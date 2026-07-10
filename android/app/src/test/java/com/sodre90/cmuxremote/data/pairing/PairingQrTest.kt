package com.sodre90.cmuxremote.data.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PairingQrTest {

    @Test
    fun parsesValidPayload() {
        val raw = """{"pair_url":"https://cmux.example.com/devices/pair","code":"ABC123",
            "agent_pubkey":"YWJj","expires_at":"2099-01-01T00:00:00Z","tenant_id":"t1"}"""

        val qr = parsePairingQr(raw)

        assertEquals("https://cmux.example.com/devices/pair", qr?.pairUrl)
        assertEquals("ABC123", qr?.code)
        assertEquals("YWJj", qr?.agentPubkey)
        assertEquals("t1", qr?.tenantId)
    }

    @Test
    fun returnsNullForMalformedOrForeignQrContent() {
        assertNull(parsePairingQr("not json at all"))
        assertNull(
            parsePairingQr("""{"totally":"unrelated"}""")
        ) // missing required fields still decodes with defaults...
    }

    @Test
    fun returnsNullForHttpPairUrl() {
        val raw = """{"pair_url":"http://cmux.example.com/devices/pair","code":"ABC123",
            "agent_pubkey":"YWJj","expires_at":"2099-01-01T00:00:00Z","tenant_id":"t1"}"""
        assertNull(parsePairingQr(raw))
    }

    @Test
    fun returnsNullForPairUrlWithUserinfo() {
        val raw = """{"pair_url":"https://user:pass@cmux.example.com/devices/pair","code":"ABC123",
            "agent_pubkey":"YWJj","expires_at":"2099-01-01T00:00:00Z","tenant_id":"t1"}"""
        assertNull(parsePairingQr(raw))
    }

    @Test
    fun hasSafePairUrlAcceptsPlainHttps() {
        val qr = PairingQr(pairUrl = "https://cmux.example.com/devices/pair", code = "c", agentPubkey = "p")
        assertTrue(qr.hasSafePairUrl())
    }

    @Test
    fun hasSafePairUrlRejectsNonHttpsSchemes() {
        assertFalse(PairingQr(pairUrl = "http://cmux.example.com/devices/pair").hasSafePairUrl())
        assertFalse(PairingQr(pairUrl = "file:///etc/passwd").hasSafePairUrl())
        assertFalse(PairingQr(pairUrl = "not a url").hasSafePairUrl())
    }

    @Test
    fun hasSafePairUrlRejectsUserinfo() {
        assertFalse(PairingQr(pairUrl = "https://user:pass@cmux.example.com/devices/pair").hasSafePairUrl())
    }

    @Test
    fun hasSafePairUrlRejectsMissingHost() {
        assertFalse(PairingQr(pairUrl = "https:///devices/pair").hasSafePairUrl())
    }

    @Test
    fun isExpiredTrueForPastTimestamp() {
        val qr = PairingQr(
            pairUrl = "u",
            code = "c",
            agentPubkey = "p",
            expiresAt = "2000-01-01T00:00:00Z",
            tenantId = "t"
        )
        assertTrue(qr.isExpired())
    }

    @Test
    fun isExpiredFalseForFutureTimestamp() {
        val future = Instant.now().plusSeconds(600).toString()
        val qr = PairingQr(pairUrl = "u", code = "c", agentPubkey = "p", expiresAt = future, tenantId = "t")
        assertFalse(qr.isExpired())
    }

    @Test
    fun isExpiredFalseForUnparseableTimestamp() {
        // Can't tell -- don't block a possibly-valid code on our own parse failure;
        // the server's 410 pairing_code_invalid is the authoritative check.
        val qr = PairingQr(pairUrl = "u", code = "c", agentPubkey = "p", expiresAt = "garbage", tenantId = "t")
        assertFalse(qr.isExpired())
    }
}
