package com.sodre90.cmuxremote.ui.inbox

import com.sodre90.cmuxremote.data.BridgeClient
import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.EventsSocket
import com.sodre90.cmuxremote.data.FallbackBridgeClient
import com.sodre90.cmuxremote.data.RelayHealth
import com.sodre90.cmuxremote.data.TerminalSocket
import com.sodre90.cmuxremote.ui.UiState
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

/** A [BridgeGateway] that always reports no configured slot for [anyBridgeConfigured]
 *  (so InboxViewModel's events-reconnect loop never starts -- these tests are
 *  only about [InboxViewModel.state]/[InboxViewModel.actionError] transitions,
 *  not the reconnect machinery already covered by SocketReconnectorTest) while
 *  still handing out a real [bridge] for reads/replies. */
private class FakeInboxBridgeGateway(private val bridge: FallbackBridgeClient?) : BridgeGateway {
    override fun activeBridge(): FallbackBridgeClient? = bridge
    override fun anyBridgeConfigured(): Boolean = false
    override fun eventsSocket(slot: ConnectionSlot): EventsSocket? = null
    override fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket? = null
    override fun relayHealth(): RelayHealth = RelayHealth()
}

/**
 * Covers the sealed-UiState rework of InboxViewModel: the previous
 * `_items`+`_error` split is now a single `state: StateFlow<UiState<List<PendingFeedItem>>>`
 * (internal typing only -- see [InboxViewModel]'s `_state` doc comment for
 * why this is deliberately *not* observably different: a fetch failure,
 * whether before or after anything has ever loaded, always surfaces through
 * [InboxViewModel.actionError] only, exactly like the old `_error` flow did,
 * never a full loading spinner or full-screen error page).
 */
class InboxViewModelTest {

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

    @Test
    fun bridgeNotConfiguredSetsActionErrorAndLeavesStateAtLoading() {
        val vm = InboxViewModel(FakeInboxBridgeGateway(null))
        assertEquals("Bridge not configured", vm.actionError.value)
        assertTrue(vm.state.value is UiState.Loading)
    }

    @Test
    fun firstLoadFailureSetsActionErrorAndLeavesStateAtLoading() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val vm = InboxViewModel(FakeInboxBridgeGateway(bridgeFor(server)))

        waitUntil { vm.actionError.value != null }

        assertTrue(vm.state.value is UiState.Loading)
    }

    @Test
    fun successfulLoadPopulatesReadyState() {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"i1","kind":"question"}]}"""))
        val vm = InboxViewModel(FakeInboxBridgeGateway(bridgeFor(server)))

        waitUntil { vm.state.value is UiState.Ready }

        val ready = vm.state.value as UiState.Ready
        assertEquals(listOf("i1"), ready.data.map { it.id })
    }

    @Test
    fun nonQuestionKindItemsAreFilteredOut() {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"i1","kind":"question"},{"id":"i2","kind":"permissionRequest"}]}""",
            ),
        )
        val vm = InboxViewModel(FakeInboxBridgeGateway(bridgeFor(server)))

        waitUntil { vm.state.value is UiState.Ready }

        assertEquals(listOf("i1"), (vm.state.value as UiState.Ready).data.map { it.id })
    }

    @Test
    fun aRefreshFailureAfterASuccessfulLoadKeepsTheStaleListAndSetsActionError() {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"i1","kind":"question"}]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val vm = InboxViewModel(FakeInboxBridgeGateway(bridgeFor(server)))
        waitUntil { vm.state.value is UiState.Ready }

        vm.refresh()
        waitUntil { vm.actionError.value != null }

        val state = vm.state.value
        assertTrue(state is UiState.Ready)
        assertEquals(listOf("i1"), (state as UiState.Ready).data.map { it.id })
    }

    @Test
    fun replySuccessRemovesTheItemFromTheReadyList() {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[
                    {"id":"i1","kind":"question","request_id":"r1"},
                    {"id":"i2","kind":"question","request_id":"r2"}
                ]}""",
            ),
        )
        server.enqueue(MockResponse()) // POST /feed/i1/reply
        val vm = InboxViewModel(FakeInboxBridgeGateway(bridgeFor(server)))
        waitUntil { vm.state.value is UiState.Ready }
        val item = (vm.state.value as UiState.Ready).data.first { it.id == "i1" }

        vm.reply(item, listOf("yes"))

        waitUntil { (vm.state.value as? UiState.Ready)?.data?.map { it.id } == listOf("i2") }
    }

    @Test
    fun replyFailureSetsActionErrorAndLeavesTheListUntouched() {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"i1","kind":"question","request_id":"r1"}]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")) // POST /feed/i1/reply
        val vm = InboxViewModel(FakeInboxBridgeGateway(bridgeFor(server)))
        waitUntil { vm.state.value is UiState.Ready }
        val item = (vm.state.value as UiState.Ready).data.first()

        vm.reply(item, listOf("yes"))

        waitUntil { vm.actionError.value != null }
        assertEquals(listOf("i1"), (vm.state.value as UiState.Ready).data.map { it.id })
    }
}
