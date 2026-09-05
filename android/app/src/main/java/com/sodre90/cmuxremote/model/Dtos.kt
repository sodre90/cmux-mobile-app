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
    /** Agent attention state derived by the bridge (see [Attention]); "" means none. */
    val attention: String = "",
    /** This workspace's YOLO auto-reply mode (see [YoloMode]); "" means off. */
    @SerialName("yolo_mode") val yoloMode: String = "",
    /** The workspace's user-picked color in cmux (`#rrggbb`); "" when unset.
     *  Rendered as an identifying dot on the sessions card. */
    @SerialName("custom_color") val customColor: String = "",
    val terminals: List<TerminalPane> = emptyList(),
)

/**
 * [Workspace.attention]'s possible values -- the agent-attention state the
 * bridge derives per workspace: blocked on a permission prompt, idle waiting
 * for input, or [NONE].
 */
object Attention {
    const val NONE = ""
    const val PERMISSION = "permission"
    const val INPUT = "input"
}

/**
 * YOLO mode's auto-reply levels for permission prompts. The bridge persists
 * these per workspace and replies to pending `permission`-kind feed items
 * with them automatically (see bridge's internal/yolo package); [BYPASS]
 * mirrors Claude Code's own `--dangerously-skip-permissions`.
 */
object YoloMode {
    const val OFF = ""
    const val ALWAYS = "always"
    const val ALL_TOOLS = "all"
    const val BYPASS = "bypass"
}

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

/** Body of `POST /sessions/{id}/rename`. */
@Serializable
data class RenameWorkspaceRequest(val title: String)

/** Body of `POST /sessions/{id}/yolo-mode`; [mode] is one of [YoloMode]'s values. */
@Serializable
data class SetYoloModeRequest(val mode: String)

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

/**
 * A terminal frame pushed down to the client: a [TerminalDownType.REPLAY]/
 * [TerminalDownType.OUTPUT] render-grid snapshot, or an [TerminalDownType.ACK]
 * echoing a [TerminalUp.seq] back with [ok] reflecting whether the bridge's
 * cmux RPC for that message actually succeeded.
 */
@Serializable
data class TerminalDown(
    val type: String = "",
    val grid: RenderGrid? = null,
    val columns: Int = 0,
    val rows: Int = 0,
    val seq: Long = 0L,
    val ok: Boolean = false,
)

/**
 * [TerminalDown.type]'s possible values. Plain `String` (not an enum) on the
 * DTO: an unrecognized future frame type must still decode instead of
 * failing the whole socket, matching TerminalViewModel's existing
 * fallthrough (anything that isn't [ACK] is treated as a render-grid frame
 * if it carries one, ignored otherwise).
 */
object TerminalDownType {
    const val ACK = "ack"
    const val REPLAY = "replay"
    const val OUTPUT = "output"
}

/**
 * A terminal message sent up from the client: [TerminalUpType.INPUT] or
 * [TerminalUpType.RESIZE]. [seq] is a client-assigned monotonic id (starting
 * at 1; 0 means unset) echoed back in the matching [TerminalDownType.ACK]
 * [TerminalDown].
 */
@Serializable
data class TerminalUp(
    val type: String,
    val text: String? = null,
    val columns: Int? = null,
    val rows: Int? = null,
    val seq: Long = 0L,
)

/** [TerminalUp.type]'s possible values -- what this client ever sends. */
object TerminalUpType {
    const val INPUT = "input"
    const val RESIZE = "resize"
}

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
 * For `kind == "permissionRequest"` there is no questions[]/options[] — instead
 * [toolName]/[toolInput] describe the gated tool call, and the reply is a
 * `mode` of `once` or `deny` (confirmed live against `feed.permission.reply`;
 * `always`/`all`/`bypass` are the same enum's YOLO auto-modes, see [YoloMode]).
 * [toolInput] is cmux's own JSON-encoded string of the tool's raw args (shape
 * varies per tool), not a typed object.
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
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("tool_input") val toolInput: String = "",
)

/** Envelope returned by `GET /feed/pending`. */
@Serializable
data class PendingFeedResponse(val items: List<PendingFeedItem> = emptyList())
