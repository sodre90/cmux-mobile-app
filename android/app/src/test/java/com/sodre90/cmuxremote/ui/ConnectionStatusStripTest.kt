package com.sodre90.cmuxremote.ui

import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStatusStripTest {

    @Test
    fun unknownRendersNothing() {
        assertNull(connectionStatusStrip(ConnectionStatus.Unknown))
    }

    @Test
    fun relayReadsAsTheNormalCase() {
        val connecting = connectionStatusStrip(ConnectionStatus.Connecting(ConnectionSlot.RELAY))!!
        assertEquals(R.string.connection_connecting_relay, connecting.labelRes)
        assertEquals(ConnectionTone.NORMAL, connecting.tone)
        assertEquals(true, connecting.busy)

        val connected = connectionStatusStrip(ConnectionStatus.Connected(ConnectionSlot.RELAY))!!
        assertEquals(R.string.connection_connected_relay, connected.labelRes)
        assertEquals(ConnectionTone.NORMAL, connected.tone)
        assertEquals(false, connected.busy)
    }

    @Test
    fun directAlwaysReadsAsAFallback() {
        val connecting = connectionStatusStrip(ConnectionStatus.Connecting(ConnectionSlot.DIRECT))!!
        assertEquals(R.string.connection_connecting_direct, connecting.labelRes)
        assertEquals(ConnectionTone.FALLBACK, connecting.tone)

        val connected = connectionStatusStrip(ConnectionStatus.Connected(ConnectionSlot.DIRECT))!!
        assertEquals(R.string.connection_connected_direct, connected.labelRes)
        assertEquals(ConnectionTone.FALLBACK, connected.tone)
    }

    // The whole point of the strip: "relay was skipped, not tried" must not
    // read the same as "relay was tried and failed" -- the first is the
    // penalty window, and is the answer to "why is it on Tailscale?".
    @Test
    fun fallingBackDistinguishesASkippedRelayFromAFailedOne() {
        val skipped = connectionStatusStrip(ConnectionStatus.FallingBack(reason = null))!!
        assertEquals(R.string.connection_relay_skipped, skipped.labelRes)
        assertNull(skipped.detail)

        val failed = connectionStatusStrip(ConnectionStatus.FallingBack(reason = "no route to host"))!!
        assertEquals(R.string.connection_falling_back, failed.labelRes)
        assertEquals("no route to host", failed.detail)
    }

    @Test
    fun failedCarriesBothCausesThroughAsDetail() {
        val model = connectionStatusStrip(
            ConnectionStatus.Failed("Relay: timeout • Direct: no route to host"),
        )!!
        assertEquals(R.string.connection_failed, model.labelRes)
        assertEquals(ConnectionTone.ERROR, model.tone)
        assertEquals("Relay: timeout • Direct: no route to host", model.detail)
    }
}
