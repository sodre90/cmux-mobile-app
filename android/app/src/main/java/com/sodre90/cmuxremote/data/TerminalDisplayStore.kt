package com.sodre90.cmuxremote.data

import android.content.Context

/**
 * Persists the phone-local terminal display preference -- the pinch-zoom
 * multiplier over the fit-to-width baseline (see
 * [com.sodre90.cmuxremote.ui.terminal.TerminalScreen]'s userZoom). Not
 * synced to the bridge, not visible from any other device, and shared by
 * every terminal surface: it's a "how big do you like your text" setting,
 * not a per-session one.
 */
class TerminalDisplayStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadFontZoom(): Float = prefs.getFloat(KEY_FONT_ZOOM, DEFAULT_FONT_ZOOM)

    fun saveFontZoom(zoom: Float) {
        prefs.edit().putFloat(KEY_FONT_ZOOM, zoom).apply()
    }

    private companion object {
        const val PREFS_NAME = "cmux_terminal_display_prefs"
        const val KEY_FONT_ZOOM = "font_zoom"
        const val DEFAULT_FONT_ZOOM = 1f
    }
}
