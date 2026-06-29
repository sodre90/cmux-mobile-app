package com.sodre90.cmuxremote.data

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore

class MtlsTest {

    private lateinit var server: MockWebServer
    private lateinit var serverCa: HeldCertificate
    private lateinit var clientCa: HeldCertificate
    private lateinit var clientCert: HeldCertificate

    @Before
    fun setUp() {
        serverCa = HeldCertificate.Builder().certificateAuthority(0).commonName("server-ca").build()
        val serverCert = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .signedBy(serverCa)
            .build()
        clientCa = HeldCertificate.Builder().certificateAuthority(0).commonName("client-ca").build()
        clientCert = HeldCertificate.Builder().commonName("device").signedBy(clientCa).build()

        val serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .addTrustedCertificate(clientCa.certificate) // accept our client CA
            .build()

        server = MockWebServer()
        server.useHttps(serverHandshake.sslSocketFactory(), false)
        server.requireClientAuth()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun presentsClientCertAndBearerHeader() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val cfg = BridgeConfig(
            baseUrl = server.url("/").toString(),
            deviceToken = "tok-123",
            clientP12 = clientP12("changeit"),
            p12Password = "changeit",
            serverCaPem = serverCa.certificatePem(),
        )

        val resp = Mtls.client(cfg)
            .newCall(Request.Builder().url(server.url("/ping")).build())
            .execute()
        resp.use { assertEquals(200, it.code) }

        val recorded = server.takeRequest()
        assertEquals("Bearer tok-123", recorded.getHeader("Authorization"))
    }

    @Test
    fun rejectsClientWithoutCertificate() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val cfg = BridgeConfig(
            baseUrl = server.url("/").toString(),
            deviceToken = "tok",
            clientP12 = null, // no client cert -> server requires one
            serverCaPem = serverCa.certificatePem(),
        )

        try {
            Mtls.client(cfg)
                .newCall(Request.Builder().url(server.url("/")).build())
                .execute()
            fail("expected TLS handshake to fail without a client certificate")
        } catch (_: IOException) {
            // expected: server rejects the unauthenticated handshake
        }
    }

    /** Packs the test client cert + key into a password-protected PKCS#12 blob. */
    private fun clientP12(password: String): ByteArray {
        val pw = password.toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "client",
                clientCert.keyPair.private,
                pw,
                arrayOf(clientCert.certificate, clientCa.certificate),
            )
        }
        return ByteArrayOutputStream().use { ks.store(it, pw); it.toByteArray() }
    }
}
