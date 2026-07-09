package com.sodre90.cmuxremote.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.BridgeGateway
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.model.FeedReply
import com.sodre90.cmuxremote.model.PendingFeedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

// Events-socket reconnect backoff: 1s, doubling to a 5s cap.
private const val INITIAL_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 5_000L

// Mirrors FallbackBridgeClient's penalty window: once RELAY has proven
// unreachable, don't retry it on every single reconnect for this long.
private const val RELAY_PENALTY_MS = 30_000L

/**
 * Backs the agent inbox. Pending blocking prompts come from `GET /feed/pending`
 * (cmux `feed.list`), which carries the real `request_id` and the choosable
 * options the user must pick from — the event stream has neither. The live
 * `/events` socket is used only as a trigger to re-fetch when a new prompt
 * appears; the prompt content always comes from a fresh pending-feed fetch.
 */
class InboxViewModel(bridge: BridgeGateway) : ViewModel() {

    private val client = bridge.activeBridge()

    private val _items = MutableStateFlow<List<PendingFeedItem>>(emptyList())
    val items: StateFlow<List<PendingFeedItem>> = _items.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
        // Re-fetch when an agent newly needs attention. Telemetry feed events
        // (PreToolUse, etc.) are ignored so we don't hammer feed.list. The
        // socket is dropped when the app is backgrounded, so reconnect with
        // backoff and re-sync pending items after each gap instead of dying on
        // the first disconnect.
        if (bridge.anyBridgeConfigured()) {
            viewModelScope.launch {
                // Set only when a connect attempt against RELAY never receives a
                // single frame -- a genuine reachability failure, as opposed to a
                // socket that connected fine and later dropped (e.g. the app was
                // backgrounded). See TerminalViewModel's relayDownUntil for the
                // full rationale.
                var relayDownUntil = 0L
                var backoff = INITIAL_BACKOFF_MS
                while (isActive) {
                    val primarySlot =
                        if (System.currentTimeMillis() < relayDownUntil) ConnectionSlot.DIRECT else ConnectionSlot.RELAY
                    val events = bridge.eventsSocket(primarySlot) ?: bridge.eventsSocket(primarySlot.other())
                    if (events == null) { delay(backoff); continue }
                    var gotFrame = false
                    try {
                        events.connect().collect { frame ->
                            gotFrame = true
                            backoff = INITIAL_BACKOFF_MS
                            if (frame.type == "feed" && frame.needsAttention) refresh()
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (_: Exception) {
                        if (!gotFrame && primarySlot == ConnectionSlot.RELAY) {
                            relayDownUntil = System.currentTimeMillis() + RELAY_PENALTY_MS
                        }
                    }
                    if (!isActive) break
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val c = client ?: run { _error.value = "Bridge not configured"; return }
        viewModelScope.launch {
            try {
                // "question" (AskUserQuestion) is the only replyable kind cmux
                // currently surfaces as a pending feed item.
                _items.value = c.pendingFeed().filter { it.kind == "question" }
                _error.value = null
            } catch (ex: Exception) {
                _error.value = ex.message ?: "Failed to load inbox"
            }
        }
    }

    /** Answer a question item with the labels of the chosen options. */
    fun reply(item: PendingFeedItem, selections: List<String>) {
        val c = client ?: run { _error.value = "Bridge not configured"; return }
        val params = buildJsonObject {
            putJsonArray("selections") { selections.forEach { add(it) } }
        }
        viewModelScope.launch {
            try {
                c.replyFeed(item.id, FeedReply("question", item.requestId, params))
                _items.update { cur -> cur.filterNot { it.id == item.id } }
            } catch (ex: Exception) {
                _error.value = ex.message ?: "Reply failed"
            }
        }
    }
}
