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

/** Parses `#rrggbb` / `#aarrggbb` to a [Color]; returns null for other forms. */
fun parseColor(value: String?): Color? {
    val hex = value?.removePrefix("#") ?: return null
    return when (hex.length) {
        6 -> runCatching { Color("FF$hex".toLong(16)) }.getOrNull()
        8 -> runCatching { Color(hex.toLong(16)) }.getOrNull()
        else -> null
    }
}

/** Resolves a cmux [Style] against the terminal [colors], applying inverse/faint. */
fun resolveSpan(style: Style?, colors: TerminalColors): ResolvedSpan {
    var fg = parseColor(style?.foregroundString) ?: colors.foreground
    var bg = parseColor(style?.backgroundString) ?: colors.background
    if (style?.inverse == true) {
        val swap = fg; fg = bg; bg = swap
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
