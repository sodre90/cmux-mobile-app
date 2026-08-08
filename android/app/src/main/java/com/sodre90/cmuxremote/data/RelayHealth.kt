package com.sodre90.cmuxremote.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _recoveries = MutableStateFlow(0L)

    /**
     * Increments each time RELAY goes from known-down back to proven-working.
     *
     * Long-lived socket subscriptions pick their slot once per connect and
     * only re-pick when the socket drops, so a healthy DIRECT socket opened
     * during a penalty window would otherwise stay on DIRECT forever (see
     * [SocketReconnector]). This is the signal to come back.
     *
     * It advances on *proven* recovery -- a real call the relay actually
     * served -- not on the penalty window merely lapsing. Expiry alone means
     * only "worth retrying"; acting on it would drop a working DIRECT socket
     * every [DEFAULT_PENALTY_MS] for as long as the relay stayed down.
     *
     * A counter rather than an event stream so consumers can read it *before*
     * committing to DIRECT and then wait for it to differ. An edge-triggered
     * signal would be lost in the gap between picking the slot and
     * subscribing, parking that socket on DIRECT until the next recovery --
     * which, since [markUp] only reports the down-to-up edge, may never come.
     */
    val recoveries: StateFlow<Long> = _recoveries.asStateFlow()

    fun isDown(now: Long): Boolean = now < downUntil

    /** Records that the primary (RELAY) just proved unreachable at [now]. */
    @Synchronized
    fun markDown(now: Long) {
        downUntil = now + penaltyMs
    }

    /**
     * Records that RELAY just served a call successfully: clears any penalty
     * and, if one was outstanding, advances [recoveries]. Idempotent -- a
     * steady stream of successful relay calls advances nothing, because only
     * the down-to-up edge is interesting.
     */
    @Synchronized
    fun markUp() {
        if (downUntil == 0L) return
        downUntil = 0L
        _recoveries.value += 1
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
