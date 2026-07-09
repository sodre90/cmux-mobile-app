package com.sodre90.cmuxremote.ui.terminal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.TerminalSocket
import com.sodre90.cmuxremote.model.DecodedGrid
import com.sodre90.cmuxremote.model.RenderGridDecoder
import com.sodre90.cmuxremote.model.Style
import com.sodre90.cmuxremote.model.TerminalUp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Reconnect backoff: 1s, doubling to a 5s cap so a backgrounded app reconnects
// within a few seconds of returning to the foreground.
private const val INITIAL_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 5_000L

// Mirrors FallbackBridgeClient's penalty window: once RELAY has proven
// unreachable, don't retry it on every single reconnect for this long.
private const val RELAY_PENALTY_MS = 30_000L

// A RELAY connect that drops again within this long of its first frame is
// treated as a framing failure, not a benign disconnect.
private const val RELAY_STABLE_MS = 2_000L

// How many framing failures in a row (see RELAY_STABLE_MS) before RELAY is
// penalized the same as a connect that received zero frames.
private const val RELAY_DROP_THRESHOLD = 3

// How long a sent-but-unacknowledged message can stay pending before the UI
// treats it as stuck rather than merely in flight.
private const val ACK_STALE_MS = 1_500L

// How long an explicit ack failure (ok == false) keeps deliveryStatus at
// DELAYED before fading back to whatever pendingAcks/neverSentQueue implies.
private const val FAILURE_DISPLAY_MS = 3_000L

private const val DELIVERY_CHECK_INTERVAL_MS = 500L

private const val TAG = "TerminalInput"

data class TerminalUiState(
    val grid: DecodedGrid? = null,
    val styles: List<Style> = emptyList(),
    val error: String? = null,
)

/** Whether recently-sent input is confirmed delivered, still in flight, or
 *  stuck (sent-but-unacked past [ACK_STALE_MS], provably unsent because the
 *  socket was down, or explicitly failed per the bridge's ack). */
enum class DeliveryStatus { CONFIRMED, SENDING, DELAYED }

class TerminalViewModel(
    private val container: AppContainer,
    private val surfaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()
    private var job: Job? = null

    // Set only when a connect attempt against RELAY never receives a single
    // frame -- a genuine reachability failure, as opposed to a socket that
    // connected fine and later dropped (e.g. the app was backgrounded). While
    // set, reconnects prefer DIRECT; once it lapses, RELAY is tried first
    // again. This matters because DIRECT (Tailscale) keeps OkHttp's normal,
    // much longer connect timeout (see AppContainer.httpClient) on the
    // assumption it's only reached after RELAY has already failed -- so
    // flipping to DIRECT on a benign disconnect made the UI stall for that
    // full timeout with an unreachable Tailscale host instead of using the
    // still-healthy relay.
    @Volatile
    private var relayDownUntil: Long = 0L

    // Consecutive RELAY connects that framed (gotFrame) but dropped again
    // almost immediately after -- see RELAY_STABLE_MS/RELAY_DROP_THRESHOLD.
    // One of these alone could be a fluke; several in a row means RELAY
    // itself is unhealthy, not just a benign single disconnect.
    private var consecutiveRelayDrops = 0

    @Volatile
    private var activeSocket: TerminalSocket? = null

    // Kept separate from [state] (which the live grid-frame loop replaces
    // wholesale on every frame) so a fast terminal stream never clobbers this
    // one-time, read-only lookup. This screen has no yolo-mode edit affordance
    // -- that lives on the sessions list's long-press menu.
    private val _yoloMode = MutableStateFlow("")
    val yoloMode: StateFlow<String> = _yoloMode.asStateFlow()

    // --- Delivery reliability (seq/ack bookkeeping) ---
    // All of this is only ever touched from the main dispatcher (Compose
    // callbacks and viewModelScope's default Main.immediate coroutines), so
    // no explicit synchronization is needed.
    private var nextSeq = 1L // 0 means "unset" on the wire; never used.
    // Messages that provably never left the phone (socket was null/closed at
    // send time) -- safe to replay verbatim once a new socket connects, since
    // there's no risk of double-delivery.
    private val neverSentQueue = mutableListOf<TerminalUp>()
    // Messages that did enqueue into the socket, keyed by seq -> sent-at ms,
    // awaiting an "ack" frame. Deliberately never auto-resent: typed input
    // isn't idempotent, so an ambiguous (sent-but-unconfirmed) message is
    // reported to the user instead of risking a duplicate command.
    private val pendingAcks = mutableMapOf<Long, Long>()
    private var lastFailureAt: Long = 0L

    private val pendingOutbound = StringBuilder()
    // Gates outbound input RPCs to one in flight at a time: each `cmux rpc`
    // call is a subprocess spawn (~150ms round trip observed live), far
    // slower than key-repeat (~30-50ms) can produce keystrokes. A fixed
    // debounce window can't keep pace with that; gating on the real
    // bottleneck (the ack) means anything typed while a request is in
    // flight coalesces into the next one, so the backlog can't grow
    // unboundedly the way it did with a timer-based flush.
    private var inFlightInputSeq: Long? = null

    private val _deliveryStatus = MutableStateFlow(DeliveryStatus.CONFIRMED)
    val deliveryStatus: StateFlow<DeliveryStatus> = _deliveryStatus.asStateFlow()

    // One-shot: set when a disconnect drops non-empty pendingAcks (their fate
    // is unknowable), cleared by the UI once shown.
    private val _lostInputNotice = MutableStateFlow(false)
    val lostInputNotice: StateFlow<Boolean> = _lostInputNotice.asStateFlow()

    init {
        connect()
        loadYoloMode()
        viewModelScope.launch {
            while (isActive) {
                delay(DELIVERY_CHECK_INTERVAL_MS)
                recomputeDeliveryStatus()
            }
        }
    }

    // The terminal route is keyed by surface (pane) id, but YOLO mode is a
    // workspace-level setting -- several panes can share one workspace -- so
    // this finds the owning workspace via the existing sessions list rather
    // than needing a new bridge endpoint or extra nav args.
    private fun loadYoloMode() {
        val client = container.activeBridge() ?: return
        viewModelScope.launch {
            try {
                val ws = client.sessions().firstOrNull { ws -> ws.terminals.any { it.id == surfaceId } }
                _yoloMode.value = ws?.yoloMode.orEmpty()
            } catch (_: Exception) {
                // Best-effort display only; leave it blank on failure.
            }
        }
    }

    /** (Re)subscribe to the terminal stream, resetting to the loading state. */
    fun reconnect() = connect()

    private fun connect() {
        if (!container.anyBridgeConfigured()) {
            _state.value = TerminalUiState(error = "Bridge not configured")
            return
        }
        job?.cancel()
        _state.value = TerminalUiState() // loading (grid == null, error == null)
        // Reconnect automatically: the WebSocket is dropped when the app is
        // backgrounded (or the network blips), so retry with backoff instead of
        // leaving the user to tap Reconnect. A disconnect keeps the last grid
        // on screen — no jarring error page — while reconnection runs.
        job = viewModelScope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                val primarySlot =
                    if (System.currentTimeMillis() < relayDownUntil) ConnectionSlot.DIRECT else ConnectionSlot.RELAY
                val socket = container.terminalSocket(primarySlot, surfaceId)
                    ?: container.terminalSocket(primarySlot.other(), surfaceId)
                if (socket == null) { delay(backoff); continue }
                activeSocket = socket
                var gotFrame = false
                var connectedAtMs = 0L
                try {
                    socket.connect().collect { frame ->
                        if (!gotFrame) {
                            gotFrame = true
                            connectedAtMs = System.currentTimeMillis()
                            flushNeverSent()
                            flushPendingOutboundIfIdle()
                        }
                        if (frame.type == "ack") {
                            handleAck(frame.seq, frame.ok)
                            return@collect
                        }
                        val rg = frame.grid ?: return@collect
                        backoff = INITIAL_BACKOFF_MS
                        _state.value = TerminalUiState(
                            grid = RenderGridDecoder.decode(rg),
                            styles = rg.styles,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (primarySlot == ConnectionSlot.RELAY) {
                        if (!gotFrame) {
                            relayDownUntil = System.currentTimeMillis() + RELAY_PENALTY_MS
                            consecutiveRelayDrops = 0
                        } else if (System.currentTimeMillis() - connectedAtMs < RELAY_STABLE_MS) {
                            consecutiveRelayDrops++
                            if (consecutiveRelayDrops >= RELAY_DROP_THRESHOLD) {
                                relayDownUntil = System.currentTimeMillis() + RELAY_PENALTY_MS
                                consecutiveRelayDrops = 0
                            }
                        } else {
                            consecutiveRelayDrops = 0
                        }
                    }
                }
                // Whether the connection ended gracefully or threw, any
                // still-unacked messages have an unknowable fate now -- drop
                // them rather than risk a duplicate resend, and flag it.
                if (pendingAcks.isNotEmpty()) {
                    pendingAcks.clear()
                    _lostInputNotice.value = true
                }
                // The in-flight gate's ack (if any) can never arrive now that
                // this socket is gone -- clear it so a new connection isn't
                // stuck refusing to flush pendingOutbound forever.
                inFlightInputSeq = null
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun dismissLostInputNotice() {
        _lostInputNotice.value = false
    }

    /** Queues [text] for delivery. Coalesces rapid chunks (typed diffs,
     *  key-bar taps, paste) into one input message per bridge round trip --
     *  gated on the previous input's ack, not a fixed timer, since the
     *  bottleneck is the bridge's per-RPC subprocess spawn, not anything
     *  client-side. */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        pendingOutbound.append(text)
        flushPendingOutboundIfIdle()
    }

    private fun flushPendingOutboundIfIdle() {
        if (inFlightInputSeq != null || pendingOutbound.isEmpty()) return
        val text = pendingOutbound.toString()
        pendingOutbound.clear()
        inFlightInputSeq = dispatch(TerminalUp(type = "input", text = text))
    }

    fun resize(columns: Int, rows: Int) {
        dispatch(TerminalUp(type = "resize", columns = columns, rows = rows))
    }

    private fun dispatch(up: TerminalUp): Long {
        val stamped = up.copy(seq = nextSeq++)
        val sent = activeSocket?.send(stamped) ?: false
        Log.d(TAG, "dispatch seq=${stamped.seq} type=${stamped.type} sent=$sent text=${stamped.text?.let(::describeForLog)}")
        if (sent) {
            pendingAcks[stamped.seq] = System.currentTimeMillis()
        } else {
            neverSentQueue.add(stamped)
        }
        return stamped.seq
    }

    /** Replays messages that never actually left the phone (the socket was
     *  null/closed when they were dispatched) now that a new socket is open.
     *  Safe to replay verbatim -- they're provably not duplicates. */
    private fun flushNeverSent() {
        if (neverSentQueue.isEmpty()) return
        val queued = neverSentQueue.toList()
        neverSentQueue.clear()
        queued.forEach { up ->
            val sent = activeSocket?.send(up) ?: false
            if (sent) pendingAcks[up.seq] = System.currentTimeMillis() else neverSentQueue.add(up)
        }
    }

    private fun handleAck(seq: Long, ok: Boolean) {
        Log.d(TAG, "ack seq=$seq ok=$ok")
        pendingAcks.remove(seq)
        if (!ok) lastFailureAt = System.currentTimeMillis()
        if (seq == inFlightInputSeq) {
            inFlightInputSeq = null
            flushPendingOutboundIfIdle()
        }
    }

    private fun recomputeDeliveryStatus() {
        val now = System.currentTimeMillis()
        val oldestPending = pendingAcks.values.minOrNull()
        _deliveryStatus.value = when {
            now - lastFailureAt < FAILURE_DISPLAY_MS -> DeliveryStatus.DELAYED
            neverSentQueue.isNotEmpty() -> DeliveryStatus.DELAYED
            oldestPending == null -> DeliveryStatus.CONFIRMED
            now - oldestPending > ACK_STALE_MS -> DeliveryStatus.DELAYED
            else -> DeliveryStatus.SENDING
        }
    }

    override fun onCleared() {
        activeSocket?.close()
    }
}
