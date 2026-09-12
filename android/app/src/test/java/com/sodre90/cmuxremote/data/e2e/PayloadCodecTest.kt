package com.sodre90.cmuxremote.data.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.Deflater

class PayloadCodecTest {

    /**
     * The bytes `wire.EncodePayload` actually produced, copied from the Go
     * side's TestTheCrossLanguageFixtureIsUnchanged. This is the test that
     * would have caught a zlib-vs-raw-DEFLATE mismatch: Go writes raw DEFLATE
     * and Java's default Inflater expects a zlib wrapper, and each side's own
     * tests pass either way. If the Go fixture test fails, regenerate this
     * constant from the value it prints.
     */
    private val goFixture = (
        "018488410a02300cc0fe92730f0a9efa1511193a459c76ac1d2a637f97f90173" +
            "4a32884fcd28d6a3f640b8b6db191d9cacf4c7d3d19dd0ece5e8f62747af69edfd" +
            "58856e84c8ef40497f601ea66077f4928ae7f90d0000ffff"
        ).hexToByteArray()

    private val goFixturePlaintext =
        """{"type":"output","grid":{"columns":4,"rows":1,"row_spans":""" +
            """[{"row":0,"text":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]},"ok":false}"""

    @Test
    fun inflatesAPayloadProducedByTheGoBridge() {
        assertEquals(PAYLOAD_DEFLATE, goFixture[0])
        assertEquals(goFixturePlaintext, String(decodePayload(goFixture), Charsets.UTF_8))
    }

    @Test
    fun passesAnIdentityPayloadThroughUntouched() {
        val json = """{"type":"ack","seq":7,"ok":true}""".toByteArray(Charsets.UTF_8)
        val tagged = byteArrayOf(PAYLOAD_IDENTITY) + json
        assertArrayEquals(json, decodePayload(tagged))
    }

    @Test
    fun refusesAnUnknownCodecTag() {
        assertThrows(PayloadCodecException::class.java) {
            decodePayload(byteArrayOf(9, 'x'.code.toByte()))
        }
    }

    @Test
    fun refusesAnEmptyPayload() {
        assertThrows(PayloadCodecException::class.java) { decodePayload(ByteArray(0)) }
    }

    /** Frames are authenticated, so this is a broken bridge rather than an
     *  attacker -- but it must drop the frame, not exhaust the heap. */
    @Test
    fun refusesAPayloadThatInflatesPastTheCap() {
        val bomb = Deflater(Deflater.BEST_COMPRESSION, true).run {
            setInput(ByteArray(9 * 1024 * 1024))
            finish()
            val out = ByteArray(1 shl 20)
            val n = deflate(out)
            end()
            out.copyOfRange(0, n)
        }
        assertThrows(PayloadCodecException::class.java) {
            decodePayload(byteArrayOf(PAYLOAD_DEFLATE) + bomb)
        }
    }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
