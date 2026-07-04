package com.sodre90.cmuxremote.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class FallbackBridgeClientTest {

    private lateinit var primaryServer: MockWebServer
    private lateinit var fallbackServer: MockWebServer

    @Before
    fun setUp() {
        primaryServer = MockWebServer().apply { start() }
        fallbackServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        primaryServer.shutdown()
        fallbackServer.shutdown()
    }

    /** connectTimeoutMs is also used as the read timeout, so a NO_RESPONSE
     *  MockResponse (server accepts the connection but never replies) fails
     *  fast and deterministically instead of hanging for OkHttp's real
     *  10s default. */
    private fun clientFor(server: MockWebServer, connectTimeoutMs: Long = 2_000): BridgeClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return BridgeClient(http, server.url("/").toString())
    }

    @Test
    fun primarySuccessNeverCallsFallback() {
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(primary = { clientFor(primaryServer) }, fallback = { clientFor(fallbackServer) })

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, primaryServer.requestCount)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun primaryFailureFallsBackAndSucceeds() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer) },
        )

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, primaryServer.requestCount)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun bothFailPropagatesException() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fallbackServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer, connectTimeoutMs = 300) },
        )

        try {
            runBlocking { fb.sessions() }
            fail("expected an exception when both primary and fallback fail")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun onlyPrimaryConfiguredUsesPrimaryDirectly() {
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(primary = { clientFor(primaryServer) }, fallback = { null })

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, primaryServer.requestCount)
    }

    @Test
    fun onlyPrimaryConfiguredPropagatesFailureWhenNoFallback() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fb = FallbackBridgeClient(primary = { clientFor(primaryServer, connectTimeoutMs = 300) }, fallback = { null })

        try {
            runBlocking { fb.sessions() }
            fail("expected the primary's failure to propagate when there's no fallback")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun onlyFallbackConfiguredUsesFallbackDirectly() {
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(primary = { null }, fallback = { clientFor(fallbackServer) })

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun penaltyWindowSkipsPrimaryUntilItExpires() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}""")) // must NOT be consumed by the 2nd call
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        var clock = 1_000_000L
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer) },
            now = { clock },
        )

        runBlocking { fb.sessions() } // primary times out, falls back, sets 30s penalty
        clock += 10_000L // still inside the window
        runBlocking { fb.sessions() } // must skip primary entirely

        assertEquals(1, primaryServer.requestCount)
        assertEquals(2, fallbackServer.requestCount)
    }

    @Test
    fun penaltyWindowExpiresAndRetriesPrimary() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        var clock = 1_000_000L
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer) },
            now = { clock },
        )

        runBlocking { fb.sessions() } // primary times out, falls back, sets 30s penalty
        clock += 31_000L // past the window

        runBlocking { fb.sessions() } // must retry primary, which now succeeds

        assertEquals(2, primaryServer.requestCount)
        assertEquals(1, fallbackServer.requestCount)
    }
}
