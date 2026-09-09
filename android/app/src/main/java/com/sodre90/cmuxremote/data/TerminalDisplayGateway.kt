package com.sodre90.cmuxremote.data

/**
 * The phone-local terminal font-size preference surface
 * [TerminalViewModel][com.sodre90.cmuxremote.ui.terminal.TerminalViewModel]
 * and
 * [ConnectionSettingsViewModel][com.sodre90.cmuxremote.ui.pairing.ConnectionSettingsViewModel]
 * consume -- see [TerminalDisplayStore], which [AppContainer] delegates to.
 */
interface TerminalDisplayGateway {
    /** The persisted pinch-zoom multiplier over the fit-to-width baseline -- 1x by default. */
    fun loadFontZoom(): Float
    fun saveFontZoom(zoom: Float)

    /**
     * Whether a pane that accepts wheel notches is scrolled with them rather
     * than with PgUp/PgDn.
     *
     * A preference and not a constant because neither answer is right for every
     * pane or link. Wheel notches move such a pane a fraction of a row, so it
     * tracks the finger -- but each input RPC is a bridge subprocess spawn
     * (~150ms), and a half-screen is roughly sixty notches, so a fast link
     * scrolls smoothly where a slow one crawls. PgUp/PgDn covers the same
     * distance in one keystroke, at the cost of jumping half a screen at a time
     * (cmux-app-vcx).
     *
     * Defaults OFF, and that is a safety default rather than a taste one. A
     * mouse report the pane does not parse does not vanish: its bare ESC lands
     * as an Escape keypress, and in a Claude pane two of those open the rewind
     * menu -- worse, one interrupts a running agent. Until a notch is confirmed
     * to be consumed as a notch on the pane in front of you, a swipe must not
     * be able to interrupt real work (cmux-app-qts).
     */
    fun loadWheelScrolling(): Boolean
    fun saveWheelScrolling(enabled: Boolean)
}
