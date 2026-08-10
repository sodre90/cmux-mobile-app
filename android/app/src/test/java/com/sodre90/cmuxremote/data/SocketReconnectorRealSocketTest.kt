package com.sodre90.cmuxremote.data

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.model.EventFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** EventsSocket only needs a session to decrypt inbound frames, and this test
 *  deliberately sends none -- the socket's lifecycle, not its payloads, is
 *  what's under test. */
private class NoFrameSession : PairedSession {
    override fun sharedSecret(): ByteArray = ByteArray(32)
    override fun nextSendCounter(): Long = 0L
    override fun <T> validateAndCommitRecvCounter(n: Long, decrypt: (ByteArray) -> T): T = decrypt(ByteArray(32))
}

/**
 * Covers the one part of the "return to RELAY once it recovers" fix that
 * [SocketReconnectorTest]'s fake flows cannot: that cancelling the
 * reconnector's inner scope really does tear the *live* WebSocket down.
 *
 * The production socket closes via [EventsSocket]'s `callbackFlow { ...
 * awaitClose { socket.cancel() } }`. A fake flow satisfies the reconnector's
 * contract without ever exercising that, so a regression that left the parked
 * DIRECT socket open -- leaking a connection and a duplicate event stream on
 * every relay blip -- would still pass the unit tests. Here both slots are
 * real [MockWebServer]s, so the close is asserted from the *server* side.
 *
 * Real sockets mean real time (no virtual clock), so the relay penalty is
 * widened well past the wall-clock the test needs and the backoff is shrunk
 * to keep it quick.
 */
class SocketReconnectorRealSocketTest {

    private lateinit var relayServer: MockWebServer
    private lateinit var directServer: MockWebServer
    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Before
    fun setUp() {
        relayServer = MockWebServer().apply { start() }
        directServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        relayServer.shutdown()
        directServer.shutdown()
    }

    @Test
    fun recoveredRelayClosesTheParkedDirectWebSocketAndReconnects() = runBlocking {
        // Rejecting the upgrade fails the socket before any frame arrives,
        // which is exactly what makes the reconnector penalize RELAY.
        relayServer.enqueue(MockResponse().setResponseCode(500))
        relayServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

        val directClosed = CountDownLatch(1)
        directServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        directClosed.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        directClosed.countDown()
                    }
                },
            ),
        )

        val health = RelayHealth(penaltyMs = 60_000L)
        val reconnector = SocketReconnector<EventFrame>(health, initialBackoffMs = 1L, maxBackoffMs = 1L)
        val http = OkHttpClient()
        val job = launch(Dispatchers.IO) {
            reconnector.run(openSocket = { slot, onOpen ->
                val base = if (slot == ConnectionSlot.RELAY) relayServer.url("/") else directServer.url("/")
                EventsSocket(http, base.toString(), NoFrameSession(), cipher).connect(onOpen)
            }) { true }
        }

        assertEquals("/events", relayServer.takeRequest(10, TimeUnit.SECONDS)?.path)
        assertEquals("/events", directServer.takeRequest(10, TimeUnit.SECONDS)?.path)

        // The trigger under test: the REST path proving RELAY healthy again.
        // Waiting for the DIRECT request above guarantees the reconnector has
        // already committed to DIRECT and captured its recovery count, so this
        // lands as a genuine down-to-up edge rather than pre-empting the pick.
        health.markUp()

        assertTrue(
            "parked DIRECT socket was never closed; awaitClose did not cancel it",
            directClosed.await(10, TimeUnit.SECONDS),
        )
        assertNotNull(
            "no second RELAY connection; the socket did not come back",
            relayServer.takeRequest(10, TimeUnit.SECONDS),
        )

        job.cancelAndJoin()
    }

    /**
     * cmux-app-2zn / cmux-app-smu, and the same gap this file exists for:
     * [SocketReconnectorTest]'s fake flows show the reconnector *intends* to
     * end the connection, but only a real socket shows it actually does.
     * Forget and re-pair both rewrite storage while a live socket goes on
     * authenticating with the credentials they replaced, so a regression
     * that left it open would keep streaming over a forgotten slot -- and,
     * after a re-pair, drop every frame as decrypt_failed until restart.
     * Asserted from the *server* side, which is the only place the close is
     * observable.
     */
    @Test
    fun invalidatingASlotsCredentialsClosesItsLiveWebSocketAndReconnects() = runBlocking {
        val relayClosed = CountDownLatch(1)
        relayServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        relayClosed.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        relayClosed.countDown()
                    }
                },
            ),
        )
        relayServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

        val credentials = SlotCredentials()
        val reconnector = SocketReconnector<EventFrame>(
            RelayHealth(),
            initialBackoffMs = 1L,
            maxBackoffMs = 1L,
            slotCredentials = credentials,
        )
        val http = OkHttpClient()
        val job = launch(Dispatchers.IO) {
            reconnector.run(openSocket = { slot, onOpen ->
                val base = if (slot == ConnectionSlot.RELAY) relayServer.url("/") else directServer.url("/")
                EventsSocket(http, base.toString(), NoFrameSession(), cipher).connect(onOpen)
            }) { true }
        }

        assertEquals("/events", relayServer.takeRequest(10, TimeUnit.SECONDS)?.path)

        credentials.invalidate(ConnectionSlot.RELAY)

        assertTrue(
            "the socket on the replaced credentials was never closed",
            relayClosed.await(10, TimeUnit.SECONDS),
        )
        assertNotNull(
            "no reconnect after invalidation; the subscription was left dead",
            relayServer.takeRequest(10, TimeUnit.SECONDS),
        )

        job.cancelAndJoin()
    }
}
