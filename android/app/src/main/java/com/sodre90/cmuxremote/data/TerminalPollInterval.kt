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
 *
 * There are two of them because the trade is not the same on both sides of a
 * Wi-Fi join: on an unmetered link responsiveness is nearly free, on a metered
 * one every frame is billed. Defaulting the metered side slower is what makes
 * the saving arrive without the user having to find this setting at all.
 */
const val DEFAULT_POLL_MS_UNMETERED = 250
const val DEFAULT_POLL_MS_METERED = 1000

/** The default for a socket whose network cost is not known -- treated as the
 *  expensive case, since guessing wrong that way costs latency, not money. */
const val DEFAULT_TERMINAL_POLL_MS = DEFAULT_POLL_MS_METERED

/**
 * The intervals the settings screen offers. Kept a short list rather than a
 * free slider: the useful range is one order of magnitude, and every value in
 * between trades the same way.
 */
val TERMINAL_POLL_CHOICES = listOf(250, 500, 1000, 2000)

/** The interval to use when the user has not chosen one for this kind of link. */
internal fun defaultPollFor(metered: Boolean): Int =
    if (metered) DEFAULT_POLL_MS_METERED else DEFAULT_POLL_MS_UNMETERED

/**
 * The default to fall back on, given whatever the single-setting version of
 * this preference left behind ([legacy], 0 when it stored nothing).
 *
 * Someone who chose a value back when there was one setting for both links
 * meant it for the link they were paying for, so it seeds the metered side.
 * The unmetered side takes the ordinary default rather than inheriting a
 * saving the user never asked to make on Wi-Fi.
 */
internal fun inheritedPollDefault(metered: Boolean, legacy: Int): Int =
    if (metered && legacy != 0) legacy else defaultPollFor(metered)

/**
 * Snaps a stored interval onto the offered list.
 *
 * A value can fall off the list when this list changes between app versions, or
 * if a preference file is ever hand-edited. Picking the nearest neighbour keeps
 * the settings screen showing a selected option instead of none, without
 * silently resetting a preference the user did choose.
 */
internal fun nearestPollChoice(ms: Int): Int = TERMINAL_POLL_CHOICES.minBy { abs(it - ms) }
