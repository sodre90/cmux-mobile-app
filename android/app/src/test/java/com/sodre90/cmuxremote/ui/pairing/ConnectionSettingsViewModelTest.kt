package com.sodre90.cmuxremote.ui.pairing

import com.sodre90.cmuxremote.data.BridgeClient
import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.ConnectionMonitor
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.EventsSocket
import com.sodre90.cmuxremote.data.FallbackBridgeClient
import com.sodre90.cmuxremote.data.RelayHealth
import com.sodre90.cmuxremote.data.TerminalDisplayGateway
import com.sodre90.cmuxremote.data.TerminalSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** A [BridgeGateway] that hands out [bridge] for [activeBridge] and stubs
 *  everything else -- these tests only exercise [ConnectionSettingsViewModel],
 *  which only ever calls [BridgeGateway.activeBridge]. */
private class FakeTestPushBridgeGateway(private val bridge: FallbackBridgeClient?) : BridgeGateway {
    val monitor = ConnectionMonitor()
    override fun activeBridge(): FallbackBridgeClient? = bridge
    override fun anyBridgeConfigured(): Boolean = bridge != null
    override fun eventsSocket(slot: ConnectionSlot): EventsSocket? = null
    override fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket? = null
    override fun relayHealth(): RelayHealth = RelayHealth()
    override fun connectionMonitor(): ConnectionMonitor = monitor
}

/** These tests only exercise the test-push flow, never font zoom -- a fixed
 *  stub is enough. */
private class FakeTerminalDisplayGateway : TerminalDisplayGateway {
    override fun loadFontZoom(): Float = 1f
    override fun saveFontZoom(zoom: Float) = Unit
}

class ConnectionSettingsViewModelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
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

    private fun bridgeFor(server: MockWebServer) = FallbackBridgeClient(
        primary = { BridgeClient(OkHttpClient(), server.url("/").toString()) },
        fallback = { null },
    )

    // Matches the exact strings.xml text each error path falls back to (see
    // CmuxNavHost's SETTINGS route) so assertions below can keep checking the
    // literal message.
    private fun testPushViewModel(bridge: BridgeGateway) = ConnectionSettingsViewModel(
        bridge = bridge,
        terminalDisplay = FakeTerminalDisplayGateway(),
        bridgeNotConfiguredMessage = "Bridge not configured",
        testPushFailedMessage = "Test push failed",
    )

    @Test
    fun initialStateIsIdle() {
        val vm = testPushViewModel(FakeTestPushBridgeGateway(null))

        assertEquals(TestPushUiState.Idle, vm.testPushState.value)
    }

    @Test
    fun noBridgeConfiguredSetsErrorImmediatelyWithNoNetworkCall() {
        val vm = testPushViewModel(FakeTestPushBridgeGateway(null))

        vm.sendTestPush()

        assertEquals(TestPushUiState.Error("Bridge not configured"), vm.testPushState.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun successfulSendTransitionsToSuccess() {
        server.enqueue(MockResponse())
        val vm = testPushViewModel(FakeTestPushBridgeGateway(bridgeFor(server)))

        vm.sendTestPush()

        waitUntil { vm.testPushState.value is TestPushUiState.Success }
        val recorded = server.takeRequest()
        assertEquals("/devices/test-push", recorded.path)
        assertEquals("POST", recorded.method)
    }

    @Test
    fun failureSurfacesTheActualErrorMessageNotAGenericOne() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"agent_offline"}"""))
        val vm = testPushViewModel(FakeTestPushBridgeGateway(bridgeFor(server)))

        vm.sendTestPush()

        waitUntil { vm.testPushState.value is TestPushUiState.Error }
        val error = vm.testPushState.value as TestPushUiState.Error
        assertTrue("expected the real status code in the message, got: ${error.message}", error.message.contains("503"))
        assertTrue(
            "expected the real error body in the message, got: ${error.message}",
            error.message.contains("agent_offline"),
        )
    }

    @Test
    fun stateIsSendingWhileTheCallIsInFlight() {
        server.enqueue(MockResponse().setBodyDelay(200, TimeUnit.MILLISECONDS))
        val vm = testPushViewModel(FakeTestPushBridgeGateway(bridgeFor(server)))

        vm.sendTestPush()

        assertEquals(TestPushUiState.Sending, vm.testPushState.value)
        waitUntil { vm.testPushState.value !is TestPushUiState.Sending }
    }

    @Test
    fun aSecondCallWhileSendingIsIgnored() {
        server.enqueue(MockResponse().setBodyDelay(300, TimeUnit.MILLISECONDS))
        val vm = testPushViewModel(FakeTestPushBridgeGateway(bridgeFor(server)))

        vm.sendTestPush()
        vm.sendTestPush() // ignored -- already sending

        waitUntil { vm.testPushState.value is TestPushUiState.Success }
        assertEquals(1, server.requestCount)
    }
}
