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
        //
        // Deliberately short. RELAY is the preferred transport (it works from
        // anywhere; DIRECT needs Tailscale up on the phone), and the failure
        // that trips this is usually transient -- the Mac agent's tunnel
        // redialing after an IPv6 "no route to host" blip takes well under a
        // second, but any window longer than the blip pins the phone to
        // DIRECT for the rest of it. The original 30s was long enough to be
        // noticeable as "why is it on Tailscale when the relay is fine?".
        // A dead relay still costs only one ~3s probe per window, not one per
        // call, which is the whole point of having a window at all.
        const val DEFAULT_PENALTY_MS = 5_000L
    }
}
