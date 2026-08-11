package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.FeedReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BridgeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor("tok-7"))
            .build()
        client = BridgeClient(http, server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sessionsDecodesEnvelopeAndSendsBearer() {
        server.enqueue(
            MockResponse().setBody(
                """{"workspaces":[{"id":"a","cwd":"/x","title":"build","preview":"Claude is waiting","has_unread":true,
                "terminals":[{"id":"t-a","cwd":"/x","title":"build","focused":true,"ready":true,"kind":"agent"}]}]}""",
            ),
        )

        val list = runBlocking { client.sessions() }

        assertEquals(1, list.size)
        assertEquals("a", list[0].id)
        assertTrue(list[0].hasUnread)
        assertEquals(1, list[0].terminals.size)
        assertEquals("t-a", list[0].terminals[0].id)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/sessions", req.path)
        assertEquals("Bearer tok-7", req.getHeader("Authorization"))
    }

    @Test
    fun replyFeedPostsToFeedPathWithBody() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val reply = FeedReply(
            kind = "permissionRequest",
            requestId = "req-1",
            params = buildJsonObject { put("decision", "approve") },
        )
        runBlocking { client.replyFeed("feed-9", reply) }

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/feed/feed-9/reply", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"request_id\":\"req-1\""))
        assertTrue(body.contains("approve"))
    }

    @Test
    fun sessionsDecodesYoloMode() {
        server.enqueue(
            MockResponse().setBody(
                """{"workspaces":[{"id":"a","cwd":"/x","title":"build","yolo_mode":"bypass","terminals":[]}]}""",
            ),
        )

        val list = runBlocking { client.sessions() }

        assertEquals("bypass", list[0].yoloMode)
    }

    @Test
    fun setYoloModePostsToYoloModePath() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        runBlocking { client.setYoloMode("ws-1", "bypass") }

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/sessions/ws-1/yolo-mode", req.path)
        assertTrue(req.body.readUtf8().contains("\"mode\":\"bypass\""))
    }

    @Test
    fun registerDevicePostsFcmToken() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        runBlocking { client.registerDevice("fcm-xyz") }

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/devices/register", req.path)
        assertTrue(req.body.readUtf8().contains("\"fcm_token\":\"fcm-xyz\""))
    }

    @Test
    fun selfRevokeNamesNoDeviceAndSendsThisDevicesToken() {
        server.enqueue(MockResponse().setBody("{}"))

        runBlocking { client.selfRevoke() }

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/devices/self-revoke", req.path)
        assertEquals("Bearer tok-7", req.getHeader("Authorization"))
        assertEquals("{}", req.body.readUtf8())
    }

    @Test
    fun nonSuccessThrowsBridgeException() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"cmux unavailable"}"""))

        try {
            runBlocking { client.sessions() }
            fail("expected BridgeException")
        } catch (e: BridgeException) {
            assertEquals(502, e.code)
            assertTrue(e.bodyText.contains("cmux unavailable"))
        }
    }
}
