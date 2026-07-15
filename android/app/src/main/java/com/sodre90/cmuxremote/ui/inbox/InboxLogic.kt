package com.sodre90.cmuxremote.ui.inbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
