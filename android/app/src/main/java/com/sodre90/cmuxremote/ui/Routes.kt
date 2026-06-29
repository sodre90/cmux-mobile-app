package com.sodre90.cmuxremote.ui

/** Navigation route constants. */
object Routes {
    const val SETTINGS = "settings"
    const val SESSIONS = "sessions"
    const val INBOX = "inbox"
    const val TERMINAL = "terminal" // terminal/{id}

    fun terminal(surfaceId: String) = "$TERMINAL/$surfaceId"
}
