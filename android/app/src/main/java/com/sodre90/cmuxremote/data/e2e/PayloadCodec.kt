package com.sodre90.cmuxremote.data.e2e

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A render grid is large and repetitive -- one 282x79 pane measured 186,821
 * bytes on the wire, 143KB of it scrollback the pane had already sent -- and
 * nothing compressed it. Compressing the transport would not have helped:
 * terminal frames are sealed before they are written, and ciphertext does not
 * compress. So the bridge compresses the plaintext INSIDE the AEAD and tags it
 * with a leading codec byte, which this undoes.
 *
 * Mirrors bridge/internal/wire/payload.go -- keep the two in step. The tag
 * appears only on a socket that negotiated compression (see [TerminalSocket]);
 * on any other socket the sealed bytes are the plaintext JSON as before.
 */

/** The payload is already plaintext JSON. */
internal const val PAYLOAD_IDENTITY: Byte = 0

/** The payload is raw DEFLATE (RFC 1951), with no zlib or gzip wrapper. */
internal const val PAYLOAD_DEFLATE: Byte = 1

/**
 * Caps what one frame may inflate to. Frames are AEAD-authenticated, so an
 * oversized one means a broken or compromised bridge rather than an attacker,
 * but the bound turns that into a dropped frame instead of an OOM. Well above
 * the largest grid measured (~370KB).
 */
private const val MAX_PAYLOAD_SIZE = 8 * 1024 * 1024

private const val INFLATE_CHUNK = 16 * 1024

class PayloadCodecException(message: String) : Exception(message)

/** Strips the codec tag written by the Go side's `wire.EncodePayload`. */
fun decodePayload(payload: ByteArray): ByteArray {
    if (payload.isEmpty()) throw PayloadCodecException("empty payload")
    val body = payload.copyOfRange(1, payload.size)
    return when (payload[0]) {
        PAYLOAD_IDENTITY -> body
        PAYLOAD_DEFLATE -> inflate(body)
        else -> throw PayloadCodecException("unknown payload codec ${payload[0]}")
    }
}

/**
 * `Inflater(true)` -- nowrap -- is load-bearing: Go's `compress/flate` writes
 * raw DEFLATE, while Java's default Inflater expects a zlib header and fails on
 * every frame without it. The cross-language fixture in TerminalSocketTest
 * pins the exact bytes, because each side passes its own tests either way.
 */
private fun inflate(body: ByteArray): ByteArray {
    val inflater = Inflater(true)
    return try {
        inflater.setInput(body)
        val out = ByteArrayOutputStream(body.size * 4)
        val chunk = ByteArray(INFLATE_CHUNK)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0) {
                // A Go stream ends on a sync-flush marker rather than a final
                // block, so `finished()` may never become true; running out of
                // input is the ordinary end of a frame, not an error.
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            if (out.size() + n > MAX_PAYLOAD_SIZE) throw PayloadCodecException("payload too large")
            out.write(chunk, 0, n)
        }
        out.toByteArray()
    } catch (e: DataFormatException) {
        throw PayloadCodecException("inflate failed: ${e.message}")
    } finally {
        inflater.end()
    }
}
