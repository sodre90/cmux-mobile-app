package com.sodre90.cmuxremote.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
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

data class TerminalUiState(
    val grid: DecodedGrid? = null,
    val styles: List<Style> = emptyList(),
    val error: String? = null,
)

class TerminalViewModel(
    container: AppContainer,
    surfaceId: String,
) : ViewModel() {

    private val socket = container.terminalSocket(surfaceId)
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()
    private var job: Job? = null

    // Kept separate from [state] (which the live grid-frame loop replaces
    // wholesale on every frame) so a fast terminal stream never clobbers this
    // one-time, read-only lookup. This screen has no yolo-mode edit affordance
    // -- that lives on the sessions list's long-press menu.
    private val _yoloMode = MutableStateFlow("")
    val yoloMode: StateFlow<String> = _yoloMode.asStateFlow()

    init {
        connect()
        loadYoloMode(container, surfaceId)
    }

    // The terminal route is keyed by surface (pane) id, but YOLO mode is a
    // workspace-level setting -- several panes can share one workspace -- so
    // this finds the owning workspace via the existing sessions list rather
    // than needing a new bridge endpoint or extra nav args.
    private fun loadYoloMode(container: AppContainer, surfaceId: String) {
        val client = container.bridgeClient() ?: return
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
        val s = socket
        if (s == null) {
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
                try {
                    s.connect().collect { frame ->
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
                    // Transient drop: keep the last grid visible and retry.
                }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun sendText(text: String) {
        socket?.send(TerminalUp(type = "input", text = text))
    }

    fun resize(columns: Int, rows: Int) {
        socket?.send(TerminalUp(type = "resize", columns = columns, rows = rows))
    }

    override fun onCleared() {
        socket?.close()
    }
}
