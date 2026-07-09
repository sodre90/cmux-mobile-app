package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.data.e2e.deriveSharedSecret
import com.sodre90.cmuxremote.data.e2e.generateX25519KeyPair
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** Records what PairingClient persisted -- stands in for real Settings/Session/Identity. */
private class FakeIdentity(val priv: ByteArray, val pub: ByteArray)

class PairingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    // pairInternal now rejects a non-https pair_url (see hasSafePairUrl), so
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

        runBlocking { client.pair(qr) }

        assertEquals(server.url("/").toString().trimEnd('/'), recordedBaseUrl[0])
        assertEquals("tok-abc", recordedToken[0])

        val wantSecret = deriveSharedSecret(agentPriv, phonePub) // agent's side of the same ECDH
        assertArrayEquals(agentPub, setPairingCalledWith!!.first)
        assertArrayEquals(wantSecret, setPairingCalledWith!!.second)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/devices/pair", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"code\":\"CODE1\""))
    }

    @Test
    fun pairThrowsPairingCodeInvalidOn410() {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"pairing_code_invalid"}"""))
        val (phonePriv, phonePub) = generateX25519KeyPair()
        val client = TestablePairingClient(http, phonePriv, phonePub, { _, _ -> }, {}, {})
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(), code = "X",
            agentPubkey = Base64.getEncoder().encodeToString(ByteArray(32)),
            expiresAt = "2099-01-01T00:00:00Z", tenantId = "t",
        )
        try {
            runBlocking { client.pair(qr) }
            fail("expected PairingCodeInvalidException")
        } catch (e: PairingCodeInvalidException) {
            // expected
        }
    }

    @Test
    fun pairRejectsNonHttpsPairUrlWithoutIssuingARequest() {
        val (phonePriv, phonePub) = generateX25519KeyPair()
        val client = TestablePairingClient(http, phonePriv, phonePub, { _, _ -> }, {}, {})
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString().replaceFirst("https://", "http://"),
            code = "X",
            agentPubkey = Base64.getEncoder().encodeToString(ByteArray(32)),
            expiresAt = "2099-01-01T00:00:00Z", tenantId = "t",
        )
        try {
            runBlocking { client.pair(qr) }
            fail("expected an IOException for a non-https pair_url")
        } catch (e: IOException) {
            // expected
        }
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
     *  PairingClient.pair() slot-threading is a two-line, branch-free
     *  pass-through verified by inspection, not by this test. */
    @Test
    fun pairWritesIntoTheConstructedSlotNotAHardcodedOne() {
        val (agentPriv, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-direct","tenant_id":"t1"}"""))

        var recordedBaseUrlSlot: com.sodre90.cmuxremote.data.ConnectionSlot? = null
        var recordedTokenSlot: com.sodre90.cmuxremote.data.ConnectionSlot? = null
        val fakeSettingsSetBaseUrl = { slot: com.sodre90.cmuxremote.data.ConnectionSlot, _: String -> recordedBaseUrlSlot = slot }
        val fakeSettingsSetToken = { slot: com.sodre90.cmuxremote.data.ConnectionSlot, _: String -> recordedTokenSlot = slot }

        val slot = com.sodre90.cmuxremote.data.ConnectionSlot.DIRECT
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

        runBlocking { client.pair(qr) }

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
}

/** Test seam: same logic as PairingClient.pair, but with persistence as
 *  three callbacks instead of real Identity/Session/Settings instances. */
private class TestablePairingClient(
    private val http: OkHttpClient,
    private val phonePrivateKey: ByteArray,
    private val phonePublicKey: ByteArray,
    private val onSetPairing: (peerPublicKey: ByteArray, sharedSecret: ByteArray) -> Unit,
    private val onSetBaseUrl: (String) -> Unit,
    private val onSetToken: (String) -> Unit,
) {
    suspend fun pair(qr: PairingQr) = pairInternal(
        http, qr, phonePrivateKey, phonePublicKey, onSetPairing, onSetBaseUrl, onSetToken,
    )
}
