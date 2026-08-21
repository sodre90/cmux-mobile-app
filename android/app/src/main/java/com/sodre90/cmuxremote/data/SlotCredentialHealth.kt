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
 * The one bit of [SlotCredentialHealth] that has to outlive the process:
 * whether the user has already been told about a slot's current rejection.
 *
 * Without it the "notify on the transition into rejected" rule cannot be
 * honoured at all. In-memory status starts at [CredentialStatus.UNKNOWN] in
 * every process, so on a phone whose credential is already dead *every* launch
 * looks like a fresh unknown-to-rejected transition, and the notification
 * would fire on every one of them -- the nag it exists to avoid.
 *
 * [Settings] is the real implementation; tests use a plain in-memory one so
 * [SlotCredentialHealth] stays constructible without Android.
 */
interface RejectionReportLog {
    fun wasRejectionReported(slot: ConnectionSlot): Boolean
    fun setRejectionReported(slot: ConnectionSlot, reported: Boolean)
}

/** The default [RejectionReportLog]: enough to make [SlotCredentialHealth]
 *  usable on its own, but it forgets on process death, so production must
 *  supply the persisted one. */
private class InMemoryRejectionReportLog : RejectionReportLog {
    private val reportedSlots = mutableSetOf<ConnectionSlot>()

    override fun wasRejectionReported(slot: ConnectionSlot): Boolean = slot in reportedSlots

    override fun setRejectionReported(slot: ConnectionSlot, reported: Boolean) {
        if (reported) reportedSlots += slot else reportedSlots -= slot
    }
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
 *
 * [onNewRejection] fires once per rejection rather than once per observation,
 * and the "once" is enforced here rather than trusted to callers, because the
 * [reportLog] that makes it possible lives here too.
 */
class SlotCredentialHealth(
    private val reportLog: RejectionReportLog = InMemoryRejectionReportLog(),
    private val onNewRejection: (ConnectionSlot) -> Unit = {},
) {

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
        when (outcome) {
            RegistrationOutcome.ACCEPTED -> markLive(slot)
            RegistrationOutcome.REJECTED -> markRejected(slot)
            RegistrationOutcome.UNREACHABLE -> return
        }
    }

    private fun markLive(slot: ConnectionSlot) {
        statuses.getValue(slot).value = CredentialStatus.LIVE
        // So a credential that dies a second time is announced a second time.
        reportLog.setRejectionReported(slot, false)
    }

    private fun markRejected(slot: ConnectionSlot) {
        statuses.getValue(slot).value = CredentialStatus.REJECTED
        if (reportLog.wasRejectionReported(slot)) return
        reportLog.setRejectionReported(slot, true)
        onNewRejection(slot)
    }

    /** Forgets what was known about [slot], because its credential has just
     *  been replaced or cleared. A fresh credential is [CredentialStatus.UNKNOWN],
     *  never [CredentialStatus.REJECTED] -- otherwise the Connections screen
     *  would still be demanding a re-pair on the very screen the user just
     *  re-paired from -- and never [CredentialStatus.LIVE], since no server
     *  has been asked about it yet. */
    fun reset(slot: ConnectionSlot) {
        statuses.getValue(slot).value = CredentialStatus.UNKNOWN
        reportLog.setRejectionReported(slot, false)
    }
}
