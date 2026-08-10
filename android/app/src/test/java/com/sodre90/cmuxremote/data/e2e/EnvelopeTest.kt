package com.sodre90.cmuxremote.data.e2e

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** In-memory PairedSession double -- avoids needing Android Keystore in tests. */
class FakeSession(private var secret: ByteArray?) : PairedSession {
    private var sendCounter = 0L
    private var window = ReplayWindow()

    override fun sharedSecret(): ByteArray? = secret
    override fun nextSendCounter(): Long = sendCounter++
    override fun <T> validateAndCommitRecvCounter(n: Long, decrypt: (ByteArray) -> T): T {
        val key = secret ?: throw NotPairedException()
        if (!window.canAccept(n)) throw ReplayRejectedException(n, window.highestSeen)
        return decrypt(key).also { window = window.commit(n) }
    }

    /** Fast-forwards the receive window, standing in for the state a
     *  now-superseded pairing left behind (see cmux-app-a3g). */
    fun advanceRecvWindowTo(n: Long) { window = window.commit(n) }
}

class EnvelopeTest {

    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Test
    fun encryptThenDecryptRoundTrips() {
        val secret = ByteArray(32) { it.toByte() }
        val sender = FakeSession(secret)
        val receiver = FakeSession(secret)
        val plaintext = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)

        // The phone is the sender here (outgoing == DIR_DEVICE_TO_AGENT), so
        // the receiver must open with the agent's perspective... but since
        // this test exercises the phone's own encryptBody/decryptBody pair
        // (both phone-side), decryptBody expects DIR_AGENT_TO_DEVICE-tagged
        // ciphertext (what the phone RECEIVES). We build that ciphertext
        // manually here to test decryptBody in isolation from encryptBody,
        // which always encrypts as DIR_DEVICE_TO_AGENT (what the phone SENDS).
        val n = receiver.nextSendCounter() // reuse counter machinery for a manual envelope
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, n), plaintext)
        val envelope = """{"v":1,"n":$n,"ct":"${Base64.getEncoder().encodeToString(ct)}"}"""

        val decrypted = decryptBody(sender, cipher, envelope.toByteArray(Charsets.UTF_8))
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptBodyProducesDecryptableEnvelopeForTheAgentDirection() {
        // encryptBody always tags DIR_DEVICE_TO_AGENT (phone's outgoing
        // direction) -- verify by opening it that way directly, matching
        // what the Go agent's DecryptBody does on receipt.
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val plaintext = "input text".toByteArray(Charsets.UTF_8)

        val envelopeBytes = encryptBody(session, cipher, plaintext)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(envelopeBytes.toString(Charsets.UTF_8)).jsonObject
        val n = json["n"]!!.jsonPrimitive.long
        val ctB64 = json["ct"]!!.jsonPrimitive.content
        val ct = Base64.getDecoder().decode(ctB64)

        val opened = cipher.open(secret, nonce(DIR_DEVICE_TO_AGENT, n), ct)
        assertArrayEquals(plaintext, opened)
    }

    @Test(expected = NotPairedException::class)
    fun encryptBodyThrowsWhenNotPaired() {
        encryptBody(FakeSession(null), cipher, "x".toByteArray())
    }

    @Test
    fun decryptBodyRejectsReplayedCounter() {
        // Deliberately NOT @Test(expected = ...) around the whole method: that
        // form can't distinguish "first call succeeded, second call correctly
        // rejected the replay" from "first call itself threw for the wrong
        // reason" -- only the second call is allowed to throw.
        val secret = ByteArray(32) { it.toByte() }
        val receiver = FakeSession(secret)
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 0L), "x".toByteArray())
        val envelope = """{"v":1,"n":0,"ct":"${Base64.getEncoder().encodeToString(ct)}"}"""

        decryptBody(receiver, cipher, envelope.toByteArray(Charsets.UTF_8)) // first: must succeed

        try {
            decryptBody(receiver, cipher, envelope.toByteArray(Charsets.UTF_8)) // replay: must throw
            fail("expected DecryptFailedException on replay")
        } catch (e: DecryptFailedException) {
            // expected
        }
    }

    @Test
    fun decryptBodyNamesTheCounterAndWindowWhenAStaleWindowRefusesAFrame() {
        // decryptBody has its own counter gate independent of decryptFrame's,
        // so the same stale-window diagnosis has to hold on the HTTP path too
        // (cmux-app-a3g) -- the REST calls fail exactly as the sockets do.
        val secret = ByteArray(32) { it.toByte() }
        val receiver = FakeSession(secret)
        receiver.advanceRecvWindowTo(500_878L)

        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 3L), "x".toByteArray())
        val envelope = """{"v":1,"n":3,"ct":"${Base64.getEncoder().encodeToString(ct)}"}"""

        try {
            decryptBody(receiver, cipher, envelope.toByteArray(Charsets.UTF_8))
            fail("expected the stale window to reject this body")
        } catch (e: ReplayRejectedException) {
            assertEquals("replay_rejected: counter=3 highest_seen=500878", e.message)
        }
    }

    @Test(expected = DecryptFailedException::class)
    fun decryptBodyRejectsWrongDirectionCiphertext() {
        // The single highest-risk failure mode per Global Constraints: a
        // direction-tag swap must be a hard decrypt failure, not a silently
        // wrong result. Seal with DIR_DEVICE_TO_AGENT (the phone's OWN
        // outgoing direction) -- decryptBody only ever opens with
        // DIR_AGENT_TO_DEVICE, so this ciphertext must fail to decrypt.
        val secret = ByteArray(32) { it.toByte() }
        val receiver = FakeSession(secret)
        val ct = cipher.seal(secret, nonce(DIR_DEVICE_TO_AGENT, 0L), "x".toByteArray())
        val envelope = """{"v":1,"n":0,"ct":"${Base64.getEncoder().encodeToString(ct)}"}"""

        decryptBody(receiver, cipher, envelope.toByteArray(Charsets.UTF_8))
    }
}
