package com.sodre90.cmuxremote.data

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.CryptoSession
import com.sodre90.cmuxremote.data.e2e.E2eInterceptor
import com.sodre90.cmuxremote.data.pairing.PairingClient
import com.sodre90.cmuxremote.push.showCredentialRejectedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container held by [com.sodre90.cmuxremote.CmuxApp]. Builds one
 * [OkHttpClient] per [ConnectionSlot] (bearer token + opt-in e2e encryption) and
 * hands out slot-scoped bridge clients/sockets, plus [activeBridge] for
 * callers that just want "whichever works right now."
 *
 * Implements the narrow [BridgeGateway]/[WorkspaceOrderGateway]/[PairingGateway]
 * interfaces ViewModels depend on instead of this concrete class, so they stay
 * constructible in a plain JVM test -- this class's init does
 * EncryptedSharedPreferences + Keystore I/O and can't be.
 */
class AppContainer(
    private val appContext: Context,
) : BridgeGateway, WorkspaceOrderGateway, PairingGateway, TerminalDisplayGateway {

    val settings = Settings(appContext)
    val cipher = Cipher(LazySodiumAndroid(SodiumAndroid()))
    val workspaceOrderStore = WorkspaceOrderStore(appContext)
    val terminalDisplayStore = TerminalDisplayStore(appContext)

    private val sessions: Map<ConnectionSlot, CryptoSession> =
        ConnectionSlot.entries.associateWith { CryptoSession(appContext, it) }

    init {
        // One-time migration off the pre-dual-pairing single-slot format:
        // Settings decides which slot the legacy {base_url, device_token}
        // belongs to (it can see the URL); that same slot's CryptoSession then
        // absorbs the matching legacy e2e record (it can't see the URL, so
        // it can't decide this on its own).
        val migratedSlot = settings.migrateLegacyIfNeeded()
        sessions.forEach { (slot, session) -> session.absorbLegacyIfTarget(slot == migratedSlot) }
    }

    // One PairingClient per slot, not one per call: it holds the keypair
    // minted by prepare() until the matching commit() submits it, and a fresh
    // instance per call would drop that between the two halves of the
    // fingerprint-confirmation flow.
    private val pairingClients = mutableMapOf<ConnectionSlot, PairingClient>()

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
            built = built.newBuilder()
                .addInterceptor(E2eInterceptor(session, cipher, slot == ConnectionSlot.RELAY))
                .build()
        }
        clients[slot] = key to built
        return built
    }

    /** Clears [slot]'s stored bridge config and e2e session, and evicts its
     *  cached [OkHttpClient] -- used by "Forget" in ConnectionSettingsScreen.
     *  The other slot is untouched. Re-pairing this slot afterwards behaves
     *  exactly like pairing it for the first time. Also asks the server to
     *  retire this device's token, best-effort; see
     *  [releaseCredentialOnServer]. */
    @Synchronized
    fun forgetSlot(slot: ConnectionSlot) {
        releaseCredentialOnServer(slot)
        settings.clearSlot(slot)
        sessions.getValue(slot).clear()
        clients.remove(slot)
        // Drops any keypair a half-finished pairing left pending, so the next
        // attempt cannot commit a key whose fingerprint predates the Forget.
        pairingClients.remove(slot)
        // Storage alone doesn't reach a socket that is already connected on
        // the credentials just cleared -- see [SlotCredentials].
        sharedSlotCredentials.invalidate(slot)
        // Forgotten by intent is not the same fact as rejected by a server,
        // and the Connections screen must not read the two the same way.
        sharedSlotCredentialHealth.reset(slot)
    }

    // Fire-and-forget on purpose, and the local clear above never waits on
    // it: Forget has to work with the server unreachable, and a phone stuck
    // paired to a bridge it can't dial would be the worse failure. What the
    // server misses here an operator can still revoke by hand, and the agent
    // reaps the orphaned shared secret on its own timer either way. The
    // client is captured before settings.clearSlot because it holds this
    // slot's bearer token by value (see Mtls.BearerInterceptor).
    private fun releaseCredentialOnServer(slot: ConnectionSlot) {
        settings.bridgeConfig(slot)?.let { retireCredential(slot, it) }
    }

    // Takes the config rather than reading it, because the re-pair path calls
    // this once the NEW credentials are already stored -- it has to name the
    // ones being replaced. The client is built here, not inside the
    // coroutine, for the same reason.
    private fun retireCredential(slot: ConnectionSlot, cfg: BridgeConfig) {
        val client = BridgeClient(httpClient(slot, cfg), cfg.baseUrl)
        selfRevokeScope.launch { runCatching { client.selfRevoke() } }
    }

    private val selfRevokeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bridgeClient(slot: ConnectionSlot): BridgeClient? =
        settings.bridgeConfig(slot)?.let { BridgeClient(httpClient(slot, it), it.baseUrl) }

    override fun eventsSocket(slot: ConnectionSlot): EventsSocket? =
        settings.bridgeConfig(
            slot
        )?.let { EventsSocket(httpClient(slot, it), it.baseUrl, sessions.getValue(slot), cipher) }

    override fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket? =
        settings.bridgeConfig(
            slot
        )?.let { TerminalSocket(httpClient(slot, it), it.baseUrl, surfaceId, sessions.getValue(slot), cipher) }

    /** Unauthenticated -- POST /devices/pair takes no bearer token (see
     *  bridge/internal/relay/relay.go's handleDevicePair). */
    @Synchronized
    override fun pairingClient(slot: ConnectionSlot): PairingClient =
        pairingClients.getOrPut(slot) {
            PairingClient(
                OkHttpClient(),
                sessions.getValue(slot),
                settings,
                slot,
                sharedSlotCredentials,
                sharedSlotCredentialHealth,
                retirePreviousCredential = { cfg -> retireCredential(slot, cfg) },
            )
        }

    override fun loadOrder(): List<String> = workspaceOrderStore.load()

    override fun saveOrder(order: List<String>) = workspaceOrderStore.save(order)

    override fun loadSortByAttention(): Boolean = workspaceOrderStore.loadSortByAttention()

    override fun saveSortByAttention(sortByAttention: Boolean) =
        workspaceOrderStore.saveSortByAttention(sortByAttention)

    override fun loadFontZoom(): Float = terminalDisplayStore.loadFontZoom()

    override fun saveFontZoom(zoom: Float) = terminalDisplayStore.saveFontZoom(zoom)

    // Shared with fallbackBridge below and handed out via relayHealth() so
    // every reconnecting socket subscription and the REST fallback path
    // learn "relay is down" once, from the same instance.
    private val sharedRelayHealth = RelayHealth()

    override fun relayHealth(): RelayHealth = sharedRelayHealth

    // Shared for the same reason as sharedRelayHealth: the REST path and every
    // socket subscription are all reporting on the same two transports.
    private val sharedConnectionMonitor = ConnectionMonitor()

    override fun connectionMonitor(): ConnectionMonitor = sharedConnectionMonitor

    // Shared for the same reason again: forgetting or re-pairing a slot has
    // to reach every socket subscription running on it, wherever it was
    // started from.
    private val sharedSlotCredentials = SlotCredentials()

    override fun slotCredentials(): SlotCredentials = sharedSlotCredentials

    // Shared for the same reason again, and written from exactly one place:
    // the registerDevice fan-out below, which is the only call that asks both
    // slots about this device on every launch. Settings supplies the one bit
    // that must survive process death -- see RejectionReportLog.
    private val sharedSlotCredentialHealth = SlotCredentialHealth(
        reportLog = settings,
        onNewRejection = { slot -> showCredentialRejectedNotification(appContext, slot) },
    )

    override fun slotCredentialHealth(): SlotCredentialHealth = sharedSlotCredentialHealth

    private val fallbackBridge = FallbackBridgeClient(
        primary = { bridgeClient(ConnectionSlot.RELAY) },
        fallback = { bridgeClient(ConnectionSlot.DIRECT) },
        relayHealth = sharedRelayHealth,
        monitor = sharedConnectionMonitor,
        onRegistrationOutcome = sharedSlotCredentialHealth::record,
    )

    /** The fallback-aware entry point most read/write call sites should use
     *  instead of bridgeClient(slot) directly. Null only when neither slot
     *  is paired yet (matches the old single-slot bridgeClient()'s null
     *  contract, so existing "Bridge not configured" call sites need no
     *  shape change). A single shared instance is kept (not rebuilt per
     *  call) so FallbackBridgeClient's 30s "primary is down" penalty window
     *  actually persists across repeated calls from ViewModels. */
    override fun activeBridge(): FallbackBridgeClient? = if (anyBridgeConfigured()) fallbackBridge else null

    override fun anyBridgeConfigured(): Boolean = ConnectionSlot.entries.any { settings.bridgeConfig(it) != null }

    /** The paired session for [slot] -- used by CmuxMessagingService to decrypt an
     *  incoming push, which arrives tagged with the slot that sent it rather than
     *  going through the usual bridgeClient()/eventsSocket() request path. */
    fun session(slot: ConnectionSlot): CryptoSession = sessions.getValue(slot)
}
