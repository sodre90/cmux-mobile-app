package com.sodre90.cmuxremote.data.e2e

private const val WINDOW_SIZE = 64

/**
 * The replay window refused [counter] before any AEAD work happened, so
 * nothing has been learned about whether the frame was authentic.
 *
 * A subtype of [DecryptFailedException] so existing handlers keep dropping
 * the frame exactly as before, but carrying its own message because the two
 * causes call for opposite responses: an AEAD failure means the bytes are
 * wrong, while a rejection here means this device's own counter state is
 * stale. Conflating them is what made cmux-app-a3g a long hunt instead of a
 * one-line diagnosis. [highestSeen] is included because the giveaway is the
 * gap -- counters near zero refused by a window sitting far ahead means the
 * window outlived a re-pairing.
 *
 * Counters carry no key material, so this is safe to log.
 */
class ReplayRejectedException(counter: Long, highestSeen: Long) : DecryptFailedException(
    "replay_rejected: counter=$counter highest_seen=$highestSeen",
)

/**
 * RFC 6479-style anti-replay bitmap: tolerates out-of-order delivery within
 * the last [WINDOW_SIZE] counters while still rejecting exact replays and
 * anything older than the window. Immutable -- [commit] returns a new
 * instance so callers can't advance state before an AEAD tag has verified
 * (see Global Constraints: canAccept is read-only, commit is separate).
 */
class ReplayWindow(val highestSeen: Long = -1L, val windowBits: Long = 0L) {

    /** Read-only: does NOT record [n] as seen. Call [commit] separately, and
     *  only after the corresponding ciphertext has verified. Protocol
     *  counters are always non-negative -- reject negative n outright rather
     *  than let it collide with the `highestSeen < 0` "no history" sentinel. */
    fun canAccept(n: Long): Boolean {
        if (n < 0) return false
        if (highestSeen < 0) return true
        if (n > highestSeen) return true
        val age = highestSeen - n
        if (age >= WINDOW_SIZE) return false
        val bit = 1L shl age.toInt()
        return (windowBits and bit) == 0L
    }

    /** Records [n] as seen, sliding the window forward if [n] is a new high.
     *  [n] must be non-negative and must already have passed [canAccept] --
     *  callers never commit a counter they haven't just verified. */
    fun commit(n: Long): ReplayWindow {
        require(n >= 0) { "counter must be non-negative" }
        if (highestSeen < 0) {
            return ReplayWindow(n, 1L)
        }
        if (n > highestSeen) {
            val shift = n - highestSeen
            val slid = if (shift >= WINDOW_SIZE) 0L else windowBits shl shift.toInt()
            return ReplayWindow(n, slid or 1L)
        }
        val age = highestSeen - n
        if (age >= WINDOW_SIZE) return this // already unrepresentable; no-op
        return ReplayWindow(highestSeen, windowBits or (1L shl age.toInt()))
    }
}
