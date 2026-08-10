package com.sodre90.cmuxremote.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.ConnectionStatus
import com.sodre90.cmuxremote.data.SocketReconnector
import com.sodre90.cmuxremote.model.EventFrame
import com.sodre90.cmuxremote.model.FeedReply
import com.sodre90.cmuxremote.model.PendingFeedItem
import com.sodre90.cmuxremote.ui.UiState
import com.sodre90.cmuxremote.ui.sessions.TerminalMatch
import com.sodre90.cmuxremote.ui.sessions.pendingItemTarget
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Backs the agent inbox. Pending blocking prompts come from `GET /feed/pending`
 * (cmux `feed.list`), which carries the real `request_id` and the choosable
 * options the user must pick from — the event stream has neither. The live
 * `/events` socket is used only as a trigger to re-fetch whenever the pending
 * set may have changed; the prompt content always comes from a fresh
 * pending-feed fetch.
 *
 * The error-message parameters are pre-resolved `strings.xml` text passed in
 * by the caller (see CmuxNavHost) rather than resolved here: a ViewModel has
 * no @Composable context to call `stringResource()` itself.
 */
@OptIn(FlowPreview::class)
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

    // Coalesces bursts of cmux feed events (two per tool call) into a single
    // refetch instead of hammering feed.list -- same trick as SessionsViewModel.
    private val refreshRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val reconnector = SocketReconnector<EventFrame>(
        bridge.relayHealth(),
        monitor = bridge.connectionMonitor(),
        slotCredentials = bridge.slotCredentials(),
    )

    /** Same process-wide transport status SessionsViewModel exposes -- replying
     *  to a prompt is the one action where knowing the connection is mid-
     *  failover actually changes what the user does. */
    val connectionStatus: StateFlow<ConnectionStatus> = bridge.connectionMonitor().status

    init {
        refresh()
        viewModelScope.launch {
            refreshRequests.debounce(EVENT_REFRESH_DEBOUNCE_MS).collect { refresh() }
        }
        // Re-fetch whenever the pending set may have changed -- see
        // [isPendingSetChangeSignal] for why that is every feed frame and not
        // just the attention-flagged ones. The socket is dropped when the app
        // is backgrounded, so reconnect with backoff and re-sync pending items
        // after each gap instead of dying on the first disconnect.
        if (bridge.anyBridgeConfigured()) {
            viewModelScope.launch {
                reconnector.run(
                    openSocket = { slot, onOpen -> bridge.eventsSocket(slot)?.connect(onOpen) },
                    onBeforeReconnect = { refresh() },
                ) { frame ->
                    if (isPendingSetChangeSignal(frame.type)) refreshRequests.tryEmit(Unit)
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
                val items = c.pendingFeed().filter { isPendingInboxKind(it.kind) }
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
        val params = buildJsonObject {
            putJsonArray("selections") { selections.forEach { add(it) } }
        }
        sendReply(item, "question", params)
    }

    /** Approve or deny a permissionRequest item once -- not a recurring
     *  YOLO auto-mode (see [com.sodre90.cmuxremote.model.YoloMode] for those). */
    fun replyPermission(item: PendingFeedItem, approve: Boolean) {
        val params = buildJsonObject { put("mode", if (approve) "once" else "deny") }
        sendReply(item, "permissionRequest", params)
    }

    private fun sendReply(item: PendingFeedItem, kind: String, params: JsonObject) {
        val c = client ?: run {
            _actionError.value = bridgeNotConfiguredMessage
            return
        }
        viewModelScope.launch {
            try {
                c.replyFeed(item.id, FeedReply(kind, item.requestId, params))
                _state.update { cur ->
                    if (cur is UiState.Ready) UiState.Ready(cur.data.filterNot { it.id == item.id }) else cur
                }
            } catch (ex: Exception) {
                _actionError.value = ex.message ?: replyFailedMessage
            }
        }
    }

    /**
     * Resolves [item]'s originating terminal for the inbox row's "open
     * terminal" affordance -- either a single surface directly, several
     * candidate workspaces for the caller to show a picker over (see
     * [pendingItemTarget]'s doc comment on why cwd alone can be ambiguous),
     * or null (with [actionError] set) if nothing matches at all.
     * [PendingFeedItem] carries no workspace/surface id of its own, so this
     * always does a fresh live lookup rather than reusing [state].
     */
    suspend fun terminalTarget(item: PendingFeedItem): TerminalMatch? {
        val c = client ?: run {
            _actionError.value = bridgeNotConfiguredMessage
            return null
        }
        val target = runCatching { c.sessions() }.getOrNull()?.let { pendingItemTarget(item, it) }
        if (target == null) _actionError.value = terminalNotFoundMessage
        return target
    }

    private companion object {
        const val EVENT_REFRESH_DEBOUNCE_MS = 800L
    }
}
