package com.sodre90.cmuxremote.ui.inbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The pending-feed [kind]s the Inbox actually renders and can reply to --
 * shared with SessionsScreen's Inbox badge count so the badge never shows a
 * number the Inbox itself can't back up (e.g. a workspace's cmux `has_unread`
 * flag, which fires on any new output, not just a repliable prompt).
 * "exitPlan" is excluded: not yet wired into the Inbox (its reply schema
 * isn't confirmed live).
 */
fun isPendingInboxKind(kind: String): Boolean = kind == "question" || kind == "permissionRequest"

/**
 * A `PendingFeedItem.toolInput` is cmux's own JSON-encoded string of the raw
 * tool args, shape varying per tool (Bash's `command`, Read's `file_path`,
 * ...) -- rendered as generic key/value lines rather than one UI per tool.
 * Non-primitive values (nested objects/arrays) and anything that isn't a flat
 * JSON object at the top level are skipped rather than dumped via
 * `toString()`, since that wouldn't read well in the Inbox row.
 */
fun parseToolInputEntries(raw: String): List<Pair<String, String>> {
    val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
    return obj.entries.mapNotNull { (fieldName, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { fieldName to it }
    }
}
