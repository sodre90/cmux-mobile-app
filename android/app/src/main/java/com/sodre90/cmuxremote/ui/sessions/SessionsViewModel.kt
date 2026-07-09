package com.sodre90.cmuxremote.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.BridgeException
import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.FallbackBridgeClient
import com.sodre90.cmuxremote.data.SocketReconnector
import com.sodre90.cmuxremote.data.WorkspaceOrderGateway
import com.sodre90.cmuxremote.model.EventFrame
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.UiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SessionsViewModel(
    private val bridge: BridgeGateway,
    private val workspaceOrder: WorkspaceOrderGateway,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Workspace>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Workspace>>> = _state.asStateFlow()

    // Surfaced separately from [state] so a failed rename doesn't blow away an
    // already-loaded list (mirrors InboxViewModel's error/items split).
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    // True only while a user-initiated refresh (pull gesture or the Refresh
    // button) is in flight -- drives PullToRefreshBox's spinner. Background
    // auto-refresh (see [subscribeToEvents]) deliberately does NOT set this;
    // it should update the list with no visible indicator at all.
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Guards against overlapping fetches from any source (pull, button, or
    // auto-refresh) -- separate from [_isRefreshing], which is UI-only.
    private var fetchInFlight = false

    // Coalesces bursts of cmux feed events (e.g. many PreToolUse frames during
    // one agent turn) into a single refetch instead of hammering `cmux rpc`.
    private val refreshRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val reconnector = SocketReconnector<EventFrame>(bridge.relayHealth())

    init {
        refresh()
        viewModelScope.launch {
            refreshRequests.debounce(EVENT_REFRESH_DEBOUNCE_MS).collect { autoRefresh() }
        }
        subscribeToEvents()
    }

    /** The phone-local custom sort order (see [com.sodre90.cmuxremote.data.WorkspaceOrderStore]). */
    fun loadOrder(): List<String> = workspaceOrder.loadOrder()

    fun saveOrder(order: List<String>) = workspaceOrder.saveOrder(order)

    /** Sets a workspace's display title in cmux, then reloads the list so the
     *  new title (cmux's single source of truth for it) comes back fresh. */
    fun renameWorkspace(id: String, title: String) {
        val client = bridge.activeBridge() ?: run { _actionError.value = "Bridge not configured"; return }
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
        val client = bridge.activeBridge() ?: run { _actionError.value = "Bridge not configured"; return }
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
        val client = bridge.activeBridge()
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

    /** User-initiated refresh (pull-to-refresh gesture or the Refresh button)
     *  -- refetches without dropping the list into [UiState.Loading], and
     *  shows [isRefreshing] so PullToRefreshBox's spinner appears. Skips if a
     *  fetch is already in flight. */
    fun silentRefresh() {
        val client = bridge.activeBridge() ?: run { _actionError.value = "Bridge not configured"; return }
        if (fetchInFlight) return
        viewModelScope.launch {
            fetchInFlight = true
            _isRefreshing.value = true
            try {
                fetchAndApply(client)
            } finally {
                _isRefreshing.value = false
                fetchInFlight = false
            }
        }
    }

    /** Background refresh triggered by [subscribeToEvents] -- same non-
     *  blocking refetch as [silentRefresh] but never touches [isRefreshing],
     *  so cmux agent activity the user didn't ask about never pops the
     *  pull-to-refresh spinner. */
    private fun autoRefresh() {
        val client = bridge.activeBridge() ?: return
        if (fetchInFlight) return
        viewModelScope.launch {
            fetchInFlight = true
            try {
                fetchAndApply(client)
            } finally {
                fetchInFlight = false
            }
        }
    }

    private suspend fun fetchAndApply(client: FallbackBridgeClient) {
        try {
            _state.value = UiState.Ready(fetchSessionsWithPairingRetry(client))
            _actionError.value = null
        } catch (e: Exception) {
            _actionError.value = e.message ?: "Failed to refresh sessions"
        }
    }

    // Re-fetch on cmux agent activity: SessionStart/SessionEnd change which
    // workspaces exist, Notification/Stop change attention + preview. Mirrors
    // InboxViewModel's reconnect-with-backoff loop over the same /events
    // socket.
    private fun subscribeToEvents() {
        if (!bridge.anyBridgeConfigured()) return
        viewModelScope.launch {
            reconnector.run(
                openSocket = { slot -> bridge.eventsSocket(slot)?.connect() },
                onBeforeReconnect = { refreshRequests.tryEmit(Unit) }, // catch up on anything missed while disconnected
            ) { frame ->
                if (frame.type != "heartbeat") refreshRequests.tryEmit(Unit)
                true
            }
        }
    }

    // Right after pairing, the phone can call this before the Mac agent's
    // pair-device poll loop has derived and stored the e2e session -- the
    // relay authenticates the device's token fine, but the agent replies 409
    // not_paired for that narrow window. Retry a few times before surfacing
    // it as a real error.
    private suspend fun fetchSessionsWithPairingRetry(client: FallbackBridgeClient): List<Workspace> {
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
        const val EVENT_REFRESH_DEBOUNCE_MS = 800L
    }
}
