package com.sodre90.cmuxremote.data.e2e

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.utils.LibraryLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private fun hex(s: String): ByteArray {
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }
    return out
}

private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

class CipherTest {

    private val cipher = Cipher(LazySodiumJava(SodiumJava(LibraryLoader.Mode.SYSTEM_ONLY)))

    @Test
    fun nonceLaysOutDirectionAndCounter() {
        val n = nonce(DIR_AGENT_TO_DEVICE, 42L)
        assertEquals(24, n.size)
        assertEquals(0x00.toByte(), n[15])
        // big-endian 42 in the last 8 bytes
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 42), n.copyOfRange(16, 24))

        val n2 = nonce(DIR_DEVICE_TO_AGENT, 1L)
        assertEquals(0x01.toByte(), n2[15])
    }

    @Test
    fun deriveSharedSecretMatchesGoFixedVector() {
        // Mirrors bridge/internal/e2e/cipher_test.go TestDeriveSharedSecretFixedVector.
        val agentPriv = ByteArray(32) { 0x01 }
        val devicePriv = ByteArray(32) { 0x02 }

        val agentPub = x25519PublicKeyFromPrivate(agentPriv)
        val devicePub = x25519PublicKeyFromPrivate(devicePriv)

        assertEquals(
            "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209",
            hexOf(agentPub),
        )
        assertEquals(
            "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59",
            hexOf(devicePub),
        )

        val agentSide = deriveSharedSecret(agentPriv, devicePub)
        val deviceSide = deriveSharedSecret(devicePriv, agentPub)

        val want = "0c657b7b4a6f6eede1d9f03bad4f9c898e9291c22eeb4cd09f12df79394837d6"
        assertEquals(want, hexOf(agentSide))
        assertEquals(want, hexOf(deviceSide))
    }

    @Test
    fun generateX25519KeyPairProducesConsistentKeys() {
        val (priv, pub) = generateX25519KeyPair()
        assertEquals(32, priv.size)
        assertEquals(32, pub.size)
        assertArrayEquals(pub, x25519PublicKeyFromPrivate(priv))
    }

    @Test
    fun sealMatchesGoFixedVector() {
        // Mirrors bridge/internal/e2e/cipher_test.go TestFixedCipherVector.
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "cmux-bridge e2e test vector".toByteArray(Charsets.UTF_8)
        val n = nonce(DIR_AGENT_TO_DEVICE, 42L)

        val ct = cipher.seal(key, n, plaintext)

        val want = "3adf930c2c38c2dc6de9e1fab5be816f607fea9f2d9e503a7f22277d65a588c593c28255c0dc93cac7a52a"
        assertEquals(want, hexOf(ct))

        val pt = cipher.open(key, n, ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test(expected = DecryptFailedException::class)
    fun openRejectsWrongKey() {
        val key1 = ByteArray(32)
        val key2 = ByteArray(32).also { it[0] = 0xff.toByte() }
        val n = nonce(DIR_AGENT_TO_DEVICE, 0L)
        val ct = cipher.seal(key1, n, "secret".toByteArray())
        cipher.open(key2, n, ct)
    }

    @Test(expected = DecryptFailedException::class)
    fun openRejectsWrongDirection() {
        val key = ByteArray(32)
        val ct = cipher.seal(key, nonce(DIR_AGENT_TO_DEVICE, 0L), "secret".toByteArray())
        cipher.open(key, nonce(DIR_DEVICE_TO_AGENT, 0L), ct)
    }
}
