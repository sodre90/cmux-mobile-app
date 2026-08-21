package com.sodre90.cmuxremote.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether a slot's bearer token still names a device row on that slot's
 *  server, as far as this app has been able to tell. */
enum class CredentialStatus {
    /** Nothing has been checked yet in this process. Deliberately not the
     *  same as [LIVE]: reporting "fine" on no evidence at all is how a dead
     *  direct credential went unnoticed for eight days (cmux-app-hr1). */
    UNKNOWN,

    /** A server accepted this slot's token. */
    LIVE,

    /** A server answered 401 for this slot's token: the device row is gone
     *  and this slot needs re-pairing before it can serve anything. */
    REJECTED,
}

/**
 * Remembers, per [ConnectionSlot], what that slot's server last said about
 * this device's credential -- the standby half of which the app otherwise has
 * no way to see, since a slot nothing exercises is indistinguishable from a
 * slot that is fine until it becomes the only one left.
 *
 * Fed from [FallbackBridgeClient.registerDevice]'s per-slot outcomes, which
 * is the one call that touches both slots on every launch.
 *
 * Shared process-wide from [AppContainer] for the same reason [RelayHealth]
 * and [SlotCredentials] are: one process, two transports, one answer.
 * Deliberately not folded into [ConnectionMonitor], which holds a single
 * process-wide "what am I doing right now" that flickers with every request;
 * this is a durable per-slot fact about a credential.
 */
class SlotCredentialHealth {

    private val statuses = ConnectionSlot.entries.associateWith { MutableStateFlow(CredentialStatus.UNKNOWN) }

    fun status(slot: ConnectionSlot): StateFlow<CredentialStatus> = statuses.getValue(slot).asStateFlow()

    /**
     * Applies what [slot]'s server just said.
     *
     * [RegistrationOutcome.UNREACHABLE] writes nothing, and that is the most
     * important line here: a server that could not be asked has said nothing
     * about the credential, so turning every relay outage into "re-pair your
     * relay" would be inventing a diagnosis out of a network failure.
     */
    fun record(slot: ConnectionSlot, outcome: RegistrationOutcome) {
        val status = when (outcome) {
            RegistrationOutcome.ACCEPTED -> CredentialStatus.LIVE
            RegistrationOutcome.REJECTED -> CredentialStatus.REJECTED
            RegistrationOutcome.UNREACHABLE -> return
        }
        statuses.getValue(slot).value = status
    }

    /** Forgets what was known about [slot], because its credential has just
     *  been replaced or cleared. A fresh credential is [CredentialStatus.UNKNOWN],
     *  never [CredentialStatus.REJECTED] -- otherwise the Connections screen
     *  would still be demanding a re-pair on the very screen the user just
     *  re-paired from -- and never [CredentialStatus.LIVE], since no server
     *  has been asked about it yet. */
    fun reset(slot: ConnectionSlot) {
        statuses.getValue(slot).value = CredentialStatus.UNKNOWN
    }
}
