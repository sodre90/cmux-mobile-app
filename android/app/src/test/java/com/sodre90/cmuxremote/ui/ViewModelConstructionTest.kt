package com.sodre90.cmuxremote.ui

import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.ConnectionMonitor
import com.sodre90.cmuxremote.data.SlotCredentials
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.EventsSocket
import com.sodre90.cmuxremote.data.FallbackBridgeClient
import com.sodre90.cmuxremote.data.PairingGateway
import com.sodre90.cmuxremote.data.RelayHealth
import com.sodre90.cmuxremote.data.TerminalDisplayGateway
import com.sodre90.cmuxremote.data.TerminalSocket
import com.sodre90.cmuxremote.data.WorkspaceOrderGateway
import com.sodre90.cmuxremote.data.pairing.PairingClient
import com.sodre90.cmuxremote.ui.inbox.InboxViewModel
import com.sodre90.cmuxremote.ui.pairing.ConnectionSettingsViewModel
import com.sodre90.cmuxremote.ui.pairing.PairingViewModel
import com.sodre90.cmuxremote.ui.sessions.SessionsViewModel
import com.sodre90.cmuxremote.ui.terminal.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Proves the seam documented on [com.sodre90.cmuxremote.data.AppContainer]:
 * every ViewModel is constructible against a fake [BridgeGateway] /
 * [WorkspaceOrderGateway] / [PairingGateway] / [TerminalDisplayGateway], with
 * no Android Context, Keystore, or EncryptedSharedPreferences involved --
 * unlike the concrete AppContainer, which needs all four in its init. Deeper
 * behavior over these gateways belongs to each ViewModel's own test suite,
 * not here.
 */
class ViewModelConstructionTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeBridgeGateway : BridgeGateway {
        val monitor = ConnectionMonitor()
        override fun activeBridge(): FallbackBridgeClient? = null
        override fun anyBridgeConfigured(): Boolean = false
        override fun eventsSocket(slot: ConnectionSlot): EventsSocket? = null
        override fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket? = null
        override fun relayHealth(): RelayHealth = RelayHealth()
        override fun connectionMonitor(): ConnectionMonitor = monitor
        override fun slotCredentials(): SlotCredentials = SlotCredentials()
    }

    private class FakeWorkspaceOrderGateway : WorkspaceOrderGateway {
        private var order: List<String> = emptyList()
        private var sortByAttention = false
        override fun loadOrder(): List<String> = order
        override fun saveOrder(order: List<String>) {
            this.order = order
        }
        override fun loadSortByAttention(): Boolean = sortByAttention
        override fun saveSortByAttention(sortByAttention: Boolean) {
            this.sortByAttention = sortByAttention
        }
    }

    private class FakePairingGateway : PairingGateway {
        override fun pairingClient(slot: ConnectionSlot): PairingClient =
            error("not exercised by construction alone")
    }

    private class FakeTerminalDisplayGateway : TerminalDisplayGateway {
        private var zoom = 1f
        override fun loadFontZoom(): Float = zoom
        override fun saveFontZoom(zoom: Float) {
            this.zoom = zoom
        }
    }

    @Test
    fun terminalViewModelIsConstructibleWithAFakeGateway() {
        TerminalViewModel(
            FakeBridgeGateway(),
            FakeTerminalDisplayGateway(),
            surfaceId = "surface-1",
            bridgeNotConfiguredMessage = "unused",
        )
    }

    @Test
    fun sessionsViewModelIsConstructibleWithFakeGateways() {
        SessionsViewModel(
            FakeBridgeGateway(),
            FakeWorkspaceOrderGateway(),
            bridgeNotConfiguredMessage = "unused",
            renameFailedMessage = "unused",
            setYoloModeFailedMessage = "unused",
            loadSessionsFailedMessage = "unused",
            refreshSessionsFailedMessage = "unused",
        )
    }

    @Test
    fun inboxViewModelIsConstructibleWithAFakeGateway() {
        InboxViewModel(
            FakeBridgeGateway(),
            bridgeNotConfiguredMessage = "unused",
            loadInboxFailedMessage = "unused",
            replyFailedMessage = "unused",
            terminalNotFoundMessage = "unused",
        )
    }

    @Test
    fun connectionSettingsViewModelIsConstructibleWithFakeGateways() {
        ConnectionSettingsViewModel(
            FakeBridgeGateway(),
            FakeTerminalDisplayGateway(),
            bridgeNotConfiguredMessage = "unused",
            testPushFailedMessage = "unused",
        )
    }

    @Test
    fun pairingViewModelIsConstructibleWithAFakeGateway() {
        PairingViewModel(
            FakePairingGateway(),
            ConnectionSlot.RELAY,
            codeExpiredMessage = "unused",
            codeInvalidScanAgainMessage = "unused",
            codeInvalidAskFreshMessage = "unused",
            pairingFailedMessage = "unused",
        )
    }
}
