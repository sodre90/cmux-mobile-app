package com.sodre90.cmuxremote.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counts, per [ConnectionSlot], how many times that slot's credentials have
 * been replaced -- by "Forget" ([AppContainer.forgetSlot]) or by a pairing
 * that commits a new bearer token and e2e secret.
 *
 * Rewriting storage is not enough on its own, because a socket opened on the
 * previous credentials stays authenticated and keeps streaming:
 *
 * - after Forget, the app carries on over the slot the user just forgot,
 *   reporting it as the live transport until the socket happens to drop or
 *   the app is restarted (cmux-app-2zn);
 * - after a re-pair, the old bearer token still maps to the agent's *old*
 *   device row, so the agent encrypts under that row's key while the app has
 *   already swapped to the new secret, and every inbound frame is dropped as
 *   decrypt_failed (cmux-app-smu).
 *
 * A counter rather than an event stream, for the same reason
 * [RelayHealth.recoveries] is one: a subscriber reads the value *before* it
 * commits to a slot and then waits for it to differ, so an invalidation
 * landing while that socket is still connecting cannot be missed.
 */
class SlotCredentials {

    private val generations = ConnectionSlot.entries.associateWith { MutableStateFlow(0L) }

    /** Advances every time [slot]'s credentials are replaced. Consumers care
     *  only that the value changed, never what it is. */
    fun generation(slot: ConnectionSlot): StateFlow<Long> = generations.getValue(slot).asStateFlow()

    /** Signals that [slot]'s stored credentials have been replaced, so
     *  anything still connected on the previous ones must let go. */
    fun invalidate(slot: ConnectionSlot) {
        generations.getValue(slot).value += 1
    }
}
