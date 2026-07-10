package com.sodre90.cmuxremote.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.PairingGateway
import com.sodre90.cmuxremote.data.pairing.PairingCodeInvalidException
import com.sodre90.cmuxremote.data.pairing.PairingQr
import com.sodre90.cmuxremote.data.pairing.isExpired
import com.sodre90.cmuxremote.data.pairing.parsePairingQr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class PairingViewModel(private val pairing: PairingGateway, private val slot: ConnectionSlot) : ViewModel() {

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Scanning)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    fun onQrScanned(raw: String) {
        if (_state.value !is PairingUiState.Scanning) return
        val qr = parsePairingQr(raw) ?: return
        if (qr.isExpired()) {
            _state.value = PairingUiState.Error("This code has expired -- ask the Mac to generate a new one.")
            return
        }
        _state.value = PairingUiState.Pairing
        viewModelScope.launch {
            pairAndUpdateState(
                qr,
                invalidCodeMessage = "This code has expired or was already used -- scan a fresh one."
            )
        }
    }

    /** Manual-entry fallback for when scanning isn't possible (no camera, or
     *  pairing remotely): resolves the server URL + the code `cmux-bridge
     *  pair-device` also prints, into the same shape a scanned QR produces. */
    fun onManualEntrySubmitted(serverUrl: String, code: String) {
        if (_state.value !is PairingUiState.Scanning) return
        if (serverUrl.isBlank() || code.isBlank()) return
        _state.value = PairingUiState.Pairing
        viewModelScope.launch {
            try {
                val qr = pairing.pairingClient(slot).resolveManualCode(serverUrl.trim(), code.trim())
                pairAndUpdateState(
                    qr,
                    invalidCodeMessage = "This code has expired or was already used -- ask for a fresh one."
                )
            } catch (e: PairingCodeInvalidException) {
                _state.value = PairingUiState.Error("This code has expired or was already used -- ask for a fresh one.")
            } catch (e: Exception) {
                _state.value = PairingUiState.Error(e.message ?: "Pairing failed")
            }
        }
    }

    private suspend fun pairAndUpdateState(qr: PairingQr, invalidCodeMessage: String) {
        try {
            pairing.pairingClient(slot).pair(qr)
            _state.value = PairingUiState.Success
        } catch (e: PairingCodeInvalidException) {
            _state.value = PairingUiState.Error(invalidCodeMessage)
        } catch (e: Exception) {
            _state.value = PairingUiState.Error(e.message ?: "Pairing failed")
        }
    }

    /** Returns to the scanning state after an error. */
    fun retry() {
        _state.value = PairingUiState.Scanning
    }
}
