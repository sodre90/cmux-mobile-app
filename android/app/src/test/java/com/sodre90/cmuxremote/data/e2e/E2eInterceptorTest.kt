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
import org.junit.Assert.assertFalse
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

    private fun clientFor(session: PairedSession, isRelaySlot: Boolean = true) = OkHttpClient.Builder()
        .addInterceptor(E2eInterceptor(session, cipher, isRelaySlot))
        .build()

    @Test
    fun encryptsRequestBodyAndDecryptsResponseBody() {
        val serverSideSession = FakeSession(secret) // stands in for the agent's own counters
        val agentReply = encryptFrameAsBody("""{"ok":true}""", serverSideSession)
        server.enqueue(MockResponse().setHeader("X-Cmux-Encrypted", "1").setBody(agentReply))

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

    @Test
    fun passesThroughUnmarkedPlaintextErrorResponse() {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody("""{"error":"agent_offline"}"""),
        )

        val client = clientFor(FakeSession(secret))
        val request = Request.Builder().url(server.url("/sessions")).build()
        val response = client.newCall(request).execute()

        assertEquals(503, response.code)
        assertEquals("""{"error":"agent_offline"}""", response.body!!.string())
    }

    @Test
    fun doesNotEncryptDeviceRegisterRequestOnRelaySlot() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val client = clientFor(FakeSession(secret), isRelaySlot = true)
        val request = Request.Builder()
            .url(server.url("/devices/register"))
            .post("""{"fcm_token":"t"}""".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val response = client.newCall(request).execute()

        assertEquals("""{"ok":true}""", response.body!!.string())

        val recorded = server.takeRequest()
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("fcm_token"))
        assertFalse(sentBody.contains("\"v\":1"))
    }

    @Test
    fun encryptsDeviceRegisterRequestOnDirectSlot() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val client = clientFor(FakeSession(secret), isRelaySlot = false)
        val request = Request.Builder()
            .url(server.url("/devices/register"))
            .post("""{"fcm_token":"t"}""".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val response = client.newCall(request).execute()

        assertEquals("""{"ok":true}""", response.body!!.string())

        val recorded = server.takeRequest()
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"v\":1")) // request body was encrypted, not the raw JSON
        assertFalse(sentBody.contains("fcm_token"))
    }

    // Forget clears the shared secret without waiting for this call, so an
    // envelope here would lose a race it can never win -- on the direct slot
    // just as much as on the relay. Both servers mount the route outside
    // their encryption layer to match.
    @Test
    fun doesNotEncryptSelfRevokeOnEitherSlot() {
        listOf(true, false).forEach { isRelaySlot ->
            server.enqueue(MockResponse().setBody("{}"))

            val client = clientFor(FakeSession(secret), isRelaySlot = isRelaySlot)
            val request = Request.Builder()
                .url(server.url("/devices/self-revoke"))
                .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { assertEquals("{}", it.body!!.string()) }

            assertFalse(
                "self-revoke must go out in plaintext (isRelaySlot=$isRelaySlot)",
                server.takeRequest().body.readUtf8().contains("\"v\":1"),
            )
        }
    }

    @Test
    fun markedButUndecryptableResponseIsHardError() {
        server.enqueue(
            MockResponse().setHeader("X-Cmux-Encrypted", "1").setBody("not-an-envelope"),
        )

        val client = clientFor(FakeSession(secret))
        val request = Request.Builder().url(server.url("/sessions")).build()
        try {
            client.newCall(request).execute()
            throw AssertionError("expected IOException was not thrown")
        } catch (e: java.io.IOException) {
            // expected: marker present but body not decryptable => hard fail
        }
    }

    /** Encodes [json] the way a Go agent's EncryptBody would, so the test client's
     *  E2eInterceptor has something valid to decrypt. */
    private fun encryptFrameAsBody(json: String, session: PairedSession): String {
        val n = session.nextSendCounter()
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, n), json.toByteArray(Charsets.UTF_8))
        return """{"v":1,"n":$n,"ct":"${Base64.getEncoder().encodeToString(ct)}"}"""
    }
}
