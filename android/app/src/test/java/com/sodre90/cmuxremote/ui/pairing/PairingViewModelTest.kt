package com.sodre90.cmuxremote.ui.pairing

import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.PairingGateway
import com.sodre90.cmuxremote.data.pairing.PairingCodeInvalidException
import com.sodre90.cmuxremote.data.pairing.PairingQr
import com.sodre90.cmuxremote.data.pairing.PairingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** A [PairingSession] whose prepare/commit/resolveManualCode behavior is
 *  fully injected -- lets these tests drive [PairingViewModel]'s state
 *  machine without any real network, Keystore, or crypto dependency. */
private class FakePairingSession(
    private val onPrepare: (PairingQr) -> String = { "FAKE-FINGERPRINT" },
    private val onCommit: suspend (PairingQr) -> Unit = {},
    private val onResolveManualCode: suspend (String, String) -> PairingQr = { _, _ ->
        error("resolveManualCode not stubbed")
    },
) : PairingSession {
    var prepareCallCount = 0
        private set
    var commitCallCount = 0
        private set

    override fun prepare(qr: PairingQr): String {
        prepareCallCount++
        return onPrepare(qr)
    }

    override suspend fun commit(qr: PairingQr) {
        commitCallCount++
        onCommit(qr)
    }

    override suspend fun resolveManualCode(serverUrl: String, code: String): PairingQr =
        onResolveManualCode(serverUrl, code)
}

private class FakePairingGateway(private val session: PairingSession) : PairingGateway {
    override fun pairingClient(slot: ConnectionSlot): PairingSession = session
}

class PairingViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun waitUntil(timeoutMs: Long = 3_000, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met within ${timeoutMs}ms", block())
    }

    // Real crypto/expiry isn't exercised here -- FakePairingSession.prepare
    // stubs the fingerprint, so agent_pubkey only needs to be valid base64.
    private fun validQrJson(code: String = "CODE1") =
        """{"pair_url":"https://relay.example.com/devices/pair","code":"$code",""" +
            """"agent_pubkey":"QUJD","expires_at":"2099-01-01T00:00:00Z","tenant_id":"t1"}"""

    private fun testViewModel(session: PairingSession) = PairingViewModel(
        pairing = FakePairingGateway(session),
        slot = ConnectionSlot.RELAY,
        codeExpiredMessage = "expired",
        codeInvalidScanAgainMessage = "invalid-scan-again",
        codeInvalidAskFreshMessage = "invalid-ask-fresh",
        pairingFailedMessage = "pairing-failed",
    )

    @Test
    fun scanningAValidQrMovesToAwaitingConfirmationWithoutCommitting() {
        val session = FakePairingSession(onPrepare = { "ABCD-1234-EF56" })
        val vm = testViewModel(session)

        vm.onQrScanned(validQrJson())

        assertEquals(PairingUiState.AwaitingConfirmation("ABCD-1234-EF56"), vm.state.value)
        assertEquals(1, session.prepareCallCount)
        assertEquals(0, session.commitCallCount)
    }

    @Test
    fun confirmingCommitsAndTransitionsToSuccessCarryingTheSameFingerprint() {
        val session = FakePairingSession(onPrepare = { "ABCD-1234-EF56" })
        val vm = testViewModel(session)
        vm.onQrScanned(validQrJson())

        vm.onConfirmed()

        waitUntil { vm.state.value == PairingUiState.Success("ABCD-1234-EF56") }
        assertEquals(1, session.commitCallCount)
    }

    @Test
    fun rejectingReturnsToScanningWithoutCommitting() {
        val session = FakePairingSession()
        val vm = testViewModel(session)
        vm.onQrScanned(validQrJson())

        vm.onRejected()

        assertEquals(PairingUiState.Scanning, vm.state.value)
        assertEquals(0, session.commitCallCount)
    }

    @Test
    fun rejectingIsANoOpWhenNotAwaitingConfirmation() {
        val session = FakePairingSession()
        val vm = testViewModel(session)

        vm.onRejected()

        assertEquals(PairingUiState.Scanning, vm.state.value)
    }

    @Test
    fun confirmingIsANoOpWhenNotAwaitingConfirmation() {
        val session = FakePairingSession()
        val vm = testViewModel(session)

        vm.onConfirmed()

        assertEquals(PairingUiState.Scanning, vm.state.value)
        assertEquals(0, session.commitCallCount)
    }

    @Test
    fun confirmingAfterQrScanUsesTheScanAgainMessageOnInvalidCode() {
        val session = FakePairingSession(onCommit = { throw PairingCodeInvalidException() })
        val vm = testViewModel(session)
        vm.onQrScanned(validQrJson())

        vm.onConfirmed()

        waitUntil { vm.state.value is PairingUiState.Error }
        assertEquals(PairingUiState.Error("invalid-scan-again"), vm.state.value)
    }

    @Test
    fun confirmingAfterManualEntryUsesTheAskFreshMessageOnInvalidCode() {
        val resolvedQr = PairingQr(
            pairUrl = "https://relay.example.com/devices/pair",
            code = "MANUAL01",
            agentPubkey = "QUJD",
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t1",
        )
        val session = FakePairingSession(
            onResolveManualCode = { _, _ -> resolvedQr },
            onCommit = { throw PairingCodeInvalidException() },
        )
        val vm = testViewModel(session)
        vm.onManualEntrySubmitted("https://relay.example.com", "MANUAL01")
        waitUntil { vm.state.value is PairingUiState.AwaitingConfirmation }

        vm.onConfirmed()

        waitUntil { vm.state.value is PairingUiState.Error }
        assertEquals(PairingUiState.Error("invalid-ask-fresh"), vm.state.value)
    }

    @Test
    fun manualEntryComputesFingerprintBeforeConfirmation() {
        val resolvedQr = PairingQr(
            pairUrl = "https://relay.example.com/devices/pair",
            code = "MANUAL01",
            agentPubkey = "QUJD",
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t1",
        )
        val session = FakePairingSession(
            onPrepare = { "MANUAL-FINGERPRINT" },
            onResolveManualCode = { serverUrl, code ->
                assertEquals("https://relay.example.com", serverUrl)
                assertEquals("MANUAL01", code)
                resolvedQr
            },
        )
        val vm = testViewModel(session)

        vm.onManualEntrySubmitted("https://relay.example.com", "MANUAL01")

        waitUntil { vm.state.value == PairingUiState.AwaitingConfirmation("MANUAL-FINGERPRINT") }
        assertEquals(0, session.commitCallCount)
    }

    @Test
    fun prepareFailureGoesStraightToErrorWithoutAwaitingConfirmation() {
        val session = FakePairingSession(onPrepare = { throw IOException("server address must be https://") })
        val vm = testViewModel(session)

        vm.onQrScanned(validQrJson())

        assertEquals(PairingUiState.Error("server address must be https://"), vm.state.value)
        assertEquals(0, session.commitCallCount)
    }

    @Test
    fun retryClearsPendingQrAndReturnsToScanning() {
        val session = FakePairingSession()
        val vm = testViewModel(session)
        vm.onQrScanned(validQrJson())

        vm.retry()

        assertEquals(PairingUiState.Scanning, vm.state.value)
        // onConfirmed after a retry has nothing pending, even if the state
        // machine were somehow re-entered -- pendingQr was cleared, not just
        // the visible state.
        vm.onConfirmed()
        assertEquals(0, session.commitCallCount)
    }
}
