package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the swipe->PgUp/PgDn routing rules for panes that own their scrolling
 * (see [SwipePager]). The key sequences themselves are trivial; what matters is
 * WHEN movement becomes keys: never on taps, never on horizontal pans, one step
 * per page-step of vertical drag, in the direct-manipulation direction (drag
 * DOWN pulls earlier output down = PgUp), and -- when a throttle is configured
 * -- surplus dropped rather than queued.
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
        // +y = downward drag = pull earlier output into view = PageUp = true.
        assertEquals(listOf(true, true), steps)
    }

    @Test fun rapidSurplusStepsAreDroppedNotQueued() {
        val p = pager()
        repeat(6) { p.onMove(0f, 60f) } // 360px within one clock instant
        // One step emitted; the rest dropped -- a fast flick must not queue a
        // burst of page jumps that all land after lift-off.
        assertEquals(listOf(true), steps)
    }

    @Test fun swipeDownEmitsPageUpSwipeUpEmitsPageDown() {
        // Direct manipulation, matching RenderGridView's verticalScroll on every
        // non-routed pane: dragging down pulls EARLIER output into view.
        val downPager = pager()
        repeat(4) {
            downPager.onMove(0f, 60f) // 240px down -> two PageUps
            clockMs += 500
        }
        assertEquals(listOf(true, true), steps)

        steps.clear()
        clockMs += 500
        val upPager = pager()
        repeat(4) {
            upPager.onMove(0f, -60f) // 240px up -> two PageDowns
            clockMs += 500
        }
        assertEquals(listOf(false, false), steps)
    }

    @Test fun secondFingerCancelsRoutingForTheGesture() {
        val p = pager()
        assertTrue(p.onMove(0f, -200f)) // routed, PgDn emitted
        p.cancel()
        // The caller stops feeding the pinched-out gesture; whatever arrives
        // next belongs to a fresh one and must re-arm from zero.
        assertFalse(p.onMove(0f, -10f))
        assertEquals(listOf(false), steps)
    }

    /** How TerminalScreen actually configures it: a step is a quarter of the
     *  viewport -- one comfortable swipe -- and the throttle is off so distance
     *  alone decides. */
    private fun viewportPager(viewportPx: Float = 240f) = SwipePager(
        armThresholdPx = 40f,
        pageStepPx = viewportPx / 4f,
        minStepIntervalMs = 0L,
        nowMillis = { clockMs },
        onStep = { up -> steps.add(up) },
    )

    @Test fun sameDistanceScrollsTheSameWhetherFlickedOrDragged() {
        // The old config dropped surplus under a 140ms throttle, so a fast
        // flick and a slow drag of the SAME distance scrolled different
        // amounts -- the thing that made this feel unpredictable.
        val flick = viewportPager()
        repeat(4) { flick.onMove(0f, 60f) } // 240px in one clock instant
        val flicked = steps.toList()

        steps.clear()
        val drag = viewportPager()
        repeat(4) {
            drag.onMove(0f, 60f) // same 240px, spread over time
            clockMs += 300
        }
        assertEquals(flicked, steps)
        assertEquals(List(4) { true }, steps) // 240px / 60px step = four PgUps
    }

    @Test fun aSwipeMustCrossAWholeStepBeforeThePaneMoves() {
        // The pane's quantum is half its screen whatever we do, so the step only
        // decides how much swiping buys one; anything shorter must stay silent
        // rather than round up to a half-screen jump.
        val p = viewportPager()
        p.onMove(0f, 50f) // armed (>40px), but under the 60px step
        assertEquals(0, steps.size)
    }

    @Test fun aStepIsShortEnoughForOneRealSwipe() {
        // The regression this replaces: a step of half the viewport was longer
        // than a thumb can travel, so an ordinary swipe emitted nothing at all
        // and the pane only crawled by whatever slop leaked to its local scroll.
        val viewportPx = 1638f // the device this was measured on
        val p = viewportPager(viewportPx)
        p.onMove(0f, 600f) // an ordinary swipe
        assertEquals(listOf(true), steps)
    }

    @Test fun diagonalButMostlyVerticalMovementStillRoutes() {
        // Real swipes drift sideways; dominance is on magnitude, not purity.
        // Downward drag (+y) -> PageUp -> true.
        val p = pager()
        assertTrue(p.onMove(8f, 50f))
        assertTrue(p.onMove(-6f, 80f))
        assertEquals(listOf(true), steps)
    }

    @Test fun theFirstStepLandsEarlierThanTheRest() {
        // Time-to-first-feedback is what reads as lag: nothing moves at all
        // until this fires, and it then costs a round trip to become visible.
        val steps = mutableListOf<Boolean>()
        val p = SwipePager(
            armThresholdPx = 40f,
            pageStepPx = 400f,
            firstStepPx = 200f,
            minStepIntervalMs = 0L,
            nowMillis = { clockMs },
            onStep = { up -> steps.add(up) },
        )
        p.onMove(0f, 200f) // first step's shorter distance is enough
        assertEquals(1, steps.size)
        p.onMove(0f, 200f) // ... but the same distance again is not
        assertEquals(1, steps.size)
        p.onMove(0f, 200f) // 400 past the first step -> second fires
        assertEquals(2, steps.size)
    }

    @Test fun anUnsetFirstStepFallsBackToThePageStep() {
        // The default keeps every existing caller on one uniform step.
        val steps = mutableListOf<Boolean>()
        val p = SwipePager(
            armThresholdPx = 40f,
            pageStepPx = 120f,
            minStepIntervalMs = 0L,
            nowMillis = { clockMs },
            onStep = { up -> steps.add(up) },
        )
        p.onMove(0f, 119f)
        assertEquals(0, steps.size)
        p.onMove(0f, 1f)
        assertEquals(1, steps.size)
    }
}
