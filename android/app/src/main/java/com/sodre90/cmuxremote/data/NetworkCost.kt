package com.sodre90.cmuxremote.data

import android.content.Context
import android.net.ConnectivityManager

/**
 * Whether the link this phone is currently using bills by the byte.
 *
 * Metered rather than "is it Wi-Fi": a phone tethered to another phone, or on a
 * hotspot the user marked as metered, is on Wi-Fi by transport and on someone's
 * data plan by cost -- and cost is the thing the terminal poll interval is
 * trading against. Android already tracks that answer, including the user's own
 * per-network override, so this asks it rather than inferring from transport.
 *
 * Read when a terminal socket is opened. That is enough to track a change:
 * moving between Wi-Fi and mobile drops the socket, and the reconnect asks
 * again.
 */
class NetworkCost(private val appContext: Context) {

    /**
     * Defaults to metered when the answer is unavailable -- no active network,
     * or the service missing on some odd build. Guessing "expensive" costs a
     * slower refresh; guessing "free" spends the user's data without being
     * asked, and only one of those is recoverable by the user noticing.
     */
    fun isMetered(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(true)
    }
}
