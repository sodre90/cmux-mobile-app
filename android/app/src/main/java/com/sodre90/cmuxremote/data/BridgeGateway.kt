package com.sodre90.cmuxremote.data

/**
 * The bridge-networking surface ViewModels consume -- narrow enough to fake
 * in a plain JVM test, unlike the concrete [AppContainer] (EncryptedSharedPreferences
 * + Keystore I/O in its init). [AppContainer] is the sole real implementation.
 */
interface BridgeGateway {
    fun activeBridge(): FallbackBridgeClient?
    fun anyBridgeConfigured(): Boolean
    fun eventsSocket(slot: ConnectionSlot): EventsSocket?
    fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket?

    /** The process-wide [RelayHealth] instance shared with [activeBridge]'s
     *  [FallbackBridgeClient], so every reconnecting socket subscription
     *  (see [SocketReconnector]) and the REST fallback path learn "relay is
     *  down" from the same source. */
    fun relayHealth(): RelayHealth
}
