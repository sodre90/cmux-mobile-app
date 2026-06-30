package com.sodre90.cmuxremote.ui.terminal

// Control sequences built from code points so no raw control bytes live in source.
private val ESC = Char(27).toString()
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

/** Ordered key bar: fixed control keys, the DECCKM-aware cursor keys, then paging. */
val TerminalKeys: List<TerminalKey> = listOf(
    StaticKey("Esc", ESC),
    StaticKey("Tab", "\t"),
    StaticKey("⏎", CR),
    StaticKey("^C", CTRL_C),
    StaticKey("^D", CTRL_D),
    StaticKey("^Z", CTRL_Z),
    CursorKey("↑", 'A'),
    CursorKey("↓", 'B'),
    CursorKey("←", 'D'),
    CursorKey("→", 'C'),
    CursorKey("Home", 'H'),
    CursorKey("End", 'F'),
    // Page keys use the `~` (keypad) form and are unaffected by DECCKM.
    StaticKey("PgUp", ESC + "[5~"),
    StaticKey("PgDn", ESC + "[6~"),
)
