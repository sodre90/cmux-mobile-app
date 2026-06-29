package com.sodre90.cmuxremote.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.model.Session
import com.sodre90.cmuxremote.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Session>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Session>>> = _state.asStateFlow()

    init {
        refresh()
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
                UiState.Ready(client.sessions())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load sessions")
            }
        }
    }
}
