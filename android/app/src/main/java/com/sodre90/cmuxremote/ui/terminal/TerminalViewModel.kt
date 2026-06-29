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
import kotlinx.coroutines.launch

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

    init {
        val s = socket
        if (s == null) {
            _state.value = TerminalUiState(error = "Bridge not configured")
        } else {
            viewModelScope.launch {
                try {
                    s.connect().collect { frame ->
                        val rg = frame.grid ?: return@collect
                        _state.value = TerminalUiState(
                            grid = RenderGridDecoder.decode(rg),
                            styles = rg.styles,
                        )
                    }
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = e.message ?: "Terminal disconnected")
                }
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
