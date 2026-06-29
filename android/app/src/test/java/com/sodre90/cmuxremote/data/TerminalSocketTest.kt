package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.RenderGridDecoder
import com.sodre90.cmuxremote.model.TerminalDown
import com.sodre90.cmuxremote.model.TerminalUp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TerminalSocketTest {

    private lateinit var server: MockWebServer
    private val received = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun receivesReplayFrameAndSendsInput() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        """{"type":"replay","columns":3,"rows":1,"seq":1,"grid":{"columns":3,"rows":1,"row_spans":[{"row":0,"column":0,"text":"hi"}]}}""",
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )

        val ts = TerminalSocket(OkHttpClient(), server.url("/").toString(), "surface-1")

        withTimeout(5_000) {
            val first = CompletableDeferred<TerminalDown>()
            val job = launch(Dispatchers.IO) {
                ts.connect().collect { frame ->
                    if (!first.isCompleted) {
                        ts.send(TerminalUp(type = "input", text = "ls\n"))
                        first.complete(frame)
                    }
                }
            }

            val frame = first.await()
            assertEquals("replay", frame.type)
            // columns=3 so "hi" is padded with one trailing blank to full width.
            assertEquals("hi ", RenderGridDecoder.decode(frame.grid!!).lines[0].text)

            val got = withContext(Dispatchers.IO) { received.poll(5, TimeUnit.SECONDS) }
            assertNotNull(got)
            assertTrue(got!!.contains("\"type\":\"input\""))

            job.cancelAndJoin()
        }
    }
}
