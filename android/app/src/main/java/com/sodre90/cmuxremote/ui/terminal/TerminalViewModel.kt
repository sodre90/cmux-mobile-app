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
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.UiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DELIVERY_CHECK_INTERVAL_MS = 500L

private const val TAG = "TerminalInput"

/** How long the measured viewport must hold still before it is worth telling
 *  the Mac about. Long enough to outlast inset/IME settling, short enough that
 *  a real rotation still feels immediate. */
internal const val RESIZE_SETTLE_MS = 150L

/** A measured surface viewport, in terminal cells. */
data class GridSize(val columns: Int, val rows: Int)

/**
 * Collapses a burst of viewport measurements into the one it settles on.
 *
 * BoxWithConstraints re-measures as the status/nav insets and the IME resolve,
 * and every distinct (columns, rows) along the way used to become its own
 * resize RPC -- twelve within a second of opening a pane, observed on device.
 * Each one makes cmux re-layout the surface and ship back a whole replay frame,
 * which is the most expensive call the bridge makes.
 *
 * [distinctUntilChanged] then drops a settled size equal to the last one sent,
 * so a measurement that ends where it began costs nothing at all.
 */
@OptIn(FlowPreview::class)
internal fun Flow<GridSize>.settledSizes(settleMs: Long = RESIZE_SETTLE_MS): Flow<GridSize> =
    debounce(settleMs).distinctUntilChanged()

/** The decoded render-grid snapshot + its style palette -- [TerminalViewModel]'s
 *  [UiState.Ready] payload. */
data class TerminalContent(
    val grid: DecodedGrid,
    val styles: List<Style> = emptyList(),
    /** The socket behind this grid is down, so it is the last known screen rather
     *  than the live one. Keeping the grid on screen avoids a jarring error page,
     *  but without this flag a frozen frame is pixel-identical to an idle agent. */
    val stale: Boolean = false,
)

/** Names the pane in the terminal's top bar: which workspace, which pane within
 *  it, and the workspace's color so the identity carries over from the sessions
 *  list. Blank fields render nothing, so a pane whose workspace could not be
 *  resolved falls back to the plain "Terminal" title. */
data class PaneLabel(
    val workspace: String = "",
    val pane: String = "",
    val color: String = "",
)

/**
 * Builds the top-bar label. The pane's own title is dropped when it merely
 * repeats the workspace's -- cmux gives the agent pane the workspace name, so
 * showing both would just print it twice.
 */
internal fun paneLabelOf(workspace: Workspace?, surfaceId: String): PaneLabel {
    val ws = workspace ?: return PaneLabel()
    val name = ws.title.ifBlank { ws.cwd.substringAfterLast('/') }
    val pane = ws.terminals.firstOrNull { it.id == surfaceId }?.title.orEmpty()
    return PaneLabel(
        workspace = name,
        pane = if (pane == name || pane.isBlank()) "" else pane,
        color = ws.customColor,
    )
}

/**
 * Flags the on-screen grid as the last known one rather than the live one.
 *
 * Only meaningful once something is rendered: before the first frame the screen
 * is [UiState.Loading], which already says the same thing, and an [UiState.Error]
 * is not showing a grid to caveat. Extracted from the ViewModel so the transition
 * is testable without standing up a socket harness (same split as
 * [com.sodre90.cmuxremote.ui.connectionStatusStrip]). A fresh frame carries the
 * default `stale = false`, so arriving output clears this on its own.
 */
internal fun staleMarked(shown: UiState<TerminalContent>): UiState<TerminalContent> =
    if (shown is UiState.Ready && !shown.data.stale) {
        UiState.Ready(shown.data.copy(stale = true))
    } else {
        shown
    }

// [bridgeNotConfiguredMessage] and [surfaceGoneMessage] are pre-resolved
// `strings.xml` text passed in by the caller (see CmuxNavHost) rather than
// resolved here: a ViewModel has no @Composable context to call
// `stringResource()` itself.
class TerminalViewModel(
    private val bridge: BridgeGateway,
    private val terminalDisplay: TerminalDisplayGateway,
    private val surfaceId: String,
    private val bridgeNotConfiguredMessage: String,
    private val surfaceGoneMessage: String,
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

    // Which agent this pane belongs to. The bar said "Terminal" on every pane,
    // so with several workspaces of 2-3 panes each -- and pushes deep-linking
    // straight into one -- there was no way to tell what you were looking at
    // without scrolling back to find a prompt.
    private val _paneLabel = MutableStateFlow(PaneLabel())
    val paneLabel: StateFlow<PaneLabel> = _paneLabel.asStateFlow()

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

    // Every viewport measurement the screen makes; only the settled ones reach
    // the wire (see [settledSizes]). DROP_OLDEST because a superseded
    // measurement has no value -- the newest one is the only one that is true.
    private val measuredSizes =
        MutableSharedFlow<GridSize>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        connect()
        loadWorkspaceContext()
        viewModelScope.launch {
            measuredSizes.settledSizes().collect { tracker.resize(it.columns, it.rows) }
        }
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
                _paneLabel.value = paneLabelOf(ws, surfaceId)
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
                    onDisconnected = {
                        tracker.onDisconnected()
                        markScreenStale()
                    },
                    // Replaces the grid outright rather than caveating it as
                    // stale: a pane that no longer exists is not coming back,
                    // and the spinner this used to leave up said nothing at
                    // all (cmux-app-34c). UiState.Error's Reconnect button is
                    // still the way out, now one deliberate tap instead of a
                    // silent retry every 5s.
                    onGone = { _state.value = UiState.Error(surfaceGoneMessage) },
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

    private fun markScreenStale() {
        _state.value = staleMarked(_state.value)
    }

    fun dismissLostInputNotice() = tracker.dismissLostInputNotice()

    fun sendText(text: String) = tracker.sendText(text)

    fun resize(columns: Int, rows: Int) {
        measuredSizes.tryEmit(GridSize(columns, rows))
    }

    override fun onCleared() {
        activeSocket?.close()
    }
}
