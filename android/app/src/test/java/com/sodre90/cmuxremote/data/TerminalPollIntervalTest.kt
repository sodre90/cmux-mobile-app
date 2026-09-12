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

    /** Both defaults have to be offered, or the settings screen opens with
     *  nothing selected on a fresh install. */
    @Test
    fun bothDefaultsAreOfferedChoices() {
        assertTrue("$DEFAULT_POLL_MS_UNMETERED is not offered", DEFAULT_POLL_MS_UNMETERED in TERMINAL_POLL_CHOICES)
        assertTrue("$DEFAULT_POLL_MS_METERED is not offered", DEFAULT_POLL_MS_METERED in TERMINAL_POLL_CHOICES)
    }

    /**
     * The point of splitting the setting: the saving has to arrive without the
     * user finding the screen, and it can only do that if the metered default
     * is the slower one.
     */
    @Test
    fun theMeteredDefaultIsSlowerThanTheUnmeteredOne() {
        assertTrue(
            "a metered link must default to polling less often than an unmetered one",
            defaultPollFor(metered = true) > defaultPollFor(metered = false),
        )
    }

    /** An unknown link is billed until proven otherwise -- guessing "free"
     *  spends the user's data without being asked. */
    @Test
    fun theUnknownLinkDefaultIsTheMeteredOne() {
        assertEquals(defaultPollFor(metered = true), DEFAULT_TERMINAL_POLL_MS)
    }

    /** A value chosen when there was a single setting was chosen to save data,
     *  so it carries to the metered side and nowhere else. */
    @Test
    fun aValueFromTheSingleSettingVersionSeedsOnlyTheMeteredSide() {
        assertEquals(2000, inheritedPollDefault(metered = true, legacy = 2000))
        assertEquals(DEFAULT_POLL_MS_UNMETERED, inheritedPollDefault(metered = false, legacy = 2000))
    }

    /** Nothing stored by the old version means nothing to inherit. */
    @Test
    fun anAbsentLegacyValueLeavesBothDefaultsAlone() {
        assertEquals(DEFAULT_POLL_MS_METERED, inheritedPollDefault(metered = true, legacy = 0))
        assertEquals(DEFAULT_POLL_MS_UNMETERED, inheritedPollDefault(metered = false, legacy = 0))
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
