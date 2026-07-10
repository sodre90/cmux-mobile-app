package com.sodre90.cmuxremote.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.BridgeGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the "Send test notification" button on [ConnectionSettingsScreen] --
 *  a debugging tool, so [Error] deliberately carries the real failure message
 *  (see [BridgeException][com.sodre90.cmuxremote.data.BridgeException]'s
 *  `bodyText`) rather than a generic string. */
sealed interface TestPushUiState {
    data object Idle : TestPushUiState
    data object Sending : TestPushUiState
    data object Success : TestPushUiState
    data class Error(val message: String) : TestPushUiState
}

/** Backs [ConnectionSettingsScreen]'s "Send test notification" button --
 *  see bridge/internal/server/test_push.go and
 *  bridge/internal/relay/testpush.go's `POST /devices/test-push`.
 *
 *  The error-message parameters are pre-resolved `strings.xml` text passed in
 *  by the caller (see CmuxNavHost) rather than resolved here: a ViewModel has
 *  no @Composable context to call `stringResource()` itself. */
class ConnectionSettingsViewModel(
    private val bridge: BridgeGateway,
    private val bridgeNotConfiguredMessage: String,
    private val testPushFailedMessage: String,
) : ViewModel() {

    private val _testPushState = MutableStateFlow<TestPushUiState>(TestPushUiState.Idle)
    val testPushState: StateFlow<TestPushUiState> = _testPushState.asStateFlow()

    fun sendTestPush() {
        if (_testPushState.value is TestPushUiState.Sending) return
        val client = bridge.activeBridge()
        if (client == null) {
            _testPushState.value = TestPushUiState.Error(bridgeNotConfiguredMessage)
            return
        }
        _testPushState.value = TestPushUiState.Sending
        viewModelScope.launch {
            try {
                client.sendTestPush()
                _testPushState.value = TestPushUiState.Success
            } catch (e: Exception) {
                _testPushState.value = TestPushUiState.Error(e.message ?: testPushFailedMessage)
            }
        }
    }
}
