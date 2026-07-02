package com.sodre90.cmuxremote.ui.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.data.pairing.PairingCodeInvalidException
import com.sodre90.cmuxremote.data.pairing.isExpired
import com.sodre90.cmuxremote.data.pairing.parsePairingQr
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data object Scanning : PairingUiState
    data object Pairing : PairingUiState
    data object Success : PairingUiState
    data class Error(val message: String) : PairingUiState
}

/** Backs the QR-scan onboarding screen. [onQrScanned] is called by the
 *  camera analyzer on every decoded barcode payload -- most calls are
 *  ignored (foreign QR content, or a scan while already mid-pairing). */
class PairingViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf<PairingUiState>(PairingUiState.Scanning)
        private set

    fun onQrScanned(raw: String) {
        if (state !is PairingUiState.Scanning) return
        val qr = parsePairingQr(raw) ?: return
        if (qr.isExpired()) {
            state = PairingUiState.Error("This code has expired -- ask the Mac to generate a new one.")
            return
        }
        state = PairingUiState.Pairing
        viewModelScope.launch {
            try {
                container.pairingClient().pair(qr)
                state = PairingUiState.Success
            } catch (e: PairingCodeInvalidException) {
                state = PairingUiState.Error("This code has expired or was already used -- scan a fresh one.")
            } catch (e: Exception) {
                state = PairingUiState.Error(e.message ?: "Pairing failed")
            }
        }
    }

    /** Returns to the scanning state after an error. */
    fun retry() {
        state = PairingUiState.Scanning
    }
}
