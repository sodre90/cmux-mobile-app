package com.sodre90.cmuxremote.data.e2e

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Agent's outgoing / phone's incoming direction tag (see Global Constraints). */
const val DIR_AGENT_TO_DEVICE: Byte = 0x00

/** Phone's outgoing / agent's incoming direction tag (see Global Constraints). */
const val DIR_DEVICE_TO_AGENT: Byte = 0x01

private const val HKDF_INFO_PREFIX = "cmux-bridge e2e v1|"

/**
 * 24-byte XChaCha20-Poly1305 nonce: byte 15 is the direction tag, bytes
 * 16-23 are the big-endian counter. Mirrors bridge/internal/e2e/cipher.go's
 * Nonce exactly -- this is the cross-language wire-format contract.
 */
fun nonce(direction: Byte, counter: Long): ByteArray {
    val n = ByteArray(24)
    n[15] = direction
    for (i in 0 until 8) {
        n[16 + i] = (counter ushr (8 * (7 - i))).toByte()
    }
    return n
}

/** Generates a fresh X25519 keypair; returns (privateKeyRaw32, publicKeyRaw32). */
fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
    val generator = X25519KeyPairGenerator()
    generator.init(X25519KeyGenerationParameters(SecureRandom()))
    val keyPair = generator.generateKeyPair()
    val priv = keyPair.private as X25519PrivateKeyParameters
    val pub = keyPair.public as X25519PublicKeyParameters
    return priv.encoded to pub.encoded
}

fun x25519PublicKeyFromPrivate(privateKeyRaw: ByteArray): ByteArray =
    X25519PrivateKeyParameters(privateKeyRaw, 0).generatePublicKey().encoded

/** Sorts the two public keys lexicographically before concatenation, so both peers
 *  derive an identical HKDF info string regardless of which side computes it --
 *  mirrors bridge/internal/e2e/cipher.go's buildInfo exactly. */
private fun buildInfo(pubA: ByteArray, pubB: ByteArray): ByteArray {
    val (first, second) = if (compareBytes(pubA, pubB) > 0) pubB to pubA else pubA to pubB
    val out = ByteArrayOutputStream()
    out.write(HKDF_INFO_PREFIX.toByteArray(Charsets.UTF_8))
    out.write(first)
    out.write('|'.code)
    out.write(second)
    return out.toByteArray()
}

private fun compareBytes(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (diff != 0) return diff
    }
    return a.size - b.size
}

/**
 * ECDH(myPriv, theirPub) then HKDF-SHA256(secret, salt=null, info=buildInfo(...))
 * -> 32-byte shared secret. Mirrors bridge/internal/e2e/cipher.go's
 * DeriveSharedSecret; both peers derive the same value independently.
 */
fun deriveSharedSecret(myPrivateKeyRaw: ByteArray, theirPublicKeyRaw: ByteArray): ByteArray {
    val myPriv = X25519PrivateKeyParameters(myPrivateKeyRaw, 0)
    val myPub = myPriv.generatePublicKey()
    val theirPub = X25519PublicKeyParameters(theirPublicKeyRaw, 0)

    val agreement = X25519Agreement()
    agreement.init(myPriv)
    val ecdh = ByteArray(agreement.agreementSize)
    agreement.calculateAgreement(theirPub, ecdh, 0)

    val info = buildInfo(myPub.encoded, theirPublicKeyRaw)
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(HKDFParameters(ecdh, null, info))
    val out = ByteArray(32)
    hkdf.generateBytes(out, 0, 32)
    return out
}
