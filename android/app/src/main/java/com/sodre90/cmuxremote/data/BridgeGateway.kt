package com.sodre90.cmuxremote.data

import kotlinx.coroutines.flow.StateFlow

/**
 * The bridge-networking surface ViewModels consume -- narrow enough to fake
 * in a plain JVM test, unlike the concrete [AppContainer] (EncryptedSharedPreferences
 * + Keystore I/O in its init). [AppContainer] is the sole real implementation.
 */
interface BridgeGateway {
    fun activeBridge(): FallbackBridgeClient?

    /** True while any activity of this app is at least STARTED -- the signal
     *  streaming subscriptions use to pause themselves when the user leaves
     *  the app, so an idle phone stops paying keepalive pings and event-driven
     *  refetch traffic on cellular. Push (FCM) covers attention while paused. */
    fun appForeground(): StateFlow<Boolean>
    fun anyBridgeConfigured(): Boolean
    fun eventsSocket(slot: ConnectionSlot): EventsSocket?
    fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket?

    /** The process-wide [RelayHealth] instance shared with [activeBridge]'s
     *  [FallbackBridgeClient], so every reconnecting socket subscription
     *  (see [SocketReconnector]) and the REST fallback path learn "relay is
     *  down" from the same source. */
    fun relayHealth(): RelayHealth

    /** The process-wide [ConnectionMonitor] the REST path and every socket
     *  subscription report into, so the UI can show which transport is in
     *  use and when it is falling back. */
    fun connectionMonitor(): ConnectionMonitor

    /** The process-wide [SlotCredentials] every socket subscription watches,
     *  so a slot that is forgotten or re-paired drops the connections still
     *  running on its previous credentials. */
    fun slotCredentials(): SlotCredentials

    /** The process-wide [SlotCredentialHealth], so the Connections screen can
     *  show a slot whose credential a server has rejected while the other
     *  slot is still serving -- which is the only moment the user can still
     *  do something about it. */
    fun slotCredentialHealth(): SlotCredentialHealth
}
