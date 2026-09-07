package com.sodre90.cmuxremote.ui

import androidx.annotation.StringRes
import com.sodre90.cmuxremote.R

/**
 * What went wrong behind a [UiState.Error], as far as the user needs to care.
 *
 * The ViewModels put the raw throwable message into [UiState.Error] -- for a
 * BridgeException that reads `bridge HTTP 503: {"error":"agent_offline"}`, which
 * tells the user nothing and looks like a crash. Classifying happens here, at the
 * UI layer, because that is where `stringResource` exists; a ViewModel would need
 * every one of these strings pre-resolved through its constructor.
 */
enum class BridgeFailure(@StringRes val message: Int) {
    AgentOffline(R.string.error_bridge_agent_offline),
    Unauthorized(R.string.error_bridge_unauthorized),
    Unreachable(R.string.error_bridge_unreachable),
    Unknown(R.string.error_bridge_unknown),
}

/**
 * Maps a raw error message onto the sentence to show for it.
 *
 * Matching is on text rather than a typed exception because the message has
 * already been flattened to a String by the time it reaches [UiState.Error], and
 * widening that would mean threading the throwable through every ViewModel.
 * Anything unrecognised falls through to [BridgeFailure.Unknown], whose raw text
 * stays available behind the Details expander.
 */
fun classifyBridgeFailure(raw: String): BridgeFailure {
    val text = raw.lowercase()
    return when {
        "agent_offline" in text || "bridge http 503" in text -> BridgeFailure.AgentOffline
        "bridge http 401" in text || "bridge http 403" in text -> BridgeFailure.Unauthorized
        UnreachableMarkers.any { it in text } -> BridgeFailure.Unreachable
        else -> BridgeFailure.Unknown
    }
}

private val UnreachableMarkers = listOf(
    "timeout",
    "timed out",
    "unable to resolve host",
    "failed to connect",
    "connection refused",
    "econnrefused",
    "network is unreachable",
    "no route to host",
)
