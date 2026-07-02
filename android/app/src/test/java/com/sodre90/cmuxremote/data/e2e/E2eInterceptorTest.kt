package com.sodre90.cmuxremote.data.e2e

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.Base64
import java.util.concurrent.TimeUnit

class E2eInterceptorTest {

    private lateinit var server: MockWebServer
    private val cipher = Cipher(LazySodiumJava(SodiumJava()))
    private val secret = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(session: PairedSession) = OkHttpClient.Builder()
        .addInterceptor(E2eInterceptor(session, cipher))
        .build()

    @Test
    fun encryptsRequestBodyAndDecryptsResponseBody() {
        val serverSideSession = FakeSession(secret) // stands in for the agent's own counters
        val agentReply = encryptFrameAsBody("""{"ok":true}""", serverSideSession)
        server.enqueue(MockResponse().setBody(agentReply))

        val client = clientFor(FakeSession(secret))
        val request = Request.Builder()
            .url(server.url("/sessions"))
            .post("""{"fcm_token":"t"}""".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val response = client.newCall(request).execute()

        assertEquals("""{"ok":true}""", response.body!!.string())

        val recorded = server.takeRequest()
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"v\":1")) // request body was encrypted, not the raw JSON
        assertTrue(!sentBody.contains("fcm_token"))
    }

    @Test
    fun skipsWebSocketUpgradeRequests() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("hello") // plaintext -- e2e for WS is handled by Frame.kt, not this interceptor
                }
            }),
        )

        val client = clientFor(FakeSession(secret))
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        val ws = client.newWebSocket(
            Request.Builder().url(server.url("/events")).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            },
        )

        val got = received.poll(5, TimeUnit.SECONDS)
        assertEquals("hello", got) // not intercepted/mangled as if it were an HTTP body
        ws.cancel()
    }

    /** Encodes [json] the way a Go agent's EncryptBody would, so the test client's
     *  E2eInterceptor has something valid to decrypt. */
    private fun encryptFrameAsBody(json: String, session: PairedSession): String {
        val n = session.nextSendCounter()
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, n), json.toByteArray(Charsets.UTF_8))
        return """{"v":1,"n":$n,"ct":"${Base64.getEncoder().encodeToString(ct)}"}"""
    }
}
