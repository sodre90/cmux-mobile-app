package com.sodre90.cmuxremote.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two slots are configured independently and direct push is documented as
 * optional, so "the bridge sent no config" must not be read as "delete the
 * config the other slot's bridge sent".
 */
class FcmConfigOwnershipTest {

    @Test
    fun `an unclaimed config may be cleared by either slot`() {
        assertTrue(mayClearFcmConfig(null, ConnectionSlot.RELAY))
        assertTrue(mayClearFcmConfig(null, ConnectionSlot.DIRECT))
    }

    @Test
    fun `the slot that supplied the config may clear it`() {
        assertTrue(mayClearFcmConfig(ConnectionSlot.RELAY.name, ConnectionSlot.RELAY))
        assertTrue(mayClearFcmConfig(ConnectionSlot.DIRECT.name, ConnectionSlot.DIRECT))
    }

    /** The regression: pairing a push-less direct agent after a push-enabled
     *  relay used to wipe the relay's config and kill push on both slots. */
    @Test
    fun `the other slot may not clear a config it did not supply`() {
        assertFalse(mayClearFcmConfig(ConnectionSlot.RELAY.name, ConnectionSlot.DIRECT))
        assertFalse(mayClearFcmConfig(ConnectionSlot.DIRECT.name, ConnectionSlot.RELAY))
    }
}
