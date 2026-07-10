package com.sodre90.cmuxremote.ui.terminal

import com.sodre90.cmuxremote.model.TerminalUp
import com.sodre90.cmuxremote.model.TerminalUpType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// How long a sent-but-unacknowledged message can stay pending before the UI
// treats it as stuck rather than merely in flight.
private const val ACK_STALE_MS = 1_500L

// How long an explicit ack failure (ok == false) keeps deliveryStatus at
// DELAYED before fading back to whatever pendingAcks/neverSentQueue implies.
private const val FAILURE_DISPLAY_MS = 3_000L

/** Whether recently-sent input is confirmed delivered, still in flight, or
 *  stuck (sent-but-unacked past [ACK_STALE_MS], provably unsent because the
 *  socket was down, or explicitly failed per the bridge's ack). */
enum class DeliveryStatus { CONFIRMED, SENDING, DELAYED }

/**
 * The terminal's delivery-reliability (seq/ack) bookkeeping, owned by
 * [TerminalViewModel] and driven by its connection lifecycle ([onConnected]/
 * [onDisconnected]/[onAck]) plus a periodic [recomputeDeliveryStatus] poll.
 * [send] enqueues one message into whatever socket is currently active,
 * returning false if it provably never left the phone.
 *
 * Everything here is only ever touched from the main dispatcher (Compose
 * callbacks and viewModelScope's default Main.immediate coroutines), so no
 * explicit synchronization is needed.
 */
class DeliveryTracker(
    private val send: (TerminalUp) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
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
        inFlightInputSeq = dispatch(TerminalUp(type = TerminalUpType.INPUT, text = text))
    }

    fun resize(columns: Int, rows: Int) {
        dispatch(TerminalUp(type = TerminalUpType.RESIZE, columns = columns, rows = rows))
    }

    private fun dispatch(up: TerminalUp): Long {
        val stamped = up.copy(seq = nextSeq++)
        val sent = send(stamped)
        log("dispatch seq=${stamped.seq} type=${stamped.type} sent=$sent text=${stamped.text?.let(::describeForLog)}")
        if (sent) {
            pendingAcks[stamped.seq] = now()
        } else {
            neverSentQueue.add(stamped)
        }
        return stamped.seq
    }

    /** A new socket just delivered its first frame: replays messages that
     *  never actually left the phone (the socket was null/closed when they
     *  were dispatched) -- safe to replay verbatim, they're provably not
     *  duplicates -- then flushes anything typed while disconnected. */
    fun onConnected() {
        flushNeverSent()
        flushPendingOutboundIfIdle()
    }

    private fun flushNeverSent() {
        if (neverSentQueue.isEmpty()) return
        val queued = neverSentQueue.toList()
        neverSentQueue.clear()
        queued.forEach { up ->
            val sent = send(up)
            if (sent) pendingAcks[up.seq] = now() else neverSentQueue.add(up)
        }
    }

    /** The connection ended, gracefully or not: any still-unacked messages
     *  have an unknowable fate now -- drop them rather than risk a duplicate
     *  resend, and flag it. */
    fun onDisconnected() {
        if (pendingAcks.isNotEmpty()) {
            pendingAcks.clear()
            _lostInputNotice.value = true
        }
        // The in-flight gate's ack (if any) can never arrive now that the
        // socket is gone -- clear it so a new connection isn't stuck
        // refusing to flush pendingOutbound forever.
        inFlightInputSeq = null
    }

    fun onAck(seq: Long, ok: Boolean) {
        log("ack seq=$seq ok=$ok")
        pendingAcks.remove(seq)
        if (!ok) lastFailureAt = now()
        if (seq == inFlightInputSeq) {
            inFlightInputSeq = null
            flushPendingOutboundIfIdle()
        }
    }

    fun recomputeDeliveryStatus() {
        val now = now()
        val oldestPending = pendingAcks.values.minOrNull()
        _deliveryStatus.value = when {
            now - lastFailureAt < FAILURE_DISPLAY_MS -> DeliveryStatus.DELAYED
            neverSentQueue.isNotEmpty() -> DeliveryStatus.DELAYED
            oldestPending == null -> DeliveryStatus.CONFIRMED
            now - oldestPending > ACK_STALE_MS -> DeliveryStatus.DELAYED
            else -> DeliveryStatus.SENDING
        }
    }
}
