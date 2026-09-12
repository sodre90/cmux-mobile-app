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
 * The payload is one frame's worth of a DEFLATE stream that stays open for the
 * life of the socket, so it is compressed against every frame sent before it.
 * Worth roughly a tenth of the bytes on a settled pane, at the cost of frames
 * no longer standing alone: only [StreamPayloadDecoder], fed every chunk in
 * order, can read one. [decodePayload] rejects this tag for exactly that
 * reason.
 */
internal const val PAYLOAD_DEFLATE_STREAM: Byte = 2

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
                // Neither finished nor waiting on us, yet producing nothing:
                // looping again would spin the socket's reader thread forever.
                throw PayloadCodecException("inflate made no progress")
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

/**
 * Decodes the chunks of one socket's shared-window stream. Hold one per socket,
 * feed it every frame in arrival order, and [close] it when the socket ends.
 *
 * There is no way to skip a chunk. Each one is compressed against the window
 * the ones before it built, so a frame that is missed or dropped makes every
 * later frame unreadable -- which is why a failure here has to close the socket
 * and resync from a fresh replay rather than be swallowed (see [TerminalSocket]
 * and its DesyncException). Not thread-safe: the bridge writes a socket's
 * frames in order under one lock, and this must read them the same way.
 */
class StreamPayloadDecoder : AutoCloseable {

    private val inflater = Inflater(true)

    private val chunk = ByteArray(INFLATE_CHUNK)

    fun decode(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) throw PayloadCodecException("empty payload")
        return when (payload[0]) {
            PAYLOAD_DEFLATE_STREAM -> inflateChunk(payload.copyOfRange(1, payload.size))
            // The bridge streams every frame on a streaming socket, acks
            // included, so the only way one of these arrives is a stream encode
            // that failed. It decodes on its own, but it leaves this decoder's
            // window a frame behind the encoder's, so the next chunk will fail
            // and resync. Accepted rather than rejected so the frame itself
            // still gets through.
            PAYLOAD_IDENTITY, PAYLOAD_DEFLATE -> decodePayload(payload)
            else -> throw PayloadCodecException("unknown payload codec ${payload[0]}")
        }
    }

    /**
     * Unlike [inflate] this never sees a finished stream: the bridge ends every
     * chunk with a sync flush instead of a final block, precisely so the window
     * survives. Running out of input is therefore the end of a frame, and
     * anything else -- a finished stream, a dictionary request, or no progress
     * with input still pending -- means the stream and this decoder have
     * diverged and the socket has to resync.
     */
    private fun inflateChunk(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size * INFLATE_GROWTH_GUESS)
        try {
            inflater.setInput(body)
            while (true) {
                val n = inflater.inflate(chunk)
                if (n == 0) {
                    if (inflater.needsInput()) break
                    if (inflater.needsDictionary()) throw PayloadCodecException("stream wants a preset dictionary")
                    if (inflater.finished()) throw PayloadCodecException("stream ended mid-socket")
                    throw PayloadCodecException("stream made no progress")
                }
                if (out.size() + n > MAX_PAYLOAD_SIZE) throw PayloadCodecException("payload too large")
                out.write(chunk, 0, n)
            }
        } catch (e: DataFormatException) {
            throw PayloadCodecException("stream inflate failed: ${e.message}")
        }
        return out.toByteArray()
    }

    /** Releases the native inflater. Never call this between frames -- it
     *  throws away the window every later frame depends on. */
    override fun close() {
        inflater.end()
    }
}

private const val INFLATE_GROWTH_GUESS = 8
