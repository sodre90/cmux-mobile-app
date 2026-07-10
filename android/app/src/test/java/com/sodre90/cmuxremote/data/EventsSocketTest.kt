package com.sodre90.cmuxremote.data

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.DIR_AGENT_TO_DEVICE
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.ReplayWindow
import com.sodre90.cmuxremote.data.e2e.nonce
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

private class SendOnlySession(private val secret: ByteArray) : PairedSession {
    private var counter = 0L
    private val window = ReplayWindow()
    override fun sharedSecret(): ByteArray = secret
    override fun nextSendCounter(): Long = counter++
    override fun canAcceptRecvCounter(n: Long): Boolean = window.canAccept(n)
    override fun commitRecvCounter(n: Long) {}
}

class EventsSocketTest {

    private lateinit var server: MockWebServer
    private val secret = ByteArray(32) { it.toByte() }
    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun frameFor(json: String, counter: Long): okio.ByteString {
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, counter), json.toByteArray(Charsets.UTF_8))
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(counter)
        ct.copyInto(frame, 8)
        return frame.toByteString()
    }

    @Test
    fun emitsTwoDecodedFrames() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        frameFor(
                            """{"type":"feed","name":"feed.updated","needs_attention":true,""" +
                                """"feed_id":"f1","kind":"permissionRequest"}""",
                            0L,
                        ),
                    )
                    webSocket.send(frameFor("""{"type":"heartbeat"}""", 1L))
                }
            }),
        )

        val es = EventsSocket(OkHttpClient(), server.url("/").toString(), SendOnlySession(secret), cipher)
        val frames = runBlocking { withTimeout(5_000) { es.connect().take(2).toList() } }

        assertEquals(2, frames.size)
        assertEquals("feed", frames[0].type)
        assertTrue(frames[0].needsAttention)
        assertEquals("f1", frames[0].feedId)
        assertEquals("permissionRequest", frames[0].kind)
        assertEquals("heartbeat", frames[1].type)
    }
}
