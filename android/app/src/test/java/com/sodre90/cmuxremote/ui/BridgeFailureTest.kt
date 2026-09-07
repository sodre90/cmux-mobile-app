package com.sodre90.cmuxremote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeFailureTest {

    @Test fun readsTheAgentOfflineBodyTheBridgeActuallySends() {
        assertEquals(
            BridgeFailure.AgentOffline,
            classifyBridgeFailure("""bridge HTTP 503: {"error":"agent_offline"}"""),
        )
    }

    @Test fun treatsABare503AsTheAgentBeingOffline() {
        assertEquals(BridgeFailure.AgentOffline, classifyBridgeFailure("bridge HTTP 503: "))
    }

    @Test fun mapsAuthFailuresToUnpaired() {
        assertEquals(BridgeFailure.Unauthorized, classifyBridgeFailure("bridge HTTP 401: unauthorized"))
        assertEquals(BridgeFailure.Unauthorized, classifyBridgeFailure("bridge HTTP 403: forbidden"))
    }

    @Test fun mapsTransportFailuresToUnreachable() {
        listOf(
            "timeout",
            "Read timed out",
            "Unable to resolve host \"mac.tail1234.ts.net\"",
            "Failed to connect to /100.64.0.1:8443",
            "connect failed: ECONNREFUSED (Connection refused)",
            "connect failed: Network is unreachable",
            "No route to host",
        ).forEach { assertEquals(it, BridgeFailure.Unreachable, classifyBridgeFailure(it)) }
    }

    @Test fun recognisesTheNotConfiguredFallbackTheViewModelsInject() {
        assertEquals(BridgeFailure.NotConfigured, classifyBridgeFailure("Bridge not configured"))
    }

    @Test fun anythingElseFallsThroughToUnknown() {
        assertEquals(BridgeFailure.Unknown, classifyBridgeFailure("bridge HTTP 500: boom"))
        assertEquals(BridgeFailure.Unknown, classifyBridgeFailure(""))
    }

    @Test fun classifiesRegardlessOfCase() {
        assertEquals(BridgeFailure.AgentOffline, classifyBridgeFailure("BRIDGE HTTP 503: AGENT_OFFLINE"))
    }
}
