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
 *  - the FIRST step lands after [firstStepPx], later ones every [pageStepPx].
 *    They differ because the two are answering different questions: the first
 *    is "has this become a scroll?", and it wants answering early, since
 *    nothing moves at all until it fires and the answer then costs a round trip
 *    to become visible. Later steps are "how much further?", which wants the
 *    longer, steadier spacing;
 *  - direction is direct manipulation: dragging DOWN pulls earlier output into
 *    view and is therefore PgUp. Surplus carries over;
 *  - a second finger (pinch) aborts routing for the rest of the gesture.
 */
internal class SwipePager(
    private val armThresholdPx: Float,
    private val pageStepPx: Float,
    private val firstStepPx: Float = pageStepPx,
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

    // One step per [minStepIntervalMs], surplus DROPPED rather than queued.
    // Only meaningful when [pageStepPx] is small enough that one gesture can
    // ask for many steps; the caller sizes a step to a quarter of the viewport
    // (see TerminalScreen) so a gesture asks for a few at most, and passes 0 to keep
    // every step. A zero interval never drops: distance alone decides how far
    // the pane moves, so the same gesture scrolls the same amount whether it
    // was flicked or dragged.
    private fun emitSteps() {
        while (abs(totalY) >= currentStepPx()) {
            val step = currentStepPx()
            val now = nowMillis()
            if (!firstStep && now - lastEmitAtMs < minStepIntervalMs) {
                totalY += if (totalY < 0) step else -step
                continue
            }
            // Direct manipulation: dragging DOWN pulls earlier output into
            // view, which is PgUp -- the same sense as RenderGridView's own
            // verticalScroll on every non-routed pane (cmux-app-a5v).
            val up = totalY > 0
            onStep(up)
            firstStep = false
            lastEmitAtMs = now
            totalY += if (totalY < 0) step else -step
        }
    }

    private fun currentStepPx(): Float = if (firstStep) firstStepPx else pageStepPx
}
