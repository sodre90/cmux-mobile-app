package com.sodre90.cmuxremote.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.data.BridgeClient
import com.sodre90.cmuxremote.data.BridgeException
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Workspace>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Workspace>>> = _state.asStateFlow()

    // Surfaced separately from [state] so a failed rename doesn't blow away an
    // already-loaded list (mirrors InboxViewModel's error/items split).
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init {
        refresh()
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
    }
}
