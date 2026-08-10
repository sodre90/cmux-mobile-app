package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.data.e2e.deriveSharedSecret
import com.sodre90.cmuxremote.data.e2e.generateX25519KeyPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * cmux-app-1fx: the phone used to hold one persistent X25519 keypair, so
 * every pairing against the same agent derived a bit-identical AEAD key while
 * each new agent-side device row restarted its counters at zero -- reusing
 * (direction, counter) nonces. These assert the property that prevents it:
 * one keypair per pairing, never reused.
 */
class PairingKeysTest {

    private val agent = generateX25519KeyPair()

    @Test
    fun `each pairing derives a different shared secret against the same agent`() {
        val keys = PairingKeys()

        val (firstPriv, firstPub) = keys.begin()
        keys.consume()
        val (secondPriv, secondPub) = keys.begin()
        keys.consume()

        assertFalse("two pairings must not submit the same device_pubkey", firstPub.contentEquals(secondPub))
        assertNotEquals(
            "two pairings must not derive the same AEAD key -- this is the cmux-app-1fx defect",
            deriveSharedSecret(firstPriv, agent.second).toList(),
            deriveSharedSecret(secondPriv, agent.second).toList(),
        )
    }

    @Test
    fun `commit consumes exactly the keypair prepare minted`() {
        val keys = PairingKeys()

        val minted = keys.begin()
        val consumed = keys.consume()

        // The SAS fingerprint the user confirmed is computed over the minted
        // key, so the submitted key has to be that same one.
        assertArrayEquals(minted.first, consumed.first)
        assertArrayEquals(minted.second, consumed.second)
    }

    @Test
    fun `re-preparing replaces the pending keypair`() {
        val keys = PairingKeys()

        keys.begin()
        val second = keys.begin()
        val consumed = keys.consume()

        assertArrayEquals(
            "the fingerprint on screen is the second one, so the second key must be submitted",
            second.second,
            consumed.second,
        )
    }

    @Test
    fun `committing without preparing fails rather than inventing a key`() {
        // A key generated at commit time was never shown to the user, so its
        // fingerprint was never confirmed -- that would silently void the SAS
        // MITM check rather than merely inconvenience the user.
        assertThrows(IOException::class.java) { PairingKeys().consume() }
    }

    @Test
    fun `a keypair cannot be reused for a second pairing`() {
        val keys = PairingKeys()
        keys.begin()
        keys.consume()

        assertThrows(IOException::class.java) { keys.consume() }
    }
}
