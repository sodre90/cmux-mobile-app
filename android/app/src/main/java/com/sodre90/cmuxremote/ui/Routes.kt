package com.sodre90.cmuxremote.ui

import com.sodre90.cmuxremote.data.ConnectionSlot

/** Navigation route constants. */
object Routes {
    const val SETTINGS = "settings"
    const val SESSIONS = "sessions"
    const val INBOX = "inbox"
    const val TERMINAL = "terminal" // terminal/{id}
    const val PAIR = "pair" // pair/{slot}

    fun terminal(surfaceId: String) = "$TERMINAL/$surfaceId"
    fun pair(slot: ConnectionSlot) = "$PAIR/${slot.name.lowercase()}"
}
