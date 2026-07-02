package com.sodre90.cmuxremote.data.e2e

import com.sodre90.cmuxremote.model.BridgeJson
import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
private data class BodyEnvelope(val v: Int, val n: Long, val ct: String)

/** No shared secret on file -- the app's local state was wiped, or pairing
 *  never completed. Mirrors the Go side's 409 not_paired case. */
class NotPairedException : Exception("not_paired")

/**
 * Encrypts an outgoing HTTP request/response body into the wire envelope
 * `{"v":1,"n":<counter>,"ct":"<base64>"}`. The phone's outgoing direction is
 * DIR_DEVICE_TO_AGENT (see Global Constraints) -- mirrors
 * bridge/internal/e2e/envelope.go's EncryptBody exactly, mirrored.
 */
fun encryptBody(session: PairedSession, cipher: Cipher, plaintext: ByteArray): ByteArray {
    val secret = session.sharedSecret() ?: throw NotPairedException()
    val n = session.nextSendCounter()
    val ct = cipher.seal(secret, nonce(DIR_DEVICE_TO_AGENT, n), plaintext)
    val envelope = BodyEnvelope(v = 1, n = n, ct = Base64.getEncoder().encodeToString(ct))
    return BridgeJson.encodeToString(BodyEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
}

/**
 * Decrypts an incoming HTTP body. The phone's incoming direction is
 * DIR_AGENT_TO_DEVICE. Two-phase: the counter is checked BEFORE opening and
 * committed only after the AEAD tag verifies (see Global Constraints) --
 * mirrors bridge/internal/e2e/envelope.go's DecryptBody.
 */
fun decryptBody(session: PairedSession, cipher: Cipher, envelopeBytes: ByteArray): ByteArray {
    val envelope = try {
        BridgeJson.decodeFromString(BodyEnvelope.serializer(), envelopeBytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw DecryptFailedException()
    }
    if (envelope.v != 1) throw DecryptFailedException()
    val secret = session.sharedSecret() ?: throw NotPairedException()
    if (!session.canAcceptRecvCounter(envelope.n)) throw DecryptFailedException()
    val ct = try {
        Base64.getDecoder().decode(envelope.ct)
    } catch (e: Exception) {
        throw DecryptFailedException()
    }
    val pt = cipher.open(secret, nonce(DIR_AGENT_TO_DEVICE, envelope.n), ct)
    session.commitRecvCounter(envelope.n)
    return pt
}
