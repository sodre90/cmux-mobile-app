package com.sodre90.cmuxremote.data

import java.net.URI

/** Which backend a request or pairing targets: the relay (always reachable
 *  from anywhere, requires the home server) or the direct Tailscale
 *  listener (requires Tailscale to be up on the phone, no home server
 *  involved). Relay is always primary, direct is always the fallback --
 *  see the 2026-07-04 dual-pairing design's Decisions section. */
enum class ConnectionSlot {
    RELAY,
    DIRECT,
    ;

    fun other(): ConnectionSlot = if (this == RELAY) DIRECT else RELAY
}

/** Classifies a pre-dual-pairing single stored base URL into the slot it
 *  most likely belongs to, for one-time migration off the old unprefixed
 *  Settings/CryptoSession keys (see Settings.migrateLegacyIfNeeded). Tailscale's
 *  MagicDNS names always end in ".ts.net"; anything else is assumed to be a
 *  relay URL -- the only other shape this app has ever stored. */
fun inferLegacySlot(baseUrl: String): ConnectionSlot {
    val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return ConnectionSlot.RELAY
    return if (host.endsWith(".ts.net")) ConnectionSlot.DIRECT else ConnectionSlot.RELAY
}
