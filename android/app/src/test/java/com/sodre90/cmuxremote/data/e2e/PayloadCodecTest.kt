package com.sodre90.cmuxremote.data.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /**
     * Two chunks of one stream, copied from the Go side's
     * TestTheCrossLanguageStreamFixtureIsUnchanged. The second is a quarter the
     * size of the first for a near-identical frame -- that is the saving, and it
     * is only readable because the first primed the same decoder.
     */
    private val goStreamFixture = listOf(
        "028488510a802014c0eeb26f3f0afa7a5789082989c052d42811ef1e7681f6b5ad9" +
            "0b23708c178ab338a2dec2b52589cbd8e33228322b83b22fd2773f4baedb1b4423a4" +
            "5324f42d03f50a75a5f000000ffff",
        "02825b985f5a52505a427b0b01000000ffff",
    ).map { it.hexToByteArray() }

    private val goStreamPlaintext = listOf("replay", "output").map { type ->
        """{"type":"$type","grid":{"columns":4,"rows":1,"row_spans":""" +
            """[{"row":0,"text":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}}"""
    }

    @Test
    fun inflatesAStreamProducedByTheGoBridge() {
        StreamPayloadDecoder().use { decoder ->
            goStreamFixture.forEachIndexed { i, chunk ->
                assertEquals(PAYLOAD_DEFLATE_STREAM, chunk[0])
                assertEquals(goStreamPlaintext[i], String(decoder.decode(chunk), Charsets.UTF_8))
            }
        }
    }

    /**
     * The half that keeps the test above honest. If this decoder secretly
     * restarted its window per frame, it would still pass that test on a Go
     * encoder that did the same -- both sides wrong, in step. A decoder that
     * never saw chunk one must not be able to read chunk two.
     */
    @Test
    fun cannotReadAChunkWithoutTheOneBeforeIt() {
        StreamPayloadDecoder().use { decoder ->
            val read = runCatching { String(decoder.decode(goStreamFixture[1]), Charsets.UTF_8) }
            assertNotEquals(
                "a fresh decoder read a chunk of a warmed stream",
                goStreamPlaintext[1],
                read.getOrNull(),
            )
        }
    }

    /** The bridge streams every frame including acks, so a standalone one means
     *  its stream encode failed. The frame must still get through; the desync it
     *  leaves behind is the next chunk's problem. */
    @Test
    fun acceptsAStandaloneFrameOnAStreamingSocket() {
        val json = """{"type":"ack","seq":7,"ok":true}""".toByteArray(Charsets.UTF_8)
        StreamPayloadDecoder().use { decoder ->
            assertArrayEquals(json, decoder.decode(byteArrayOf(PAYLOAD_IDENTITY) + json))
        }
    }

    @Test
    fun refusesAnUnknownCodecTagOnAStream() {
        StreamPayloadDecoder().use { decoder ->
            assertThrows(PayloadCodecException::class.java) {
                decoder.decode(byteArrayOf(9, 'x'.code.toByte()))
            }
        }
    }

    /** Garbage must surface as a thrown desync, not a hung reader thread. */
    @Test
    fun refusesAChunkThatIsNotPartOfTheStream() {
        StreamPayloadDecoder().use { decoder ->
            decoder.decode(goStreamFixture[0])
            assertThrows(PayloadCodecException::class.java) {
                decoder.decode(byteArrayOf(PAYLOAD_DEFLATE_STREAM) + ByteArray(64) { 0xFF.toByte() })
            }
        }
    }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
