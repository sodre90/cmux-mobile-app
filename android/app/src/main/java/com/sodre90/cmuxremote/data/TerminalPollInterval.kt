package com.sodre90.cmuxremote.data

import kotlin.math.abs

/**
 * How often the bridge re-reads an open pane looking for new output, in
 * milliseconds. Sent as `?poll_ms=` on the terminal socket and clamped there --
 * see `terminalPollInterval` in bridge/internal/server/terminal.go, and keep
 * [TERMINAL_POLL_CHOICES] inside the bounds that function enforces.
 *
 * This is a data-for-latency dial and nothing else. It does not slow typing:
 * input nudges an immediate replay on the bridge, so the interval governs only
 * how quickly output the user did not type reaches the phone. Each tick that
 * finds a change costs a frame, so halving the rate halves the cost of watching
 * a busy pane.
 */
const val DEFAULT_TERMINAL_POLL_MS = 250

/**
 * The intervals the settings screen offers. Kept a short list rather than a
 * free slider: the useful range is one order of magnitude, and every value in
 * between trades the same way.
 */
val TERMINAL_POLL_CHOICES = listOf(250, 500, 1000, 2000)

/**
 * Snaps a stored interval onto the offered list.
 *
 * A value can fall off the list when this list changes between app versions, or
 * if a preference file is ever hand-edited. Picking the nearest neighbour keeps
 * the settings screen showing a selected option instead of none, without
 * silently resetting a preference the user did choose.
 */
internal fun nearestPollChoice(ms: Int): Int = TERMINAL_POLL_CHOICES.minBy { abs(it - ms) }
