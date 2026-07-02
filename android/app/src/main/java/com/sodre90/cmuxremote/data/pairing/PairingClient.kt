package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.data.Settings
import com.sodre90.cmuxremote.data.e2e.Identity
import com.sodre90.cmuxremote.data.e2e.Session
import java.util.Base64
import com.sodre90.cmuxremote.data.e2e.deriveSharedSecret
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

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Completes self-service pairing against POST /devices/pair: submits the
 * phone's e2e public key alongside the scanned code, and on success derives
 * the shared secret via ECDH and persists everything -- bearer token + base
 * URL into Settings, e2e session into Session. Mirrors
 * bridge/cmd/cmux-bridge/pair.go's agent-side half of the same handshake.
 */
class PairingClient(
    private val http: OkHttpClient,
    private val identity: Identity,
    private val session: Session,
    private val settings: Settings,
) {
    suspend fun pair(qr: PairingQr) = pairInternal(
        http = http,
        qr = qr,
        phonePrivateKey = identity.privateKey,
        phonePublicKey = identity.publicKey,
        onSetPairing = session::setPairing,
        onSetBaseUrl = { settings.baseUrl = it },
        onSetToken = { settings.deviceToken = it },
    )
}

/** Free function (not a Session/Settings method) so PairingClientTest can
 *  exercise the real handshake logic via plain callbacks -- see Task 11's
 *  Step 1 note on why Identity/Session/Settings can't be constructed in a
 *  local JVM unit test. */
internal suspend fun pairInternal(
    http: OkHttpClient,
    qr: PairingQr,
    phonePrivateKey: ByteArray,
    phonePublicKey: ByteArray,
    onSetPairing: (peerPublicKey: ByteArray, sharedSecret: ByteArray) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetToken: (String) -> Unit,
): Unit = withContext(Dispatchers.IO) {
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
