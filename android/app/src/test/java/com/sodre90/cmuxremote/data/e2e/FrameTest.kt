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
