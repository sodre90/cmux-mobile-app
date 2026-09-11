package com.sodre90.cmuxremote.ui.terminal

import kotlin.math.abs

/**
 * Turns the part of a vertical drag that the local render buffer could NOT
 * absorb into page-scroll key presses for the pane itself -- see TerminalScreen
 * for how that overscroll is collected and why the keys are PgUp/PgDn.
 *
 * Everything fed here is already overscroll, which is what removes the arming
 * threshold this used to carry: the grid's own scroll has had first refusal on
 * the gesture and reports only what it could not use, so there is nothing left
 * to arm against. What survives is the direction rule.
 *
 * Rules, all pure here so they are unit-testable without a live renderer:
 *  - horizontal-dominant movement never routes (horizontal panning stays with
 *    the grid's own horizontal scroll);
 *  - the FIRST step lands after [firstStepPx], later ones every [pageStepPx].
 *    They differ because the two are answering different questions: the first
 *    is "has this become a scroll?", and it wants answering early, since
 *    nothing moves at all until it fires and the answer then costs a round trip
 *    to become visible. Later steps are "how much further?", which wants the
 *    longer, steadier spacing;
 *  - direction is direct manipulation: dragging DOWN pulls earlier output into
 *    view and is therefore PgUp. Surplus carries over;
 *  - [reset] ends a gesture, so the next one starts from zero rather than
 *    inheriting this one's drift or its spent first step.
 */
internal class SwipePager(
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

    /** Feed one scroll event's unconsumed delta. */
    fun onOverscroll(dx: Float, dy: Float) {
        totalX += dx
        totalY += dy
        if (!routed && abs(totalY) > abs(totalX)) routed = true
        if (routed) emitSteps()
    }

    /** The gesture is over (lift-off, or a second finger claiming it for a
     *  pinch). The instance outlives a single gesture, so clear everything the
     *  next one must not inherit -- including the spent first step, whose
     *  shorter distance is what every gesture opens with. */
    fun reset() {
        routed = false
        totalX = 0f
        totalY = 0f
        firstStep = true
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
            // verticalScroll, which has just run out of room doing exactly
            // that (cmux-app-a5v).
            val up = totalY > 0
            onStep(up)
            firstStep = false
            lastEmitAtMs = now
            totalY += if (totalY < 0) step else -step
        }
    }

    private fun currentStepPx(): Float = if (firstStep) firstStepPx else pageStepPx
}
