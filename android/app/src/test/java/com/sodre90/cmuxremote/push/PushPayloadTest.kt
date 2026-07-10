package com.sodre90.cmuxremote.push

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.DIR_AGENT_TO_DEVICE
import com.sodre90.cmuxremote.data.e2e.DIR_DEVICE_TO_AGENT
import com.sodre90.cmuxremote.data.e2e.FakeSession
import com.sodre90.cmuxremote.data.e2e.nonce
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class PushPayloadTest {

    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    /** Builds the exact wire format bridge/internal/server/push.go's
     *  buildEncryptedPush produces: EncodeFrame(secret, DirAgentToDevice, counter,
     *  jsonBytes), base64-encoded -- proving decryptPushPayload really speaks the
     *  agent's wire format, not just its own round trip. */
    private fun agentEncodedPushBlob(secret: ByteArray, counter: Long, title: String, body: String): String {
        val json = """{"title":"$title","body":"$body"}""".toByteArray(Charsets.UTF_8)
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, counter), json)
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(counter)
        ct.copyInto(frame, 8)
        return Base64.getEncoder().encodeToString(frame)
    }

    @Test
    fun decryptsAgentEncodedBlobIntoRealTitleAndBody() {
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val blob = agentEncodedPushBlob(secret, 0L, "cmux-app", "Claude needs your permission")

        val payload = decryptPushPayload(session, cipher, blob)

        assertEquals("cmux-app", payload.title)
        assertEquals("Claude needs your permission", payload.body)
    }

    @Test(expected = Exception::class)
    fun rejectsInvalidBase64() {
        decryptPushPayload(FakeSession(ByteArray(32)), cipher, "not-valid-base64!!")
    }

    @Test(expected = Exception::class)
    fun rejectsBlobEncryptedWithWrongKey() {
        val secret = ByteArray(32) { it.toByte() }
        val wrongSecret = ByteArray(32) { (it + 1).toByte() }
        // Encrypted with a key the receiving session doesn't hold -- the AEAD
        // tag must fail to verify, exactly the "phone re-paired, old
        // ciphertext" scenario TenantFCMDevices' dedup-by-newest-row exists
        // to avoid in the first place, but this proves the decrypt side is
        // also safe if it ever happened.
        val blob = agentEncodedPushBlob(secret, 0L, "x", "y")
        decryptPushPayload(FakeSession(wrongSecret), cipher, blob)
    }

    @Test(expected = Exception::class)
    fun rejectsBlobEncryptedWithDeviceToAgentDirection() {
        // A push blob must be tagged DIR_AGENT_TO_DEVICE (see cipher.go's
        // Nonce direction byte) -- one sealed as if it were the phone's own
        // outgoing traffic must not decrypt.
        val secret = ByteArray(32) { it.toByte() }
        val json = """{"title":"x","body":"y"}""".toByteArray(Charsets.UTF_8)
        val ct = cipher.seal(secret, nonce(DIR_DEVICE_TO_AGENT, 0L), json)
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(0L)
        ct.copyInto(frame, 8)
        decryptPushPayload(FakeSession(secret), cipher, Base64.getEncoder().encodeToString(frame))
    }

    @Test(expected = Exception::class)
    fun rejectsValidCiphertextWithNonJsonPlaintext() {
        val secret = ByteArray(32) { it.toByte() }
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 0L), "not json".toByteArray())
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(0L)
        ct.copyInto(frame, 8)
        decryptPushPayload(FakeSession(secret), cipher, Base64.getEncoder().encodeToString(frame))
    }
}
