package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.input.key.Key

// Control sequences built from code points so no raw control bytes live in source.
// ESC is internal (not private) so TerminalScreen's physical-keyboard handling
// can send it directly, the same sequence the on-screen "Esc" key uses.
internal val ESC = Char(27).toString()
private val CTRL_C = Char(3).toString()
private val CTRL_D = Char(4).toString()
private val CTRL_Z = Char(26).toString()
private const val CR = "\r"

/**
 * A key in the terminal key bar. Most keys emit a fixed byte string; the cursor
 * keys (arrows, Home, End) instead resolve their escape against DECCKM — the
 * terminal's "application cursor keys" mode — which is only known at press time.
 */
sealed interface TerminalKey {
    val label: String
    fun sequence(applicationCursorKeys: Boolean): String
}

/** A key whose byte sequence never changes. */
data class StaticKey(override val label: String, private val bytes: String) : TerminalKey {
    override fun sequence(applicationCursorKeys: Boolean): String = bytes
}

/**
 * A cursor key. When DECCKM is set the terminal expects SS3 (`ESC O x`); when
 * reset it expects CSI (`ESC [ x`). [finalChar] is the trailing letter (A/B/C/D
 * for the arrows, H/F for Home/End) shared by both forms.
 */
data class CursorKey(override val label: String, private val finalChar: Char) : TerminalKey {
    override fun sequence(applicationCursorKeys: Boolean): String =
        ESC + (if (applicationCursorKeys) "O" else "[") + finalChar
}

// The directional arrows, surfaced as an always-visible D-pad (see ArrowPad in
// TerminalScreen) rather than buried in the scrollable bar. DECCKM-aware like any
// CursorKey. The final chars D/C (not C/D) match the ANSI codes for left/right.
val ArrowUp = CursorKey("↑", 'A')
val ArrowDown = CursorKey("↓", 'B')
val ArrowLeft = CursorKey("←", 'D')
val ArrowRight = CursorKey("→", 'C')

/** The horizontally-scrolling key bar. The arrows live in the D-pad instead. */
val TerminalKeys: List<TerminalKey> = listOf(
    StaticKey("Esc", ESC),
    StaticKey("Tab", "\t"),
    StaticKey("⏎", CR),
    StaticKey("^C", CTRL_C),
    StaticKey("^D", CTRL_D),
    StaticKey("^Z", CTRL_Z),
    CursorKey("Home", 'H'),
    CursorKey("End", 'F'),
    // Page keys use the `~` (keypad) form and are unaffected by DECCKM.
    StaticKey("PgUp", ESC + "[5~"),
    StaticKey("PgDn", ESC + "[6~"),
)

/**
 * Resolves a physical keyboard press (e.g. from a Bluetooth keyboard) to the
 * terminal byte sequence it should send, or null if this key isn't one of the
 * ones intercepted directly -- regular character keys fall through to the
 * text field's own IME-driven capture instead.
 */
internal fun physicalKeySequence(key: Key, applicationCursorKeys: Boolean): String? = when (key) {
    Key.DirectionUp -> ArrowUp.sequence(applicationCursorKeys)
    Key.DirectionDown -> ArrowDown.sequence(applicationCursorKeys)
    Key.DirectionLeft -> ArrowLeft.sequence(applicationCursorKeys)
    Key.DirectionRight -> ArrowRight.sequence(applicationCursorKeys)
    Key.Escape -> ESC
    Key.Tab -> "\t"
    Key.Enter, Key.NumPadEnter -> CR
    else -> null
}
