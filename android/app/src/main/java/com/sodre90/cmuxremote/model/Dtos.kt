package com.sodre90.cmuxremote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Shared JSON codec for every value exchanged with the cmux bridge.
 *
 * - [ignoreUnknownKeys]: the bridge (and cmux behind it) may grow fields; the
 *   client must keep parsing older shapes.
 * - [explicitNulls] = false: omit null fields when we encode upstream messages.
 */
val BridgeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** A cmux workspace as surfaced by the bridge `GET /sessions` endpoint. */
@Serializable
data class Workspace(
    val id: String = "",
    val cwd: String = "",
    val title: String = "",
    val preview: String = "",
    @SerialName("has_unread") val hasUnread: Boolean = false,
    /** Agent attention state derived by the bridge: "permission", "input", or "". */
    val attention: String = "",
    val terminals: List<TerminalPane> = emptyList(),
)

/** One terminal surface (pane) within a [Workspace]; [id] opens via /terminal/{id}. */
@Serializable
data class TerminalPane(
    val id: String = "",
    val cwd: String = "",
    val title: String = "",
    val focused: Boolean = false,
    val ready: Boolean = false,
    val kind: String = "",
)

/** Envelope returned by `GET /sessions`. */
@Serializable
data class WorkspacesResponse(val workspaces: List<Workspace> = emptyList())

/** Body of `POST /devices/register`. */
@Serializable
data class RegisterDeviceRequest(@SerialName("fcm_token") val fcmToken: String)

/** A simplified event the bridge fans out over the events WebSocket. */
@Serializable
data class EventFrame(
    val type: String = "",
    val name: String = "",
    @SerialName("needs_attention") val needsAttention: Boolean = false,
    @SerialName("feed_id") val feedId: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("surface_id") val surfaceId: String? = null,
    val title: String? = null,
    val kind: String? = null,
)

/** A terminal frame pushed down to the client (replay snapshot or update). */
@Serializable
data class TerminalDown(
    val type: String = "",
    val grid: RenderGrid? = null,
    val columns: Int = 0,
    val rows: Int = 0,
    val seq: Long = 0L,
)

/** A terminal message sent up from the client: input, paste, or resize. */
@Serializable
data class TerminalUp(
    val type: String,
    val text: String? = null,
    val columns: Int? = null,
    val rows: Int? = null,
)

/** A reply to a pending feed item (permission / question / exit-plan). */
@Serializable
data class FeedReply(
    val kind: String,
    @SerialName("request_id") val requestId: String,
    val params: JsonObject,
)

/** One selectable choice within a [FeedQuestion]. cmux replies with the [label]. */
@Serializable
data class FeedOption(
    val id: String = "",
    val label: String = "",
    val description: String = "",
)

/** A single question inside an AskUserQuestion prompt; an item may carry several. */
@Serializable
data class FeedQuestion(
    val id: String = "",
    val header: String = "",
    val prompt: String = "",
    @SerialName("multi_select") val multiSelect: Boolean = false,
    val options: List<FeedOption> = emptyList(),
)

/**
 * A pending blocking prompt from `GET /feed/pending` (cmux `feed.list pending_only`).
 * [requestId] is what [FeedReply] must echo back — not the event feed id. For
 * `kind == "question"` the reply is `selections`: the chosen options' labels.
 */
@Serializable
data class PendingFeedItem(
    val id: String = "",
    @SerialName("request_id") val requestId: String = "",
    val kind: String = "",
    val status: String = "",
    val title: String = "",
    val cwd: String = "",
    @SerialName("workstream_id") val workstreamId: String = "",
    @SerialName("question_multi_select") val questionMultiSelect: Boolean = false,
    @SerialName("question_options") val questionOptions: List<FeedOption> = emptyList(),
    val questions: List<FeedQuestion> = emptyList(),
)

/** Envelope returned by `GET /feed/pending`. */
@Serializable
data class PendingFeedResponse(val items: List<PendingFeedItem> = emptyList())
