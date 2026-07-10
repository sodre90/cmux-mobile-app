package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.Settings
import com.sodre90.cmuxremote.data.e2e.CryptoSession
import com.sodre90.cmuxremote.data.e2e.Identity
import com.sodre90.cmuxremote.data.e2e.deriveSharedSecret
import com.sodre90.cmuxremote.data.e2e.pairingFingerprint
import com.sodre90.cmuxremote.model.BridgeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/** The pairing code was not found, already redeemed, or expired -- the relay's
 *  RedeemPairingCode doesn't distinguish these (see the Go spec's error
 *  handling section), so neither does this. */
class PairingCodeInvalidException : Exception("pairing_code_invalid")

@Serializable
private data class DevicePairRequest(
    val code: String,
    val name: String,
    @SerialName("device_pubkey") val devicePubkey: String,
)

@Serializable
private data class DevicePairResponse(
    val token: String = "",
    @SerialName("tenant_id") val tenantId: String = "",
)

@Serializable
private data class PairingCodeInfoResponse(
    @SerialName("agent_pubkey") val agentPubkey: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("tenant_id") val tenantId: String = "",
)

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * The prepare/commit/resolveManualCode surface [PairingViewModel]
 * [com.sodre90.cmuxremote.ui.pairing.PairingViewModel] consumes -- narrow
 * enough to fake in a plain JVM test, unlike [PairingClient] itself (whose
 * constructor needs a real Android-Keystore-backed Identity/CryptoSession/
 * Settings). [PairingClient] is the sole real implementation.
 */
interface PairingSession {
    /** Computes the SAS fingerprint for [qr]'s agent key against this
     *  phone's own key -- no network call. */
    fun prepare(qr: PairingQr): String

    suspend fun commit(qr: PairingQr)

    /** Resolves a manually-entered server URL + pairing code (the CLI's
     *  fallback for when scanning isn't possible) into the same [PairingQr]
     *  shape a scanned QR produces. */
    suspend fun resolveManualCode(serverUrl: String, code: String): PairingQr
}

/**
 * Completes self-service pairing against POST /devices/pair: submits the
 * phone's e2e public key alongside the scanned code, and on success derives
 * the shared secret via ECDH and persists everything -- bearer token + base
 * URL into Settings, e2e session into CryptoSession. Mirrors
 * bridge/cmd/cmux-bridge/pair.go's agent-side half of the same handshake.
 *
 * [prepare]/[commit] are split (rather than one atomic call) so the UI can
 * show a fingerprint confirmation screen between them -- see
 * docs/superpowers/specs/2026-07-10-pairing-mitm-fingerprint-design.md.
 * [prepare] makes no network call, so a rejected fingerprint costs nothing
 * server-side; only [commit] actually redeems the pairing code.
 */
class PairingClient(
    private val http: OkHttpClient,
    private val identity: Identity,
    private val session: CryptoSession,
    private val settings: Settings,
    private val slot: ConnectionSlot,
) : PairingSession {
    override fun prepare(qr: PairingQr): String = prepareInternal(qr, identity.publicKey)

    override suspend fun commit(qr: PairingQr) = commitInternal(
        http = http,
        qr = qr,
        phonePrivateKey = identity.privateKey,
        phonePublicKey = identity.publicKey,
        onSetPairing = session::setPairing,
        onSetBaseUrl = { settings.setBaseUrl(slot, it) },
        onSetToken = { settings.setDeviceToken(slot, it) },
    )

    override suspend fun resolveManualCode(serverUrl: String, code: String): PairingQr =
        resolvePairingCode(http, serverUrl, code)
}

/** GET /devices/pair-info/{code}: the manual-entry counterpart to scanning a
 *  QR. The QR carries {pair_url, code, agent_pubkey, expires_at, tenant_id}
 *  directly; a phone that can't scan (no camera, or pairing remotely) has
 *  only the server URL and the code the CLI also prints, so it resolves the
 *  rest from the relay instead. Everything downstream of this call
 *  (prepareInternal/commitInternal) is identical to the QR path. */
internal suspend fun resolvePairingCode(http: OkHttpClient, serverUrl: String, code: String): PairingQr =
    withContext(Dispatchers.IO) {
        val base = serverUrl.trimEnd('/')
        val request = Request.Builder().url("$base/devices/pair-info/$code").get().build()
        http.newCall(request).execute().use { response ->
            if (response.code == 410) throw PairingCodeInvalidException()
            if (!response.isSuccessful) throw IOException("pair-info failed: HTTP ${response.code}")
            val body = BridgeJson.decodeFromString(
                PairingCodeInfoResponse.serializer(),
                response.body?.string().orEmpty(),
            )
            PairingQr(
                pairUrl = "$base/devices/pair",
                code = code,
                agentPubkey = body.agentPubkey,
                expiresAt = body.expiresAt,
                tenantId = body.tenantId,
            )
        }
    }

/** Local-only: computes the SAS fingerprint for [qr]'s agent key against
 *  [phonePublicKey] -- no network call, nothing persisted. Validates
 *  [PairingQr.hasSafePairUrl] up front too, so a malformed manual-entry URL
 *  fails before the user ever sees a confirmation screen, not just later at
 *  [commitInternal]. Free function so PairingClientTest can exercise it
 *  without constructing a real Identity. */
internal fun prepareInternal(qr: PairingQr, phonePublicKey: ByteArray): String {
    if (!qr.hasSafePairUrl()) throw IOException("pairing failed: server address must be https://")
    val agentPublicKey = Base64.getDecoder().decode(qr.agentPubkey)
    return pairingFingerprint(phonePublicKey, agentPublicKey)
}

/** Free function (not a CryptoSession/Settings method) so PairingClientTest can
 *  exercise the real handshake logic via plain callbacks -- see Task 11's
 *  Step 1 note on why Identity/CryptoSession/Settings can't be constructed in a
 *  local JVM unit test. */
internal suspend fun commitInternal(
    http: OkHttpClient,
    qr: PairingQr,
    phonePrivateKey: ByteArray,
    phonePublicKey: ByteArray,
    onSetPairing: (peerPublicKey: ByteArray, sharedSecret: ByteArray) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetToken: (String) -> Unit,
): Unit = withContext(Dispatchers.IO) {
    // The manual-entry path (resolvePairingCode) builds its own PairingQr
    // from a locally-constructed URL and never goes through
    // parsePairingQr's validation, so this is the one choke point both the
    // QR-scan and manual-entry flows share before the pairing POST.
    if (!qr.hasSafePairUrl()) throw IOException("pairing failed: server address must be https://")
    val payload = DevicePairRequest(
        code = qr.code,
        name = "phone",
        devicePubkey = Base64.getEncoder().encodeToString(phonePublicKey),
    )
    val request = Request.Builder()
        .url(qr.pairUrl)
        .post(BridgeJson.encodeToString(DevicePairRequest.serializer(), payload).toRequestBody(JSON_MEDIA))
        .build()

    http.newCall(request).execute().use { response ->
        if (response.code == 410) throw PairingCodeInvalidException()
        if (!response.isSuccessful) throw IOException("pairing failed: HTTP ${response.code}")
        val body = BridgeJson.decodeFromString(
            DevicePairResponse.serializer(),
            response.body?.string().orEmpty(),
        )

        val agentPublicKey = Base64.getDecoder().decode(qr.agentPubkey)
        val sharedSecret = deriveSharedSecret(phonePrivateKey, agentPublicKey)

        onSetPairing(agentPublicKey, sharedSecret)
        onSetBaseUrl(baseUrlFromPairUrl(qr.pairUrl))
        onSetToken(body.token)
    }
}

/** "https://host/devices/pair" -> "https://host" -- the same main vhost the
 *  bridge's other endpoints live on (bridge/cmd/cmux-bridge/pair.go derives
 *  the QR's pair_url this same way, in reverse). */
private fun baseUrlFromPairUrl(pairUrl: String): String = pairUrl.removeSuffix("/devices/pair")
