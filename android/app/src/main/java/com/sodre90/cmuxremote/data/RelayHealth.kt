package com.sodre90.cmuxremote.data

/**
 * Tracks whether RELAY has recently proven unreachable, so that knowledge is
 * learned once and shared by every caller instead of being rediscovered
 * independently -- [FallbackBridgeClient] (REST) and [SocketReconnector]
 * (the terminal/events sockets) both consult the same instance in
 * production (see [AppContainer]). A fresh [RelayHealth] always starts
 * assuming RELAY is healthy; tests construct their own instance to stay
 * isolated from each other and from production's shared one.
 */
class RelayHealth(private val penaltyMs: Long = DEFAULT_PENALTY_MS) {

    @Volatile private var downUntil: Long = 0L

    fun isDown(now: Long): Boolean = now < downUntil

    /** Records that the primary (RELAY) just proved unreachable at [now]. */
    fun markDown(now: Long) {
        downUntil = now + penaltyMs
    }

    companion object {
        // Once RELAY has proven unreachable, don't retry it on every single
        // call/reconnect for this long.
        const val DEFAULT_PENALTY_MS = 30_000L
    }
}
