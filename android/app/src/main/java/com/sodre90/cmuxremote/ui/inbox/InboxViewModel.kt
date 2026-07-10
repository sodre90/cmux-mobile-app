package com.sodre90.cmuxremote.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.SocketReconnector
import com.sodre90.cmuxremote.model.EventFrame
import com.sodre90.cmuxremote.model.FeedReply
import com.sodre90.cmuxremote.model.PendingFeedItem
import com.sodre90.cmuxremote.ui.UiState
import com.sodre90.cmuxremote.ui.sessions.pendingItemTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * Backs the agent inbox. Pending blocking prompts come from `GET /feed/pending`
 * (cmux `feed.list`), which carries the real `request_id` and the choosable
 * options the user must pick from — the event stream has neither. The live
 * `/events` socket is used only as a trigger to re-fetch when a new prompt
 * appears; the prompt content always comes from a fresh pending-feed fetch.
 *
 * The error-message parameters are pre-resolved `strings.xml` text passed in
 * by the caller (see CmuxNavHost) rather than resolved here: a ViewModel has
 * no @Composable context to call `stringResource()` itself.
 */
class InboxViewModel(
    bridge: BridgeGateway,
    private val bridgeNotConfiguredMessage: String,
    private val loadInboxFailedMessage: String,
    private val replyFailedMessage: String,
    private val terminalNotFoundMessage: String,
) : ViewModel() {

    private val client = bridge.activeBridge()

    // [UiState.Error] is never set here (see [refresh]) -- kept as the shared
    // UiState<T> type for consistency with the other screens, but InboxScreen
    // deliberately renders Loading and Error the same as an empty Ready list
    // (see its doc comment) so this internal-modeling unification introduces
    // no observable UI change: a failed fetch always surfaced as an error
    // banner over the (possibly still-empty) list before this refactor too,
    // never a full loading spinner or a full-screen error page.
    private val _state = MutableStateFlow<UiState<List<PendingFeedItem>>>(UiState.Loading)
    val state: StateFlow<UiState<List<PendingFeedItem>>> = _state.asStateFlow()

    // Surfaced separately from [state] so a failed refresh/reply doesn't blow
    // away an already-loaded list (mirrors SessionsViewModel's actionError).
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val reconnector = SocketReconnector<EventFrame>(bridge.relayHealth())

    init {
        refresh()
        // Re-fetch when an agent newly needs attention. Telemetry feed events
        // (PreToolUse, etc.) are ignored so we don't hammer feed.list. The
        // socket is dropped when the app is backgrounded, so reconnect with
        // backoff and re-sync pending items after each gap instead of dying on
        // the first disconnect.
        if (bridge.anyBridgeConfigured()) {
            viewModelScope.launch {
                reconnector.run(
                    openSocket = { slot -> bridge.eventsSocket(slot)?.connect() },
                    onBeforeReconnect = { refresh() },
                ) { frame ->
                    if (frame.type == "feed" && frame.needsAttention) refresh()
                    true
                }
            }
        }
    }

    fun refresh() {
        val c = client ?: run {
            _actionError.value = bridgeNotConfiguredMessage
            return
        }
        viewModelScope.launch {
            try {
                // "question" (AskUserQuestion) is the only replyable kind cmux
                // currently surfaces as a pending feed item.
                val items = c.pendingFeed().filter { it.kind == "question" }
                _state.value = UiState.Ready(items)
                _actionError.value = null
            } catch (ex: Exception) {
                // Never demotes [state] to Error, whether or not a list was
                // already showing -- see [_state]'s doc comment for why.
                _actionError.value = ex.message ?: loadInboxFailedMessage
            }
        }
    }

    /** Answer a question item with the labels of the chosen options. */
    fun reply(item: PendingFeedItem, selections: List<String>) {
        val c = client ?: run {
            _actionError.value = bridgeNotConfiguredMessage
            return
        }
        val params = buildJsonObject {
            putJsonArray("selections") { selections.forEach { add(it) } }
        }
        viewModelScope.launch {
            try {
                c.replyFeed(item.id, FeedReply("question", item.requestId, params))
                _state.update { cur ->
                    if (cur is UiState.Ready) UiState.Ready(cur.data.filterNot { it.id == item.id }) else cur
                }
            } catch (ex: Exception) {
                _actionError.value = ex.message ?: replyFailedMessage
            }
        }
    }

    /**
     * Resolves [item]'s originating terminal surface id for the inbox row's
     * "open terminal" affordance, or null (with [actionError] set) if none is
     * found. [PendingFeedItem] carries no workspace/surface id of its own (see
     * [pendingItemTarget]'s doc comment), so this always does a fresh live
     * lookup rather than reusing [state].
     */
    suspend fun terminalTarget(item: PendingFeedItem): String? {
        val c = client ?: run {
            _actionError.value = bridgeNotConfiguredMessage
            return null
        }
        val target = runCatching { c.sessions() }.getOrNull()?.let { pendingItemTarget(item, it) }
        if (target == null) _actionError.value = terminalNotFoundMessage
        return target
    }
}
