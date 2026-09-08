package com.sodre90.cmuxremote.ui.terminal

/**
 * Codepoints that agent output actually contains but [TerminalFont] cannot
 * draw, each mapped to a glyph it can.
 *
 * The bundled JetBrains Mono Nerd Font covers 11,743 codepoints. A scan of
 * 37,226 characters of live output across all 13 surfaces of 6 workspaces
 * (2026-09-08) found exactly twelve it does not, in ~210 occurrences -- and
 * U+23F5, the one that prompted this, was only 6% of them. The bulk is
 * Claude Code's own chrome: its progress bar (U+25B0/U+25B1, 110 hits) and its
 * spinner and tool-result marker (U+23FA/U+23BF, 68).
 *
 * Substitution rather than font fallback, because this grid is drawn as one
 * Text per row with softWrap=false: column alignment is entirely a function of
 * glyph advance width. Every glyph in this font, .notdef included, advances 600
 * units -- so an undrawable codepoint already occupies its cell correctly and
 * shows as tofu. Handing the codepoint to a proportional system font (which is
 * what android.graphics.Typeface.CustomFallbackBuilder does, and it needs API
 * 29 against this module's minSdk 26) would draw the glyph but shift every
 * column after it on that row. A same-font substitute keeps the advance and so
 * keeps the grid.
 *
 * The cost is that selecting and copying one of these rows yields the
 * substitute, not what the agent emitted. That is worth it here: every entry
 * below is decorative status chrome, and none of it is text anyone copies for
 * its exact codepoint.
 */
internal val GlyphSubstitutions = mapOf(
    // Progress bar: filled and empty segments.
    '▰' to '█', // ▰ BLACK PARALLELOGRAM      -> █ FULL BLOCK
    '▱' to '░', // ▱ WHITE PARALLELOGRAM      -> ░ LIGHT SHADE
    // Spinner, and the marker Claude Code hangs tool results off.
    '⏺' to '●', // ⏺ BLACK CIRCLE FOR RECORD  -> ● BLACK CIRCLE
    '⎿' to '└', // ⎿ DENTISTRY SYMBOL ...     -> └ BOX DRAWINGS LIGHT UP AND RIGHT
    '↳' to '└', // ↳ DOWNWARDS ARROW WITH TIP RIGHTWARDS
    // Playback/media controls.
    '⏵' to '▶', // ⏵ BLACK MEDIUM RIGHT-POINTING TRIANGLE -> ▶
    '⏴' to '◀', // ⏴ BLACK MEDIUM LEFT-POINTING TRIANGLE  -> ◀
    '⏸' to '‖', // ⏸ DOUBLE VERTICAL BAR      -> ‖ DOUBLE VERTICAL LINE
    // Pass/fail marks: the heavy variants are missing, the light ones are not.
    '✔' to '✓', // ✔ HEAVY CHECK MARK         -> ✓ CHECK MARK
    '✘' to '✗', // ✘ HEAVY BALLOT X           -> ✗ BALLOT X
    // Asterisk-shaped attention marks.
    '✻' to '*', // ✻ TEARDROP-SPOKED ASTERISK
    '✽' to '*', // ✽ HEAVY TEARDROP-SPOKED ASTERISK
    '※' to '*', // ※ REFERENCE MARK
    // Arrows.
    '⇡' to '↑', // ⇡ UPWARDS DASHED ARROW     -> ↑ UPWARDS ARROW
)

/** [char] if [TerminalFont] can draw it, otherwise the closest glyph it can. */
internal fun drawableGlyph(char: Char): Char = GlyphSubstitutions[char] ?: char
