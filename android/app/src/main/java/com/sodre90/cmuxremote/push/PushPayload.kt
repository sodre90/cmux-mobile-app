package com.sodre90.cmuxremote.push

import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.decryptFrame
import com.sodre90.cmuxremote.model.BridgeJson
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * Mirrors bridge/internal/server/push.go's pushPayload -- the plaintext a
 * NeedsAttention push's real title/body take before being e2e-encrypted
 * per-device into FCM's `data["e2e"]` field. This is the only form that
 * content ever has once it leaves the agent, whether relayed or sent
 * directly.
 */
@Serializable
data class PushPayload(val title: String, val body: String)

/**
 * Decrypts an incoming push's `data["e2e"]` field (base64 XChaCha20-Poly1305
 * frame, see bridge/internal/e2e/frame.go's EncodeFrame) back into its real
 * title/body. Throws on anything that isn't a clean decrypt -- no
 * session/key material, corrupt blob, or a JSON shape mismatch -- so the
 * caller can fall back to a generic notification uniformly.
 */
fun decryptPushPayload(session: PairedSession, cipher: Cipher, blobB64: String): PushPayload {
    val frame = Base64.getDecoder().decode(blobB64)
    val plain = decryptFrame(session, cipher, frame)
    return BridgeJson.decodeFromString(PushPayload.serializer(), String(plain, Charsets.UTF_8))
}
