package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Style

/** A fully resolved span, ready to map onto a Compose SpanStyle. */
data class ResolvedSpan(
    val fg: Color,
    val bg: Color,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
)

/** Style id 0 is what the decoder fills empty cells with, so its background is
 *  the grid's own "unstyled" color. */
private const val DefaultStyleId = 0

/** The background the grid reports for unstyled cells, or null if it states none. */
fun defaultBackgroundOf(styles: Map<Int, Style>): String? = styles[DefaultStyleId]?.backgroundString

/** Parses `#rrggbb` / `#aarrggbb` to a [Color]; returns null for other forms. */
fun parseColor(value: String?): Color? {
    val hex = value?.removePrefix("#") ?: return null
    return when (hex.length) {
        6 -> runCatching { Color("FF$hex".toLong(16)) }.getOrNull()
        8 -> runCatching { Color(hex.toLong(16)) }.getOrNull()
        else -> null
    }
}

/**
 * Resolves a cmux [Style] against the terminal [colors], applying inverse/faint.
 *
 * [defaultBackground] is the background the grid reports for unstyled cells (see
 * [defaultBackgroundOf]). cmux names that color explicitly rather than leaving it
 * absent, and it is the *terminal's* palette black, not this canvas -- painting it
 * literally tiled a black box behind every run on the #1E1E2E ground, and in
 * wrap-off mode the 1.25 line height left unpainted gaps between those boxes,
 * striping the whole pane. Treating it as "no background stated" hands those cells
 * the canvas instead, while a genuinely styled background still paints. Resolved
 * before inverse, so inverting a default cell swaps against the canvas as it should.
 */
fun resolveSpan(style: Style?, colors: TerminalColors, defaultBackground: String? = null): ResolvedSpan {
    var fg = parseColor(style?.foregroundString) ?: colors.foreground
    val stated = style?.backgroundString?.takeIf { defaultBackground == null || it != defaultBackground }
    var bg = parseColor(stated) ?: colors.background
    if (style?.inverse == true) {
        val swap = fg
        fg = bg
        bg = swap
    }
    if (style?.faint == true) {
        fg = fg.copy(alpha = fg.alpha * colors.faintAlpha)
    }
    return ResolvedSpan(
        fg = fg,
        bg = bg,
        bold = style?.bold == true,
        italic = style?.italic == true,
        underline = style?.underline == true,
        strikethrough = style?.strikethrough == true,
    )
}
