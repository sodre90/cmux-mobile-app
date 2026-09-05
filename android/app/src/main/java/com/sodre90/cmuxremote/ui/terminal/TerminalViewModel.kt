package com.sodre90.cmuxremote.ui.terminal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.BuildConfig
import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.SocketReconnector
import com.sodre90.cmuxremote.data.TerminalDisplayGateway
import com.sodre90.cmuxremote.data.TerminalSocket
import com.sodre90.cmuxremote.model.DecodedGrid
import com.sodre90.cmuxremote.model.RenderGridDecoder
import com.sodre90.cmuxremote.model.Style
import com.sodre90.cmuxremote.model.TerminalDown
import com.sodre90.cmuxremote.model.TerminalDownType
import com.sodre90.cmuxremote.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DELIVERY_CHECK_INTERVAL_MS = 500L

private const val TAG = "TerminalInput"

/** The decoded render-grid snapshot + its style palette -- [TerminalViewModel]'s
 *  [UiState.Ready] payload. */
data class TerminalContent(
    val grid: DecodedGrid,
    val styles: List<Style> = emptyList(),
)

// [bridgeNotConfiguredMessage] is pre-resolved `strings.xml` text passed in by
// the caller (see CmuxNavHost) rather than resolved here: a ViewModel has no
// @Composable context to call `stringResource()` itself.
class TerminalViewModel(
    private val bridge: BridgeGateway,
    private val terminalDisplay: TerminalDisplayGateway,
    private val surfaceId: String,
    private val bridgeNotConfiguredMessage: String,
    private val cancelAttentionNotification: (workspaceId: String) -> Unit = {},
) : ViewModel() {

    fun loadFontZoom(): Float = terminalDisplay.loadFontZoom()
    fun saveFontZoom(zoom: Float) = terminalDisplay.saveFontZoom(zoom)

    private val _state = MutableStateFlow<UiState<TerminalContent>>(UiState.Loading)
    val state: StateFlow<UiState<TerminalContent>> = _state.asStateFlow()
    private var job: Job? = null

    // Picks RELAY vs DIRECT per reconnect attempt, preferring DIRECT only
    // once RELAY has proven unreachable (see SocketReconnector/RelayHealth).
    // This matters because DIRECT (Tailscale) keeps OkHttp's normal, much
    // longer connect timeout (see AppContainer.httpClient) on the
    // assumption it's only reached after RELAY has already failed -- so
    // flipping to DIRECT on a benign disconnect would stall the UI for that
    // full timeout with an unreachable Tailscale host instead of using the
    // still-healthy relay.
    private val reconnector = SocketReconnector<TerminalDown>(
        bridge.relayHealth(),
        monitor = bridge.connectionMonitor(),
        slotCredentials = bridge.slotCredentials(),
    )

    @Volatile
    private var activeSocket: TerminalSocket? = null

    // Kept separate from [state] (which the live grid-frame loop replaces
    // wholesale on every frame) so a fast terminal stream never clobbers this
    // one-time, read-only lookup. This screen has no yolo-mode edit affordance
    // -- that lives on the sessions list's long-press menu.
    private val _yoloMode = MutableStateFlow("")
    val yoloMode: StateFlow<String> = _yoloMode.asStateFlow()

    // The seq/ack bookkeeping behind the never-double-send guarantee for
    // non-idempotent terminal input -- see DeliveryTracker. Its log lines
    // carry only dispatch metadata (seq/type/sent-flag) plus a redacted text
    // placeholder (see describeForLog) -- never raw keystrokes -- but
    // per-keystroke logcat output is still noise worth keeping out of
    // release builds.
    private val tracker = DeliveryTracker(
        send = { activeSocket?.send(it) ?: false },
        log = { if (BuildConfig.DEBUG) Log.d(TAG, it) },
    )

    val deliveryStatus: StateFlow<DeliveryStatus> = tracker.deliveryStatus
    val lostInputNotice: StateFlow<Boolean> = tracker.lostInputNotice

    init {
        connect()
        loadWorkspaceContext()
        viewModelScope.launch {
            while (isActive) {
                delay(DELIVERY_CHECK_INTERVAL_MS)
                tracker.recomputeDeliveryStatus()
            }
        }
    }

    // The terminal route is keyed by surface (pane) id, but both YOLO mode and
    // the attention-push notification id are workspace-level -- several panes
    // can share one workspace -- so this finds the owning workspace via the
    // existing sessions list rather than needing a new bridge endpoint or
    // extra nav args. Once found, its attention notification (if any is still
    // showing) is cancelled: the user is looking at this workspace's terminal
    // now, however they navigated here, so it's no longer unread.
    private fun loadWorkspaceContext() {
        val client = bridge.activeBridge() ?: return
        viewModelScope.launch {
            try {
                val ws = client.sessions().firstOrNull { ws -> ws.terminals.any { it.id == surfaceId } }
                _yoloMode.value = ws?.yoloMode.orEmpty()
                ws?.let { cancelAttentionNotification(it.id) }
            } catch (_: Exception) {
                // Best-effort display only; leave it blank on failure.
            }
        }
    }

    /** (Re)subscribe to the terminal stream, resetting to the loading state. */
    fun reconnect() = connect()

    private fun connect() {
        if (!bridge.anyBridgeConfigured()) {
            _state.value = UiState.Error(bridgeNotConfiguredMessage)
            return
        }
        job?.cancel()
        _state.value = UiState.Loading
        // Reconnect automatically: the WebSocket is dropped when the app is
        // backgrounded (or the network blips), so retry with backoff instead of
        // leaving the user to tap Reconnect. A disconnect keeps the last grid
        // on screen — no jarring error page — while reconnection runs.
        job = viewModelScope.launch {
            // Keyed on app foreground (see SessionsViewModel.subscribeToEvents
            // for the data rationale): backgrounded mid-session, the socket
            // tears down instead of streaming output nobody is watching, and
            // the replay snapshot resyncs it on return. A disconnect keeps
            // the last grid on screen -- no jarring error page -- while the
            // loop is down or reconnecting.
            bridge.appForeground().collectLatest { foreground ->
                if (!foreground) return@collectLatest
                reconnector.run(
                    openSocket = { slot, onOpen ->
                        bridge.terminalSocket(slot, surfaceId)?.also { activeSocket = it }?.connect(onOpen)
                    },
                    onConnected = tracker::onConnected,
                    onDisconnected = tracker::onDisconnected,
                    onFrame = onFrame@{ frame ->
                        if (frame.type == TerminalDownType.ACK) {
                            tracker.onAck(frame.seq, frame.ok)
                            return@onFrame false
                        }
                        val rg = frame.grid ?: return@onFrame false
                        val content = TerminalContent(grid = RenderGridDecoder.decode(rg), styles = rg.styles)
                        _state.value = UiState.Ready(content)
                        true
                    },
                )
            }
        }
    }

    fun dismissLostInputNotice() = tracker.dismissLostInputNotice()

    fun sendText(text: String) = tracker.sendText(text)

    fun resize(columns: Int, rows: Int) = tracker.resize(columns, rows)

    override fun onCleared() {
        activeSocket?.close()
    }
}
