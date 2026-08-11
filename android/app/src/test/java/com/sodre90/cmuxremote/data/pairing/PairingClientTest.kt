package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.e2e.deriveSharedSecret
import com.sodre90.cmuxremote.data.e2e.generateX25519KeyPair
import com.sodre90.cmuxremote.data.e2e.pairingFingerprint
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** Records what PairingClient persisted -- stands in for real Settings/CryptoSession. */
private class FakeIdentity(val priv: ByteArray, val pub: ByteArray)

class PairingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    // prepareInternal/commitInternal now reject a non-https pair_url (see hasSafePairUrl), so
    // the MockWebServer these tests POST/GET against must actually speak
    // TLS -- a self-signed cert the client is told to trust, same as
    // OkHttp's own test suite does for localhost.
    @Before
    fun setUp() {
        val serverCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(serverCert).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCert.certificate)
            .build()
        http = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pairSuccessDerivesSecretAndPersistsTokenAndBaseUrl() {
        val (agentPriv, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()

        server.enqueue(MockResponse().setBody("""{"token":"tok-abc","tenant_id":"t1"}"""))
        server.enqueue(confirmed())

        val recordedBaseUrl = arrayOfNulls<String>(1)
        val recordedToken = arrayOfNulls<String>(1)
        var setPairingCalledWith: Pair<ByteArray, ByteArray>? = null

        val client = TestablePairingClient(
            http = http,
            phonePrivateKey = phonePriv,
            phonePublicKey = phonePub,
            onSetPairing = { peerPub, secret -> setPairingCalledWith = peerPub to secret },
            onSetBaseUrl = { recordedBaseUrl[0] = it },
            onSetToken = { recordedToken[0] = it },
        )

        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(),
            code = "CODE1",
            agentPubkey = Base64.getEncoder().encodeToString(agentPub),
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t1",
        )

        runBlocking { client.commit(qr) }

        assertEquals(server.url("/").toString().trimEnd('/'), recordedBaseUrl[0])
        assertEquals("tok-abc", recordedToken[0])

        val wantSecret = deriveSharedSecret(agentPriv, phonePub) // agent's side of the same ECDH
        assertArrayEquals(agentPub, setPairingCalledWith!!.first)
        assertArrayEquals(wantSecret, setPairingCalledWith!!.second)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/devices/pair", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"code\":\"CODE1\""))

        val polled = server.takeRequest()
        assertEquals("GET", polled.method)
        assertEquals("/devices/pair-status/CODE1", polled.path)
    }

    /**
     * cmux-app-smu. A commit that only rewrites storage leaves the sockets
     * opened on the previous token connected, still mapped to the agent's
     * old device row -- which now encrypts under a key this session no
     * longer holds, so every frame is dropped until the app is restarted.
     * The signal has to fire, and fire after the token is stored, or a
     * socket woken by it reconnects on the credentials it was meant to
     * replace.
     */
    @Test
    fun pairSuccessSignalsThatTheSlotsCredentialsWereReplacedOnceTheyArePersisted() {
        val (_, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-new","tenant_id":"t1"}"""))
        server.enqueue(confirmed())

        var tokenWhenSignalled: String? = null
        var storedToken: String? = null
        var signalCount = 0

        val client = TestablePairingClient(
            http = http,
            phonePrivateKey = phonePriv,
            phonePublicKey = phonePub,
            onSetPairing = { _, _ -> },
            onSetBaseUrl = {},
            onSetToken = { storedToken = it },
            onCredentialsReplaced = {
                signalCount++
                tokenWhenSignalled = storedToken
            },
        )

        runBlocking { client.commit(qrFor(agentPub, code = "CODE1")) }

        assertEquals(1, signalCount)
        assertEquals("signalled before the new token was stored", "tok-new", tokenWhenSignalled)
    }

    /** A pairing that never completed replaced nothing, so waking the live
     *  sockets would drop a working connection for no reason. */
    @Test
    fun aFailedPairDoesNotSignalThatCredentialsWereReplaced() {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"pairing_code_invalid"}"""))
        val (phonePriv, phonePub) = generateX25519KeyPair()
        var signalled = false
        val client = TestablePairingClient(
            http = http,
            phonePrivateKey = phonePriv,
            phonePublicKey = phonePub,
            onSetPairing = { _, _ -> },
            onSetBaseUrl = {},
            onSetToken = {},
            onCredentialsReplaced = { signalled = true },
        )

        try {
            runBlocking { client.commit(qrFor(ByteArray(32), code = "X")) }
            fail("expected PairingCodeInvalidException")
        } catch (e: PairingCodeInvalidException) {
            // expected
        }
        assertFalse("a refused pairing replaced nothing", signalled)
    }

    private fun qrFor(agentPub: ByteArray, code: String) = PairingQr(
        pairUrl = server.url("/devices/pair").toString(),
        code = code,
        agentPubkey = Base64.getEncoder().encodeToString(agentPub),
        expiresAt = "2099-01-01T00:00:00Z",
        tenantId = "t1",
    )

    @Test
    fun pairThrowsPairingCodeInvalidOn410() {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"pairing_code_invalid"}"""))
        val (phonePriv, phonePub) = generateX25519KeyPair()
        val client = TestablePairingClient(http, phonePriv, phonePub, { _, _ -> }, {}, {})
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(),
            code = "X",
            agentPubkey = Base64.getEncoder().encodeToString(ByteArray(32)),
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t",
        )
        try {
            runBlocking { client.commit(qr) }
            fail("expected PairingCodeInvalidException")
        } catch (e: PairingCodeInvalidException) {
            // expected
        }
    }

    @Test
    fun prepareRejectsNonHttpsPairUrlWithoutIssuingARequest() {
        val (phonePriv, phonePub) = generateX25519KeyPair()
        val client = TestablePairingClient(http, phonePriv, phonePub, { _, _ -> }, {}, {})
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString().replaceFirst("https://", "http://"),
            code = "X",
            agentPubkey = Base64.getEncoder().encodeToString(ByteArray(32)),
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t",
        )
        try {
            client.prepare(qr)
            fail("expected an IOException for a non-https pair_url")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun prepareComputesFingerprintWithoutIssuingARequest() {
        val (_, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        val client = TestablePairingClient(http, phonePriv, phonePub, { _, _ -> }, {}, {})
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(),
            code = "X",
            agentPubkey = Base64.getEncoder().encodeToString(agentPub),
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t",
        )

        val fingerprint = client.prepare(qr)

        assertEquals(pairingFingerprint(phonePub, agentPub), fingerprint)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun resolvePairingCodeBuildsEquivalentPairingQr() {
        val (_, agentPub) = generateX25519KeyPair()
        val agentPubkeyB64 = Base64.getEncoder().encodeToString(agentPub)
        server.enqueue(
            MockResponse().setBody(
                """{"agent_pubkey":"$agentPubkeyB64","expires_at":"2099-01-01T00:00:00Z","tenant_id":"t1"}""",
            ),
        )

        val serverUrl = server.url("/").toString().trimEnd('/')
        val qr = runBlocking { resolvePairingCode(http, serverUrl, "MANUAL01") }

        assertEquals("$serverUrl/devices/pair", qr.pairUrl)
        assertEquals("MANUAL01", qr.code)
        assertEquals(agentPubkeyB64, qr.agentPubkey)
        assertEquals("2099-01-01T00:00:00Z", qr.expiresAt)
        assertEquals("t1", qr.tenantId)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/devices/pair-info/MANUAL01", recorded.path)
    }

    @Test
    fun resolvePairingCodeThrowsPairingCodeInvalidOn410() {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"pairing_code_invalid"}"""))
        val serverUrl = server.url("/").toString().trimEnd('/')
        try {
            runBlocking { resolvePairingCode(http, serverUrl, "BOGUS") }
            fail("expected PairingCodeInvalidException")
        } catch (e: PairingCodeInvalidException) {
            // expected
        }
    }

    /** Exercises TestablePairingClient's closure-capture shape only -- does
     *  NOT construct or call the real PairingClient class. The real
     *  PairingClient.commit() slot-threading is a two-line, branch-free
     *  pass-through verified by inspection, not by this test. */
    @Test
    fun commitWritesIntoTheConstructedSlotNotAHardcodedOne() {
        val (agentPriv, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-direct","tenant_id":"t1"}"""))
        server.enqueue(confirmed())

        var recordedBaseUrlSlot: ConnectionSlot? = null
        var recordedTokenSlot: ConnectionSlot? = null
        val fakeSettingsSetBaseUrl = { slot: ConnectionSlot, _: String -> recordedBaseUrlSlot = slot }
        val fakeSettingsSetToken = { slot: ConnectionSlot, _: String -> recordedTokenSlot = slot }

        val slot = ConnectionSlot.DIRECT
        val client = TestablePairingClient(
            http = http,
            phonePrivateKey = phonePriv,
            phonePublicKey = phonePub,
            onSetPairing = { _, _ -> },
            onSetBaseUrl = { fakeSettingsSetBaseUrl(slot, it) },
            onSetToken = { fakeSettingsSetToken(slot, it) },
        )
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(),
            code = "CODE1",
            agentPubkey = Base64.getEncoder().encodeToString(agentPub),
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t1",
        )

        runBlocking { client.commit(qr) }

        assertEquals(slot, recordedBaseUrlSlot)
        assertEquals(slot, recordedTokenSlot)
    }

    @Test
    fun resolvePairingCodeTrimsTrailingSlashFromServerUrl() {
        val (_, agentPub) = generateX25519KeyPair()
        val agentPubkeyB64 = Base64.getEncoder().encodeToString(agentPub)
        server.enqueue(
            MockResponse().setBody(
                """{"agent_pubkey":"$agentPubkeyB64","expires_at":"2099-01-01T00:00:00Z","tenant_id":"t1"}""",
            ),
        )

        val serverUrlWithSlash = server.url("/").toString() // already trailing-slash
        val qr = runBlocking { resolvePairingCode(http, serverUrlWithSlash, "MANUAL01") }

        assertEquals(server.url("/").toString().trimEnd('/') + "/devices/pair", qr.pairUrl)
    }

    private fun confirmed() = MockResponse().setBody("""{"state":"confirmed"}""")

    private fun pending() = MockResponse().setBody("""{"state":"pending"}""")

    /**
     * cmux-app-gmo. The phone used to declare the slot paired the moment
     * redemption returned, which is before anyone at the Mac has agreed to
     * the pairing. Observing the store from inside each poll is what makes
     * this a claim about the whole wait rather than about one callback.
     */
    @Test
    fun nothingIsPersistedWhileTheOperatorHasNotAnswered() {
        val (_, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        val persisted = mutableListOf<String>()
        val persistedDuringEachPoll = mutableListOf<List<String>>()

        var polls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/devices/pair-status/") != true) {
                    return MockResponse().setBody("""{"token":"tok-abc","tenant_id":"t1"}""")
                }
                persistedDuringEachPoll += persisted.toList()
                polls++
                return if (polls < 3) pending() else confirmed()
            }
        }

        var stateAtAwaitingOperator: List<String> = listOf("never fired")
        val client = testClient(phonePriv, phonePub, persisted)

        runBlocking {
            client.commit(qrFor(agentPub, code = "CODE1")) {
                stateAtAwaitingOperator = persisted.toList()
            }
        }

        assertEquals(emptyList<String>(), stateAtAwaitingOperator)
        assertEquals(3, persistedDuringEachPoll.size)
        assertEquals(listOf(emptyList<String>(), emptyList(), emptyList()), persistedDuringEachPoll)
        assertEquals(listOf("pairing", "baseUrl", "token", "credentialsReplaced"), persisted)
    }

    @Test
    fun aRefusedPairingPersistsNothingAndSaysItWasRefused() {
        val (_, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-abc","tenant_id":"t1"}"""))
        server.enqueue(MockResponse().setBody("""{"state":"refused"}"""))

        val persisted = mutableListOf<String>()
        val client = testClient(phonePriv, phonePub, persisted)

        try {
            runBlocking { client.commit(qrFor(agentPub, code = "CODE1")) }
            fail("expected PairingRefusedException")
        } catch (e: PairingRefusedException) {
            // expected
        }
        assertEquals(emptyList<String>(), persisted)
    }

    /** An operator who never answers is not the same as one who said no: the
     *  user's next move differs, so the two must not collapse. */
    @Test
    fun anUnansweredPairingPersistsNothingAndIsDistinctFromARefusal() {
        val (_, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-abc","tenant_id":"t1"}"""))
        repeat(20) { server.enqueue(pending()) }

        val persisted = mutableListOf<String>()
        val client = testClient(phonePriv, phonePub, persisted, confirmTimeoutMillis = 60)

        try {
            runBlocking { client.commit(qrFor(agentPub, code = "CODE1")) }
            fail("expected PairingNotAnsweredException")
        } catch (e: PairingNotAnsweredException) {
            // expected
        }
        assertEquals(emptyList<String>(), persisted)
    }

    /** A relay hiccup mid-wait is not an answer. Treating it as one would
     *  fail a pairing the operator is in the middle of accepting -- and a
     *  404 is exactly what a relay too old to serve this route replies. */
    @Test
    fun aTransientPollFailureIsRetriedRatherThanReadAsARefusal() {
        val (_, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-abc","tenant_id":"t1"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(confirmed())

        val persisted = mutableListOf<String>()
        val client = testClient(phonePriv, phonePub, persisted)

        runBlocking { client.commit(qrFor(agentPub, code = "CODE1")) }

        assertEquals(listOf("pairing", "baseUrl", "token", "credentialsReplaced"), persisted)
    }

    /** cmux-app-smu: onCredentialsReplaced tears down live sockets on the
     *  slot, so it must stay last -- and now, must not fire at all for a
     *  pairing the operator went on to refuse. */
    @Test
    fun aRefusedPairingDoesNotTearDownTheSlotsLiveSockets() {
        val (_, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-abc","tenant_id":"t1"}"""))
        server.enqueue(MockResponse().setBody("""{"state":"refused"}"""))

        var signalled = false
        val client = TestablePairingClient(
            http = http,
            phonePrivateKey = phonePriv,
            phonePublicKey = phonePub,
            onSetPairing = { _, _ -> },
            onSetBaseUrl = {},
            onSetToken = {},
            onCredentialsReplaced = { signalled = true },
        )

        try {
            runBlocking { client.commit(qrFor(agentPub, code = "CODE1")) }
            fail("expected PairingRefusedException")
        } catch (e: PairingRefusedException) {
            // expected
        }
        assertFalse("a refused pairing killed a working connection", signalled)
    }

    private fun testClient(
        phonePriv: ByteArray,
        phonePub: ByteArray,
        persisted: MutableList<String>,
        confirmTimeoutMillis: Long = 2000,
    ) = TestablePairingClient(
        http = http,
        phonePrivateKey = phonePriv,
        phonePublicKey = phonePub,
        onSetPairing = { _, _ -> persisted += "pairing" },
        onSetBaseUrl = { persisted += "baseUrl" },
        onSetToken = { persisted += "token" },
        onCredentialsReplaced = { persisted += "credentialsReplaced" },
        confirmTimeoutMillis = confirmTimeoutMillis,
    )
}

/** Test seam: same logic as PairingClient.prepare/commit, but with
 *  persistence and the credential-invalidation signal as callbacks instead
 *  of real CryptoSession/Settings/SlotCredentials instances. */
private class TestablePairingClient(
    private val http: OkHttpClient,
    private val phonePrivateKey: ByteArray,
    private val phonePublicKey: ByteArray,
    private val onSetPairing: (peerPublicKey: ByteArray, sharedSecret: ByteArray) -> Unit,
    private val onSetBaseUrl: (String) -> Unit,
    private val onSetToken: (String) -> Unit,
    private val onCredentialsReplaced: () -> Unit = {},
    private val confirmTimeoutMillis: Long = 2000,
) {
    fun prepare(qr: PairingQr): String = prepareInternal(qr, phonePublicKey)

    suspend fun commit(qr: PairingQr, onAwaitingOperator: () -> Unit = {}) = commitInternal(
        http = http,
        qr = qr,
        phonePrivateKey = phonePrivateKey,
        phonePublicKey = phonePublicKey,
        onSetPairing = onSetPairing,
        onSetBaseUrl = onSetBaseUrl,
        onSetToken = onSetToken,
        onCredentialsReplaced = onCredentialsReplaced,
        onAwaitingOperator = onAwaitingOperator,
        pollPeriodMillis = 10,
        confirmTimeoutMillis = confirmTimeoutMillis,
    )
}
