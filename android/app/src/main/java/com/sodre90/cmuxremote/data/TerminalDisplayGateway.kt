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
}
