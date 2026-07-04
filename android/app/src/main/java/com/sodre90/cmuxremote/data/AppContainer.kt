package com.sodre90.cmuxremote.data

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.E2eInterceptor
import com.sodre90.cmuxremote.data.e2e.Identity
import com.sodre90.cmuxremote.data.e2e.Session
import com.sodre90.cmuxremote.data.pairing.PairingClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container held by [com.sodre90.cmuxremote.CmuxApp]. Builds one
 * [OkHttpClient] per [ConnectionSlot] (bearer token + opt-in e2e encryption) and
 * hands out slot-scoped bridge clients/sockets, plus [activeBridge] for
 * callers that just want "whichever works right now."
 */
class AppContainer(appContext: Context) {

    val settings = Settings(appContext)
    val identity = Identity(appContext)
    val cipher = Cipher(LazySodiumAndroid(SodiumAndroid()))
    val workspaceOrderStore = WorkspaceOrderStore(appContext)

    private val sessions: Map<ConnectionSlot, Session> =
        ConnectionSlot.entries.associateWith { Session(appContext, it) }

    init {
        // One-time migration off the pre-dual-pairing single-slot format:
        // Settings decides which slot the legacy {base_url, device_token}
        // belongs to (it can see the URL); that same slot's Session then
        // absorbs the matching legacy e2e record (it can't see the URL, so
        // it can't decide this on its own).
        val migratedSlot = settings.migrateLegacyIfNeeded()
        sessions.forEach { (slot, session) -> session.absorbLegacyIfTarget(slot == migratedSlot) }
    }

    private val clients = mutableMapOf<ConnectionSlot, Pair<String, OkHttpClient>>()

    @Synchronized
    private fun httpClient(slot: ConnectionSlot, cfg: BridgeConfig): OkHttpClient {
        val session = sessions.getValue(slot)
        val key = "${cfg.baseUrl}|${cfg.deviceToken}|${session.isPaired()}"
        clients[slot]?.let { (cachedKey, cachedClient) -> if (cachedKey == key) return cachedClient }

        var builder = Mtls.client(cfg).newBuilder()
        if (slot == ConnectionSlot.RELAY) {
            // Only the connect phase needs a short leash: an unreachable
            // relay fails the TCP handshake almost immediately, so 3s is
            // generous for a real failure while still keeping the UI from
            // stalling on a dead home server. A slow-but-reachable response
            // (cmux itself being slow) is a different problem and must not
            // trip a spurious failover, so read/write timeouts stay at
            // OkHttp's defaults. Direct has no second fallback to race
            // against, so it keeps the normal (longer) connect timeout too.
            builder = builder.connectTimeout(3, TimeUnit.SECONDS)
        }
        var built = builder.build()
        if (session.isPaired()) {
            built = built.newBuilder().addInterceptor(E2eInterceptor(session, cipher)).build()
        }
        clients[slot] = key to built
        return built
    }

    fun bridgeClient(slot: ConnectionSlot): BridgeClient? =
        settings.bridgeConfig(slot)?.let { BridgeClient(httpClient(slot, it), it.baseUrl) }

    fun eventsSocket(slot: ConnectionSlot): EventsSocket? =
        settings.bridgeConfig(slot)?.let { EventsSocket(httpClient(slot, it), it.baseUrl, sessions.getValue(slot), cipher) }

    fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket? =
        settings.bridgeConfig(slot)?.let { TerminalSocket(httpClient(slot, it), it.baseUrl, surfaceId, sessions.getValue(slot), cipher) }

    /** Unauthenticated -- POST /devices/pair takes no bearer token (see
     *  bridge/internal/relay/relay.go's handleDevicePair). */
    fun pairingClient(slot: ConnectionSlot): PairingClient =
        PairingClient(OkHttpClient(), identity, sessions.getValue(slot), settings, slot)

    /** The fallback-aware entry point most read/write call sites should use
     *  instead of bridgeClient(slot) directly. Null only when neither slot
     *  is paired yet (matches the old single-slot bridgeClient()'s null
     *  contract, so existing "Bridge not configured" call sites need no
     *  shape change). */
    fun activeBridge(): FallbackBridgeClient? {
        if (!anyBridgeConfigured()) return null
        return FallbackBridgeClient(
            primary = { bridgeClient(ConnectionSlot.RELAY) },
            fallback = { bridgeClient(ConnectionSlot.DIRECT) },
        )
    }

    fun anyBridgeConfigured(): Boolean = ConnectionSlot.entries.any { settings.bridgeConfig(it) != null }
}
