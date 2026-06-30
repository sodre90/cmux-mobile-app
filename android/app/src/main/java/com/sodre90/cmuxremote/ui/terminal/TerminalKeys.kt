package com.sodre90.cmuxremote.ui.terminal

// Control sequences built from code points so no raw control bytes live in source.
private val ESC = Char(27).toString()
private val CTRL_C = Char(3).toString()
private val CTRL_D = Char(4).toString()
private val CTRL_Z = Char(26).toString()

/** Label → byte sequence for the terminal key bar. */
val TerminalKeys: List<Pair<String, String>> = listOf(
    "Esc" to ESC,
    "Tab" to "\t",
    "^C" to CTRL_C,
    "^D" to CTRL_D,
    "^Z" to CTRL_Z,
    "↑" to ESC + "[A",
    "↓" to ESC + "[B",
    "←" to ESC + "[D",
    "→" to ESC + "[C",
    "PgUp" to ESC + "[5~",
    "PgDn" to ESC + "[6~",
    "Home" to ESC + "[H",
    "End" to ESC + "[F",
)
