package com.sodre90.cmuxremote.data

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.DIR_AGENT_TO_DEVICE
import com.sodre90.cmuxremote.data.e2e.DIR_DEVICE_TO_AGENT
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.ReplayRejectedException
import com.sodre90.cmuxremote.data.e2e.ReplayWindow
import com.sodre90.cmuxremote.data.e2e.nonce
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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Simple PairedSession double sharing one secret with independent counters,
 *  used to simulate "the other side" (a mock agent) in these WS tests. */
private class SharedSecretSession(private val secret: ByteArray) : PairedSession {
    private var sendCounter = 0L
    private var window = ReplayWindow()
    override fun sharedSecret(): ByteArray = secret
    override fun nextSendCounter(): Long = sendCounter++
    override fun <T> validateAndCommitRecvCounter(n: Long, decrypt: (ByteArray) -> T): T {
        if (!window.canAccept(n)) throw ReplayRejectedException(n, window.highestSeen)
        return decrypt(secret).also { window = window.commit(n) }
    }
}

class TerminalSocketTest {

    private lateinit var server: MockWebServer
    private val received = LinkedBlockingQueue<ByteString>()
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

    @Test
    fun receivesReplayFrameAndSendsEncryptedInput() = runBlocking {
        val serverSession = SharedSecretSession(secret)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val plaintext = """{"type":"replay","columns":3,"rows":1,"seq":1,
                        "grid":{"columns":3,"rows":1,"row_spans":[{"row":0,"column":0,"text":"hi"}]}}"""
                    val n = serverSession.nextSendCounter()
                    val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, n), plaintext.toByteArray(Charsets.UTF_8))
                    val frame = ByteArray(8 + ct.size)
                    ByteBuffer.wrap(frame, 0, 8).putLong(n)
                    ct.copyInto(frame, 8)
                    webSocket.send(frame.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received.add(bytes)
                }
            }),
        )

        val clientSession = SharedSecretSession(secret)
        val ts = TerminalSocket(OkHttpClient(), server.url("/").toString(), "surface-1", clientSession, cipher)

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

            val gotBytes = withContext(Dispatchers.IO) { received.poll(5, TimeUnit.SECONDS) }
            assertNotNull(gotBytes)
            // Decode as the agent would: an 8-byte counter prefix, then open
            // with DIR_DEVICE_TO_AGENT (the phone's outgoing direction).
            val raw = gotBytes!!.toByteArray()
            val n = ByteBuffer.wrap(raw, 0, 8).long
            val opened = cipher.open(secret, nonce(DIR_DEVICE_TO_AGENT, n), raw.copyOfRange(8, raw.size))
            assertTrue(String(opened, Charsets.UTF_8).contains("\"type\":\"input\""))

            job.cancelAndJoin()
        }
    }

    /** Connects and returns whatever ended the flow, or null for a clean end. */
    private fun collectUntilClosedBy(closeCode: Int): Throwable? = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(closeCode, "")
                }
            }),
        )
        val ts = TerminalSocket(
            OkHttpClient(),
            server.url("/").toString(),
            "surface-1",
            SharedSecretSession(secret),
            cipher,
        )
        withTimeout(5_000) {
            withContext(Dispatchers.IO) {
                runCatching { ts.connect().collect { } }.exceptionOrNull()
            }
        }
    }

    // cmux-app-34c: onClosing threw the close code away, so a surface the
    // bridge had just declared gone ended the flow exactly like a dropped
    // connection -- and the reconnect loop dutifully dialled it again.
    @Test
    fun surfaceGoneCloseCodeEndsTheFlowWithADistinguishableFailure() {
        assertTrue(
            "want SubscriptionGoneException",
            collectUntilClosedBy(CLOSE_SURFACE_GONE) is SubscriptionGoneException,
        )
    }

    // Any other close is still a plain end-of-stream, which the reconnect loop
    // treats as a disconnect and retries. That is the behaviour to preserve.
    @Test
    fun anOrdinaryCloseStillEndsTheFlowCleanly() {
        for (code in listOf(1000, 1001, 1011)) {
            assertNull("close $code must not look like a gone surface", collectUntilClosedBy(code))
        }
    }
}
