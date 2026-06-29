package com.sodre90.cmuxremote.data

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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventsSocketTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun emitsTwoDecodedFrames() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        """{"type":"feed","name":"feed.updated","needs_attention":true,"feed_id":"f1","kind":"permissionRequest"}""",
                    )
                    webSocket.send("""{"type":"heartbeat"}""")
                }
            }),
        )

        val es = EventsSocket(OkHttpClient(), server.url("/").toString())
        val frames = runBlocking { withTimeout(5_000) { es.connect().take(2).toList() } }

        assertEquals(2, frames.size)
        assertEquals("feed", frames[0].type)
        assertTrue(frames[0].needsAttention)
        assertEquals("f1", frames[0].feedId)
        assertEquals("permissionRequest", frames[0].kind)
        assertEquals("heartbeat", frames[1].type)
    }
}
