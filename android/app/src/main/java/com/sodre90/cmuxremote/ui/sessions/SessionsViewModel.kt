package com.sodre90.cmuxremote.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.data.BridgeClient
import com.sodre90.cmuxremote.data.BridgeException
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Workspace>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Workspace>>> = _state.asStateFlow()

    // Surfaced separately from [state] so a failed rename doesn't blow away an
    // already-loaded list (mirrors InboxViewModel's error/items split).
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    // True while a pull-to-refresh or event-triggered refetch is in flight;
    // drives PullToRefreshBox's spinner without dropping the already-rendered
    // list into UiState.Loading (see [silentRefresh]).
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Coalesces bursts of cmux feed events (e.g. many PreToolUse frames during
    // one agent turn) into a single refetch instead of hammering `cmux rpc`.
    private val refreshRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        refresh()
        viewModelScope.launch {
            refreshRequests.debounce(EVENT_REFRESH_DEBOUNCE_MS).collect { silentRefresh() }
        }
        subscribeToEvents()
    }

    /** The phone-local custom sort order (see [com.sodre90.cmuxremote.data.WorkspaceOrderStore]). */
    fun loadOrder(): List<String> = container.workspaceOrderStore.load()

    fun saveOrder(order: List<String>) = container.workspaceOrderStore.save(order)

    /** Sets a workspace's display title in cmux, then reloads the list so the
     *  new title (cmux's single source of truth for it) comes back fresh. */
    fun renameWorkspace(id: String, title: String) {
        val client = container.bridgeClient() ?: run { _actionError.value = "Bridge not configured"; return }
        viewModelScope.launch {
            try {
                client.renameWorkspace(id, title)
                _actionError.value = null
                refresh()
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Rename failed"
            }
        }
    }

    /** Sets a workspace's YOLO auto-reply mode. Unlike [renameWorkspace], the
     *  bridge echoes back nothing to reconcile (the mode is exactly what we
     *  sent, not cmux-transformed), so this patches the already-loaded list
     *  in place rather than dropping into [UiState.Loading] and reloading
     *  the whole screen. */
    fun setYoloMode(id: String, mode: String) {
        val client = container.bridgeClient() ?: run { _actionError.value = "Bridge not configured"; return }
        viewModelScope.launch {
            try {
                client.setYoloMode(id, mode)
                _actionError.value = null
                val current = _state.value
                if (current is UiState.Ready) {
                    _state.value = UiState.Ready(
                        current.data.map { if (it.id == id) it.copy(yoloMode = mode) else it }
                    )
                }
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Setting YOLO mode failed"
            }
        }
    }

    fun refresh() {
        val client = container.bridgeClient()
        if (client == null) {
            _state.value = UiState.Error("Bridge not configured")
            return
        }
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Ready(fetchSessionsWithPairingRetry(client))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load sessions")
            }
        }
    }

    /** Refetches the session list without dropping it into [UiState.Loading] --
     *  used by pull-to-refresh and by [subscribeToEvents]'s change-triggered
     *  refetch, neither of which should flash the full-screen spinner over an
     *  already-rendered list. Skips if a refresh is already in flight so a
     *  burst of events collapses onto one request. */
    fun silentRefresh() {
        val client = container.bridgeClient() ?: run { _actionError.value = "Bridge not configured"; return }
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                _state.value = UiState.Ready(fetchSessionsWithPairingRetry(client))
                _actionError.value = null
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Failed to refresh sessions"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // Re-fetch on cmux agent activity: SessionStart/SessionEnd change which
    // workspaces exist, Notification/Stop change attention + preview. Mirrors
    // InboxViewModel's reconnect-with-backoff loop over the same /events
    // socket.
    private fun subscribeToEvents() {
        val events = container.eventsSocket() ?: return
        viewModelScope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    events.connect().collect { frame ->
                        backoff = INITIAL_BACKOFF_MS
                        if (frame.type != "heartbeat") refreshRequests.tryEmit(Unit)
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (_: Exception) {
                    // Transient drop; reconnect below.
                }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                refreshRequests.tryEmit(Unit) // catch up on anything missed while disconnected
            }
        }
    }

    // Right after pairing, the phone can call this before the Mac agent's
    // pair-device poll loop has derived and stored the e2e session -- the
    // relay authenticates the device's token fine, but the agent replies 409
    // not_paired for that narrow window. Retry a few times before surfacing
    // it as a real error.
    private suspend fun fetchSessionsWithPairingRetry(client: BridgeClient): List<Workspace> {
        repeat(NOT_PAIRED_RETRY_ATTEMPTS - 1) {
            try {
                return client.sessions()
            } catch (e: BridgeException) {
                if (e.code != 409) throw e
                delay(NOT_PAIRED_RETRY_DELAY_MS)
            }
        }
        return client.sessions()
    }

    private companion object {
        const val NOT_PAIRED_RETRY_ATTEMPTS = 3
        const val NOT_PAIRED_RETRY_DELAY_MS = 500L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5_000L
        const val EVENT_REFRESH_DEBOUNCE_MS = 800L
    }
}
