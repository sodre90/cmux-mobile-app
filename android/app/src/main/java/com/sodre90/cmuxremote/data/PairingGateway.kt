package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.pairing.PairingSession

/**
 * The pairing surface [PairingViewModel][com.sodre90.cmuxremote.ui.pairing.PairingViewModel]
 * consumes -- narrow enough to fake in a plain JVM test, unlike the concrete
 * [AppContainer]. [AppContainer] is the sole real implementation.
 */
interface PairingGateway {
    /**
     * Must return the same instance for a given [slot] for as long as a
     * pairing is in flight. [PairingSession.prepare] mints the keypair that
     * [PairingSession.commit] submits, and PairingViewModel calls the two
     * through separate `pairingClient(slot)` lookups -- handing out a fresh
     * instance per call strands the keypair and fails every pairing.
     */
    fun pairingClient(slot: ConnectionSlot): PairingSession
}
