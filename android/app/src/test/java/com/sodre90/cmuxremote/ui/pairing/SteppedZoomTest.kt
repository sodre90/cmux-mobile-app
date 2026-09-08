package com.sodre90.cmuxremote.ui.pairing

import com.sodre90.cmuxremote.ui.terminal.MAX_ZOOM
import com.sodre90.cmuxremote.ui.terminal.MIN_ZOOM
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stepper used to add or subtract a fixed offset from whatever value the
 * last pinch persisted, so a zoom of 1.36 could only ever reach 1.11/1.36/1.61
 * -- the round values the buttons are supposed to produce were unreachable, and
 * so was any way back onto the grid.
 */
class SteppedZoomTest {

    private val delta = 1e-4f

    @Test
    fun aValueOffTheGridSnapsOntoIt() {
        assertEquals(1.25f, steppedZoomDown(1.36f), delta)
        assertEquals(1.5f, steppedZoomUp(1.36f), delta)
    }

    @Test
    fun aValueOnTheGridMovesExactlyOneStep() {
        assertEquals(1.25f, steppedZoomDown(1.5f), delta)
        assertEquals(1.75f, steppedZoomUp(1.5f), delta)
    }

    @Test
    fun steppingStaysWithinTheZoomBounds() {
        assertEquals(MIN_ZOOM, steppedZoomDown(MIN_ZOOM), delta)
        assertEquals(MAX_ZOOM, steppedZoomUp(MAX_ZOOM), delta)
    }

    @Test
    fun repeatedStepsKeepLandingOnRoundValues() {
        var zoom = 1.63f
        repeat(3) { zoom = steppedZoomUp(zoom) }
        assertEquals(2.25f, zoom, delta)
    }
}
