package com.sodre90.cmuxremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPollIntervalTest {

    @Test
    fun keepsAValueThatIsAlreadyOnTheList() {
        TERMINAL_POLL_CHOICES.forEach { assertEquals(it, nearestPollChoice(it)) }
    }

    /**
     * The case this exists for: a value stored by a version whose list differed.
     * Snapping to the neighbour keeps a selected chip on the settings screen
     * instead of none, without discarding the intent behind the stored value.
     */
    @Test
    fun snapsAnOffListValueToItsNearestNeighbour() {
        assertEquals(250, nearestPollChoice(300))
        assertEquals(500, nearestPollChoice(600))
        assertEquals(2000, nearestPollChoice(1800))
    }

    /** Nothing outside the list can survive, however far out it starts --
     *  including the values a corrupt preference file could hold. */
    @Test
    fun bringsEvenAbsurdValuesOntoTheList() {
        assertEquals(250, nearestPollChoice(0))
        assertEquals(250, nearestPollChoice(-10_000))
        assertEquals(2000, nearestPollChoice(Int.MAX_VALUE))
    }

    /** The default has to be offered, or the settings screen opens with nothing
     *  selected on a fresh install. */
    @Test
    fun theDefaultIsOneOfTheOfferedChoices() {
        assertTrue(
            "DEFAULT_TERMINAL_POLL_MS must appear in TERMINAL_POLL_CHOICES",
            DEFAULT_TERMINAL_POLL_MS in TERMINAL_POLL_CHOICES,
        )
    }

    /**
     * The bridge clamps to [250ms, 10s] (terminalPollInterval in
     * bridge/internal/server/terminal.go). Offering a choice outside that range
     * would show the user a setting the bridge silently overrides.
     */
    @Test
    fun everyOfferedChoiceSurvivesTheBridgeClamp() {
        TERMINAL_POLL_CHOICES.forEach {
            assertTrue("$it ms is below the bridge floor of 250ms", it >= 250)
            assertTrue("$it ms is above the bridge ceiling of 10s", it <= 10_000)
        }
    }
}
