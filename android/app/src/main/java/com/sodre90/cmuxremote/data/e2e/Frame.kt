package com.sodre90.cmuxremote.data.e2e

import java.nio.ByteBuffer

/**
 * Encrypts an outgoing WS message into `[8-byte big-endian counter][ciphertext+tag]`.
 * Phone's outgoing direction is DIR_DEVICE_TO_AGENT -- mirrors
 * bridge/internal/e2e/frame.go's EncodeFrame/EncryptFrame.
 */
fun encryptFrame(session: PairedSession, cipher: Cipher, plaintext: ByteArray): ByteArray {
    val secret = session.sharedSecret() ?: throw NotPairedException()
    val n = session.nextSendCounter()
    val ct = cipher.seal(secret, nonce(DIR_DEVICE_TO_AGENT, n), plaintext)
    val out = ByteArray(8 + ct.size)
    ByteBuffer.wrap(out, 0, 8).putLong(n)
    ct.copyInto(out, 8)
    return out
}

/**
 * Decrypts an incoming WS message. Phone's incoming direction is
 * DIR_AGENT_TO_DEVICE. The window check, the AEAD attempt and the commit run
 * as one atomic step -- mirrors bridge/internal/e2e/frame.go's
 * DecodeFrame/DecryptFrame, which wraps the same three in the agent's
 * ValidateAndCommitRecvCounter.
 */
fun decryptFrame(session: PairedSession, cipher: Cipher, frame: ByteArray): ByteArray {
    if (frame.size < 8) throw DecryptFailedException()
    val n = ByteBuffer.wrap(frame, 0, 8).long
    return session.validateAndCommitRecvCounter(n) { secret ->
        cipher.open(secret, nonce(DIR_AGENT_TO_DEVICE, n), frame.copyOfRange(8, frame.size))
    }
}
