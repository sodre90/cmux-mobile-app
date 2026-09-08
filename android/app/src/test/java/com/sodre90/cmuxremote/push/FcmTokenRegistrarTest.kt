package com.sodre90.cmuxremote.push

import com.sodre90.cmuxremote.data.BridgeClient
import com.sodre90.cmuxremote.data.FallbackBridgeClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private class InMemoryPendingTokenStore(private var token: String? = null) : PendingTokenStore {
    override fun pendingFcmToken(): String? = token
    override fun setPendingFcmToken(token: String?) {
        this.token = token
    }
}

/**
 * The rule this all turns on: a token stops being pending only once a slot has
 * actually accepted it. Clearing it on the *attempt* is exactly the bug
 * cmux-app-2cm describes -- FCM issues a rotated token once, so a dropped one is
 * gone, and the server keeps happily 2xx-ing pushes to the dead one it still has.
 */
class FcmTokenRegistrarTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun bridgeFor(server: MockWebServer) = FallbackBridgeClient(
        primary = { BridgeClient(OkHttpClient(), server.url("/").toString()) },
        fallback = { null },
    )

    @Test
    fun anAcceptedTokenStopsBeingPending() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val store = InMemoryPendingTokenStore()
        val registrar = FcmTokenRegistrar(store) { bridgeFor(server) }
        registrar.onTokenIssued("token-1")

        assertEquals(RegistrationAttempt.DONE, registrar.registerPending())
        assertNull(store.pendingFcmToken())
    }

    @Test
    fun aRejectedTokenStaysPendingSoTheRetryHasSomethingToSend() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"agent_offline"}"""))
        val store = InMemoryPendingTokenStore()
        val registrar = FcmTokenRegistrar(store) { bridgeFor(server) }
        registrar.onTokenIssued("token-1")

        assertEquals(RegistrationAttempt.RETRY, registrar.registerPending())
        assertEquals("token-1", store.pendingFcmToken())
    }

    @Test
    fun withNothingPendingThereIsNothingToDo() = runBlocking {
        val registrar = FcmTokenRegistrar(InMemoryPendingTokenStore()) { bridgeFor(server) }

        assertEquals(RegistrationAttempt.NOTHING_PENDING, registrar.registerPending())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun withNoSlotConfiguredTheTokenIsKeptRatherThanRetriedAgainstNothing() = runBlocking {
        val store = InMemoryPendingTokenStore()
        val registrar = FcmTokenRegistrar(store) { null }
        registrar.onTokenIssued("token-1")

        assertEquals(RegistrationAttempt.NOT_CONFIGURED, registrar.registerPending())
        assertEquals("token-1", store.pendingFcmToken())
    }

    @Test
    fun aNewerTokenSupersedesOneStillWaitingToBeSent() {
        val store = InMemoryPendingTokenStore()
        val registrar = FcmTokenRegistrar(store) { null }

        registrar.onTokenIssued("token-1")
        registrar.onTokenIssued("token-2")

        // Only the newest token FCM issued can still route anything, so the
        // older one must not be what a queued retry ends up sending.
        assertEquals("token-2", store.pendingFcmToken())
    }

    @Test
    fun aRetryAfterAFailureSendsTheTokenAgain() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"agent_offline"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        val store = InMemoryPendingTokenStore()
        val registrar = FcmTokenRegistrar(store) { bridgeFor(server) }
        registrar.onTokenIssued("token-1")

        assertEquals(RegistrationAttempt.RETRY, registrar.registerPending())
        assertEquals(RegistrationAttempt.DONE, registrar.registerPending())
        assertNull(store.pendingFcmToken())
        assertEquals(2, server.requestCount)
    }
}
