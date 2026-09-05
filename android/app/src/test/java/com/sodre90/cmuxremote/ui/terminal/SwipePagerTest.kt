package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the swipe->PgUp/PgDn routing rules for mouse-reporting TUI panes
 * (see [SwipePager]). The key sequences themselves are trivial; what matters
 * is WHEN movement becomes keys: never on taps, never on horizontal pans, one
 * step per page-step of vertical drag, surplus steps dropped rather than
 * queued when they arrive faster than the throttle allows.
 */
class SwipePagerTest {

    private val steps = mutableListOf<Boolean>()
    private var clockMs = 1_000L

    private fun pager() = SwipePager(
        armThresholdPx = 40f,
        pageStepPx = 120f,
        nowMillis = { clockMs },
        onStep = { up -> steps.add(up) },
    )

    @Test fun tapSizedMovementNeverRoutes() {
        val p = pager()
        assertFalse(p.onMove(0f, 30f))
        assertFalse(p.onMove(0f, -25f)) // wiggling under the threshold stays a tap
        assertEquals(0, steps.size)
    }

    @Test fun horizontalDominantDragNeverRoutes() {
        val p = pager()
        repeat(10) { p.onMove(20f, 5f) } // 200px across, 50px down
        assertEquals(0, steps.size)
    }

    @Test fun verticalDragArmsThenEmitsOneStepPerPageStep() {
        val p = pager()
        assertFalse(p.onMove(0f, 30f)) // arming threshold not reached
        // Armed (>40px) but under one 120px step: consumed, zero keys -- the
        // grid's own scroll must not fight the first page-step.
        assertTrue(p.onMove(0f, 60f))
        assertTrue(p.onMove(0f, 60f)) // 150 cumulative -> one step, 30 carry
        clockMs += 500 // past the throttle: the next full step may fire
        assertTrue(p.onMove(0f, 100f)) // 130 -> another step, 10 carry
        // +y = downward swipe = PageDown = false.
        assertEquals(listOf(false, false), steps)
    }

    @Test fun rapidSurplusStepsAreDroppedNotQueued() {
        val p = pager()
        repeat(6) { p.onMove(0f, 60f) } // 360px within one clock instant
        // One step emitted; the rest dropped -- a fast flick must not queue a
        // burst of page jumps that all land after lift-off.
        assertEquals(listOf(false), steps)
    }

    @Test fun swipeDownEmitsPageDownSwipeUpEmitsPageUp() {
        val downPager = pager()
        repeat(4) {
            downPager.onMove(0f, 60f) // 240px across 4 ticks -> two PageDowns
            clockMs += 500
        }
        assertEquals(listOf(false, false), steps)

        steps.clear()
        clockMs += 500
        val upPager = pager()
        repeat(4) {
            upPager.onMove(0f, -60f) // 240px up -> two PageUps
            clockMs += 500
        }
        assertEquals(listOf(true, true), steps)
    }

    @Test fun secondFingerCancelsRoutingForTheGesture() {
        val p = pager()
        assertTrue(p.onMove(0f, -200f)) // routed, PgUp emitted
        p.cancel()
        // The caller stops feeding the pinched-out gesture; whatever arrives
        // next belongs to a fresh one and must re-arm from zero.
        assertFalse(p.onMove(0f, -10f))
        assertEquals(listOf(true), steps)
    }

    @Test fun diagonalButMostlyVerticalMovementStillRoutes() {
        // Real swipes drift sideways; dominance is on magnitude, not purity.
        // Downward drag (+y) -> PageDown -> false.
        val p = pager()
        assertTrue(p.onMove(8f, 50f))
        assertTrue(p.onMove(-6f, 80f))
        assertEquals(listOf(false), steps)
    }
}
