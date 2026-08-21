package com.sodre90.cmuxremote.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
