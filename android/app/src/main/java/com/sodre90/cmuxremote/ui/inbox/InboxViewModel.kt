package com.sodre90.cmuxremote.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.model.EventFrame
import com.sodre90.cmuxremote.model.FeedReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A pending agent prompt awaiting the user. */
data class AttentionItem(
    val feedId: String,
    val kind: String,
    val title: String,
    val workspaceId: String?,
)

enum class ReplyDecision { APPROVE, DENY, ANSWER }

class InboxViewModel(container: AppContainer) : ViewModel() {

    private val client = container.bridgeClient()
    private val events = container.eventsSocket()

    private val _items = MutableStateFlow<List<AttentionItem>>(emptyList())
    val items: StateFlow<List<AttentionItem>> = _items.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        val e = events
        if (e == null) {
            _error.value = "Bridge not configured"
        } else {
            viewModelScope.launch {
                try {
                    e.connect().collect { frame ->
                        val id = frame.feedId
                        if (frame.type == "feed" && id != null) {
                            if (frame.needsAttention) add(frame) else remove(id)
                        }
                    }
                } catch (ex: Exception) {
                    _error.value = ex.message ?: "Events disconnected"
                }
            }
        }
    }

    private fun add(frame: EventFrame) {
        val item = AttentionItem(
            feedId = frame.feedId!!,
            kind = frame.kind.orEmpty(),
            title = frame.title.orEmpty(),
            workspaceId = frame.workspaceId,
        )
        _items.update { cur -> if (cur.any { it.feedId == item.feedId }) cur else cur + item }
    }

    private fun remove(feedId: String) {
        _items.update { it.filterNot { x -> x.feedId == feedId } }
    }

    fun reply(item: AttentionItem, decision: ReplyDecision, text: String = "") {
        val c = client ?: run { _error.value = "Bridge not configured"; return }
        // The bridge reads request_id from the body; cmux's exact reply param
        // names are unconfirmed, so feed_id is used as request_id for now.
        val replyKind = when (item.kind) {
            "permissionRequest" -> "permission"
            "question" -> "question"
            "exitPlan" -> "exitPlan"
            else -> item.kind
        }
        val params = when (decision) {
            ReplyDecision.APPROVE -> buildJsonObject { put("decision", "approve") }
            ReplyDecision.DENY -> buildJsonObject { put("decision", "deny") }
            ReplyDecision.ANSWER -> buildJsonObject { put("answer", text) }
        }
        viewModelScope.launch {
            try {
                c.replyFeed(item.feedId, FeedReply(replyKind, item.feedId, params))
                remove(item.feedId)
            } catch (ex: Exception) {
                _error.value = ex.message ?: "Reply failed"
            }
        }
    }
}
