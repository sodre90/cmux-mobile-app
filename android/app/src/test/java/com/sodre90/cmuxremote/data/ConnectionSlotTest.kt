package com.sodre90.cmuxremote.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSlotTest {

    @Test
    fun otherFlipsBetweenRelayAndDirect() {
        assertEquals(ConnectionSlot.DIRECT, ConnectionSlot.RELAY.other())
        assertEquals(ConnectionSlot.RELAY, ConnectionSlot.DIRECT.other())
    }

    @Test
    fun inferLegacySlotClassifiesTsNetHostAsDirect() {
        assertEquals(ConnectionSlot.DIRECT, inferLegacySlot("https://macbook.sokoke-draco.ts.net:8443"))
    }

    @Test
    fun inferLegacySlotClassifiesOtherHostsAsRelay() {
        assertEquals(ConnectionSlot.RELAY, inferLegacySlot("https://sodre-cmux.mywire.org"))
    }

    @Test
    fun inferLegacySlotClassifiesUnparseableUrlAsRelay() {
        assertEquals(ConnectionSlot.RELAY, inferLegacySlot("not a url"))
    }

    @Test
    fun inferLegacySlotRejectsHostsThatMerelyContainTsNetSubstring() {
        // Only an exact ".ts.net" host suffix counts as a real Tailscale
        // MagicDNS name -- a host that just happens to contain that text
        // elsewhere must not be misclassified.
        assertEquals(ConnectionSlot.RELAY, inferLegacySlot("https://ts.net.evil.example.com"))
    }
}
