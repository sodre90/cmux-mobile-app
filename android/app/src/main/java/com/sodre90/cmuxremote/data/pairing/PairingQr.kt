package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.model.BridgeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** The JSON payload rendered into the pairing QR by `cmux-bridge pair-device`
 *  (bridge/cmd/cmux-bridge/pair.go's pairingQR struct). */
@Serializable
data class PairingQr(
    @SerialName("pair_url") val pairUrl: String = "",
    val code: String = "",
    @SerialName("agent_pubkey") val agentPubkey: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("tenant_id") val tenantId: String = "",
)

/** Returns null for anything that isn't a valid pairing QR -- malformed
 *  JSON, or JSON missing the fields this flow actually needs. The camera
 *  scanner resumes scanning on null rather than crashing (a scanned code
 *  may simply be unrelated to cmux). */
fun parsePairingQr(raw: String): PairingQr? {
    val qr = try {
        BridgeJson.decodeFromString(PairingQr.serializer(), raw)
    } catch (e: Exception) {
        return null
    }
    if (qr.pairUrl.isBlank() || qr.code.isBlank() || qr.agentPubkey.isBlank()) return null
    return qr
}

/** Client-side expiry check so a stale/reused QR fails fast with a clear
 *  message instead of waiting on the server's 410. Unparseable timestamps
 *  are treated as not-expired -- the server is the authoritative check. */
fun PairingQr.isExpired(): Boolean = try {
    Instant.parse(expiresAt).isBefore(Instant.now())
} catch (e: Exception) {
    false
}
