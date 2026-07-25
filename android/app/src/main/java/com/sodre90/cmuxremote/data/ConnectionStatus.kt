package com.sodre90.cmuxremote.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app is currently doing about its two transports, so the failover
 * that [FallbackBridgeClient] and [SocketReconnector] perform silently becomes
 * visible instead of showing up only as "it feels like it's on Tailscale".
 */
sealed interface ConnectionStatus {
    /** Nothing attempted yet this process. */
    data object Unknown : ConnectionStatus

    /** A request/socket is in flight against [slot]. */
    data class Connecting(val slot: ConnectionSlot) : ConnectionStatus

    /** [slot] last served successfully. */
    data class Connected(val slot: ConnectionSlot) : ConnectionStatus

    /**
     * RELAY failed and DIRECT is being tried instead. [reason] is the relay's
     * own error, or null when relay was skipped inside [RelayHealth]'s
     * penalty window rather than attempted and failed.
     */
    data class FallingBack(val reason: String?) : ConnectionStatus

    /** Neither transport could serve the last attempt. */
    data class Failed(val detail: String) : ConnectionStatus
}

/**
 * Process-wide holder for the current [ConnectionStatus]. One instance is
 * shared by the REST path and every socket subscription (see [AppContainer]),
 * because they are all really talking about the same two transports -- a
 * per-caller status would flicker between contradictory answers.
 *
 * Writes come from whichever coroutine is doing the network work, so the
 * backing [MutableStateFlow] carries the required synchronization.
 */
class ConnectionMonitor {
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    fun connecting(slot: ConnectionSlot) {
        _status.value = ConnectionStatus.Connecting(slot)
    }

    fun connected(slot: ConnectionSlot) {
        _status.value = ConnectionStatus.Connected(slot)
    }

    fun fallingBack(reason: String?) {
        _status.value = ConnectionStatus.FallingBack(reason)
    }

    fun failed(detail: String) {
        _status.value = ConnectionStatus.Failed(detail)
    }
}
