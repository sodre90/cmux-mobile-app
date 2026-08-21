package com.sodre90.cmuxremote.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stands in for [com.sodre90.cmuxremote.data.Settings], which cannot be
 *  constructed in a plain JVM test. Deliberately survives being handed to a
 *  second [SlotCredentialHealth], which is how the tests below model the app
 *  being relaunched. */
private class FakeRejectionReportLog : RejectionReportLog {
    private val reportedSlots = mutableSetOf<ConnectionSlot>()

    override fun wasRejectionReported(slot: ConnectionSlot): Boolean = slot in reportedSlots

    override fun setRejectionReported(slot: ConnectionSlot, reported: Boolean) {
        if (reported) reportedSlots += slot else reportedSlots -= slot
    }
}

class SlotCredentialHealthTest {

    /** Nothing has been asked yet, and "nothing asked" must not read as
     *  "fine" -- that conflation is the whole of cmux-app-hr1. */
    @Test
    fun startsUnknownRatherThanLive() {
        val health = SlotCredentialHealth()

        assertEquals(CredentialStatus.UNKNOWN, health.status(ConnectionSlot.RELAY).value)
        assertEquals(CredentialStatus.UNKNOWN, health.status(ConnectionSlot.DIRECT).value)
    }

    @Test
    fun anAcceptedRegistrationClearsAnEarlierRejection() {
        val health = SlotCredentialHealth()

        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)
        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.ACCEPTED)

        assertEquals(CredentialStatus.LIVE, health.status(ConnectionSlot.DIRECT).value)
    }

    /** Reverting the UNREACHABLE guard fails this: a server that could not be
     *  asked has said nothing, so a rejection it did report earlier stands. */
    @Test
    fun anUnreachableServerDoesNotClearARejection() {
        val health = SlotCredentialHealth()

        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)
        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.UNREACHABLE)

        assertEquals(CredentialStatus.REJECTED, health.status(ConnectionSlot.DIRECT).value)
    }

    /** The false-alarm case: a relay outage must never be reported as "your
     *  relay credential is gone, go and re-pair it". */
    @Test
    fun anUnreachableServerDoesNotDemoteALiveCredential() {
        val health = SlotCredentialHealth()

        health.record(ConnectionSlot.RELAY, RegistrationOutcome.ACCEPTED)
        health.record(ConnectionSlot.RELAY, RegistrationOutcome.UNREACHABLE)

        assertEquals(CredentialStatus.LIVE, health.status(ConnectionSlot.RELAY).value)
    }

    @Test
    fun anUnreachableServerLeavesAnUncheckedSlotUnknown() {
        val health = SlotCredentialHealth()

        health.record(ConnectionSlot.RELAY, RegistrationOutcome.UNREACHABLE)

        assertEquals(CredentialStatus.UNKNOWN, health.status(ConnectionSlot.RELAY).value)
    }

    /** Forgetting or re-pairing a slot leaves a credential nobody has asked
     *  about yet -- not a rejected one, or the Connections screen would still
     *  demand a re-pair on the screen the user just re-paired from. */
    @Test
    fun resetReturnsARejectedSlotToUnknown() {
        val health = SlotCredentialHealth()

        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)
        health.reset(ConnectionSlot.DIRECT)

        assertEquals(CredentialStatus.UNKNOWN, health.status(ConnectionSlot.DIRECT).value)
    }

    @Test
    fun eachSlotIsTrackedIndependently() {
        val health = SlotCredentialHealth()

        health.record(ConnectionSlot.RELAY, RegistrationOutcome.ACCEPTED)
        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)

        assertEquals(CredentialStatus.LIVE, health.status(ConnectionSlot.RELAY).value)
        assertEquals(CredentialStatus.REJECTED, health.status(ConnectionSlot.DIRECT).value)
    }

    private fun healthReporting(
        log: RejectionReportLog,
        rejections: MutableList<ConnectionSlot>,
    ) = SlotCredentialHealth(reportLog = log, onNewRejection = { rejections += it })

    @Test
    fun aRejectionIsAnnouncedOnceWithinAProcess() {
        val rejections = mutableListOf<ConnectionSlot>()
        val health = healthReporting(FakeRejectionReportLog(), rejections)

        repeat(3) { health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED) }

        assertEquals(listOf(ConnectionSlot.DIRECT), rejections)
    }

    /**
     * The case in-memory state alone gets wrong, and the reason
     * [RejectionReportLog] exists: a phone whose credential is already dead
     * sees a fresh UNKNOWN -> REJECTED transition on every single launch.
     * Asserted against a report log that outlives the holder, not a re-created
     * one, because a re-created one would pass while the app nagged daily.
     */
    @Test
    fun aRejectionIsNotAnnouncedAgainInAFreshProcess() {
        val log = FakeRejectionReportLog()
        val rejections = mutableListOf<ConnectionSlot>()
        healthReporting(log, rejections).record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)

        healthReporting(log, rejections).record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)

        assertEquals(listOf(ConnectionSlot.DIRECT), rejections)
    }

    @Test
    fun aCredentialThatRecoversAndDiesAgainIsAnnouncedAgain() {
        val log = FakeRejectionReportLog()
        val rejections = mutableListOf<ConnectionSlot>()
        val health = healthReporting(log, rejections)

        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)
        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.ACCEPTED)
        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)

        assertEquals(listOf(ConnectionSlot.DIRECT, ConnectionSlot.DIRECT), rejections)
    }

    /** Re-pairing (or forgetting) clears the suppression too, so the same slot
     *  dying again after a repair is announced rather than swallowed. */
    @Test
    fun resetClearsTheSuppressionAsWellAsTheStatus() {
        val log = FakeRejectionReportLog()
        val rejections = mutableListOf<ConnectionSlot>()
        val health = healthReporting(log, rejections)

        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)
        health.reset(ConnectionSlot.DIRECT)
        health.record(ConnectionSlot.DIRECT, RegistrationOutcome.REJECTED)

        assertEquals(listOf(ConnectionSlot.DIRECT, ConnectionSlot.DIRECT), rejections)
    }

    @Test
    fun anUnreachableServerAnnouncesNothing() {
        val rejections = mutableListOf<ConnectionSlot>()
        val health = healthReporting(FakeRejectionReportLog(), rejections)

        health.record(ConnectionSlot.RELAY, RegistrationOutcome.UNREACHABLE)

        assertEquals(emptyList<ConnectionSlot>(), rejections)
    }
}
