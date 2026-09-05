package com.sodre90.cmuxremote.ui.terminal

import kotlin.math.abs

/**
 * Turns a stream of vertical drag deltas into page-scroll key presses for
 * mouse-reporting TUI panes (opencode et al.), whose PTY scrollback is empty
 * -- see TerminalScreen for why swipes must become PgUp/PgDn rather than local
 * scrolling.
 *
 * Rules, all pure here so they are unit-testable without a live renderer:
 *  - horizontal-dominant movement never routes (horizontal panning stays with
 *    the grid's own horizontal scroll);
 *  - routing arms once cumulative vertical movement exceeds [armThresholdPx]
 *    in the vertical-dominant direction, so taps never fire keys;
 *  - while routed, every [pageStepPx] of accumulated drag emits exactly one
 *    step (up = swipe toward the top = PgUp), surplus carrying over;
 *  - a second finger (pinch) aborts routing for the rest of the gesture.
 */
internal class SwipePager(
    private val armThresholdPx: Float,
    private val pageStepPx: Float,
    private val minStepIntervalMs: Long = 140,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onStep: (up: Boolean) -> Unit,
) {
    private var totalY = 0f
    private var totalX = 0f
    private var routed = false
    private var firstStep = true
    private var lastEmitAtMs = 0L

    /** Feed one move event's delta; true when the caller should consume it. */
    fun onMove(dx: Float, dy: Float): Boolean {
        totalX += dx
        totalY += dy
        if (!routed &&
            abs(totalY) > armThresholdPx &&
            abs(totalY) > abs(totalX)
        ) {
            routed = true
        }
        if (!routed) return false
        emitSteps()
        return true
    }

    /** A second pointer went down: pinch owns the rest of this gesture. The
     *  caller stops feeding moves for the remainder of the gesture; state is
     *  reset so the next gesture (the instance is reused across gestures)
     *  starts from zero rather than inheriting this one's drift. */
    fun cancel() {
        routed = false
        totalX = 0f
        totalY = 0f
    }

    // One step per [minStepIntervalMs], surplus DROPPED rather than queued:
    // each press jumps a whole page in the TUI, so bursting a fast swipe's
    // full distance made it lurch through several pages after lift-off. A
    // fast flick deliberately scrolls less far than its raw pixel distance.
    private fun emitSteps() {
        while (abs(totalY) >= pageStepPx) {
            val now = nowMillis()
            if (!firstStep && now - lastEmitAtMs < minStepIntervalMs) {
                totalY += if (totalY < 0) pageStepPx else -pageStepPx
                continue
            }
            val up = totalY < 0
            onStep(up)
            firstStep = false
            lastEmitAtMs = now
            totalY += if (up) pageStepPx else -pageStepPx
        }
    }
}
