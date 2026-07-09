package com.sodre90.cmuxremote.ui.terminal

// DEL (0x7f), the erase byte most shells expect (stty erase default on macOS
// and most Linux distros). Also sent directly by TerminalScreen when
// backspace is pressed on an already-empty capture field, since
// [diffToKeystrokes] has nothing to diff against in that case.
val DEL = Char(127).toString()

/**
 * The bytes to send so the remote line matches [new], given it currently
 * matches [old]: erase [old]'s suffix past the common prefix with [DEL], then
 * type [new]'s replacement suffix.
 *
 * This is a prefix-only diff, not true cursor-position tracking -- editing
 * the middle of the string backspaces further than a real terminal would
 * need to -- but it always converges on the same final line content, and the
 * common case (typing forward, an occasional trailing correction) produces
 * the minimal diff.
 */
fun diffToKeystrokes(old: String, new: String): String {
    val prefixLen = old.commonPrefixWith(new).length
    val erased = old.length - prefixLen
    return DEL.repeat(erased) + new.substring(prefixLen)
}

/** Renders control characters (DEL, ESC, etc.) as readable placeholders for logging. */
fun describeForLog(text: String): String = buildString {
    for (c in text) {
        when {
            c.code == 127 -> append("<DEL>")
            c.code < 0x20 -> append("<0x%02x>".format(c.code))
            else -> append(c)
        }
    }
}
