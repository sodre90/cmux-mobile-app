package com.sodre90.cmuxremote.data.e2e

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class FrameTest {

    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Test
    fun encryptFrameThenAgentSideDecodeRoundTrips() {
        // encryptFrame tags DIR_DEVICE_TO_AGENT (phone's outgoing direction);
        // verify by decoding as the agent would: read the 8-byte
        // big-endian counter prefix, then open with the same direction.
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val plaintext = """{"type":"input","text":"ls\n"}""".toByteArray(Charsets.UTF_8)

        val frame = encryptFrame(session, cipher, plaintext)
        val counter = ByteBuffer.wrap(frame, 0, 8).long
        val opened = cipher.open(secret, nonce(DIR_DEVICE_TO_AGENT, counter), frame.copyOfRange(8, frame.size))
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun decryptFrameOpensAgentOriginatedFrame() {
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val plaintext = """{"type":"replay"}""".toByteArray(Charsets.UTF_8)

        // Build a frame as the agent would send it: DIR_AGENT_TO_DEVICE, counter 0.
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 0L), plaintext)
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(0L)
        ct.copyInto(frame, 8)

        val decrypted = decryptFrame(session, cipher, frame)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = DecryptFailedException::class)
    fun decryptFrameRejectsTooShortFrame() {
        decryptFrame(FakeSession(ByteArray(32)), cipher, ByteArray(4))
    }

    @Test
    fun decryptFrameRejectsReplayedCounter() {
        // Deliberately NOT @Test(expected = ...) around the whole method:
        // that form can't distinguish "first call succeeded, second call
        // correctly rejected the replay" from "first call itself threw for
        // the wrong reason" -- only the second call is allowed to throw.
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 0L), "x".toByteArray())
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(0L)
        ct.copyInto(frame, 8)

        decryptFrame(session, cipher, frame) // first: must succeed

        try {
            decryptFrame(session, cipher, frame) // replay: must throw
            fail("expected DecryptFailedException on replay")
        } catch (e: DecryptFailedException) {
            // expected
        }
    }

    @Test
    fun decryptFrameRejectsAFrameFromAnotherPairingWithoutAdvancingTheWindow() {
        // cmux-app-1fx / cmux-app-a3g. Every pairing used to derive the same
        // key, so a frame produced under a *stale* pairing's row still
        // authenticated against the live session -- and because that row's
        // counter was far ahead, committing it slid the replay window past
        // the live counter and every real frame afterwards was rejected as a
        // replay. Per-pairing keys must make the foreign frame fail the AEAD
        // instead, leaving the window where it was.
        val liveSecret = ByteArray(32) { it.toByte() }
        val otherPairingSecret = ByteArray(32) { (it + 1).toByte() }
        val session = FakeSession(liveSecret)

        val foreignCounter = 5_000L
        val foreign = cipher.seal(otherPairingSecret, nonce(DIR_AGENT_TO_DEVICE, foreignCounter), "x".toByteArray())
        val foreignFrame = ByteArray(8 + foreign.size)
        ByteBuffer.wrap(foreignFrame, 0, 8).putLong(foreignCounter)
        foreign.copyInto(foreignFrame, 8)

        try {
            decryptFrame(session, cipher, foreignFrame)
            fail("a frame from a different pairing must not decrypt")
        } catch (e: DecryptFailedException) {
            // expected
        }

        // The live session must still be usable at its own, much lower
        // counter -- that is the part the shared key used to break.
        val live = cipher.seal(liveSecret, nonce(DIR_AGENT_TO_DEVICE, 0L), "live".toByteArray())
        val liveFrame = ByteArray(8 + live.size)
        ByteBuffer.wrap(liveFrame, 0, 8).putLong(0L)
        live.copyInto(liveFrame, 8)

        assertArrayEquals("live".toByteArray(), decryptFrame(session, cipher, liveFrame))
    }

    @Test(expected = DecryptFailedException::class)
    fun decryptFrameRejectsWrongDirectionFrame() {
        // The single highest-risk failure mode per Global Constraints: a
        // direction-tag swap must be a hard decrypt failure, not a silently
        // wrong result (mirrors Task 7's decryptBodyRejectsWrongDirectionCiphertext).
        // Seal with DIR_DEVICE_TO_AGENT (the phone's OWN outgoing direction)
        // -- decryptFrame only ever opens with DIR_AGENT_TO_DEVICE, so this
        // frame must fail to decrypt.
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val ct = cipher.seal(secret, nonce(DIR_DEVICE_TO_AGENT, 0L), "x".toByteArray())
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(0L)
        ct.copyInto(frame, 8)

        decryptFrame(session, cipher, frame)
    }
}
