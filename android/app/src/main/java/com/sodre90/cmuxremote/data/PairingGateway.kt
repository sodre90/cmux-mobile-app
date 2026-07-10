package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.pairing.PairingSession

/**
 * The pairing surface [PairingViewModel][com.sodre90.cmuxremote.ui.pairing.PairingViewModel]
 * consumes -- narrow enough to fake in a plain JVM test, unlike the concrete
 * [AppContainer]. [AppContainer] is the sole real implementation.
 */
interface PairingGateway {
    fun pairingClient(slot: ConnectionSlot): PairingSession
}
