package com.sodre90.cmuxremote.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.model.FeedOption
import com.sodre90.cmuxremote.model.FeedQuestion
import com.sodre90.cmuxremote.model.PendingFeedItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: InboxViewModel,
    onBack: () -> Unit,
) {
    val items by vm.items.collectAsState()
    val error by vm.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent inbox") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = vm::refresh) { Text("Refresh") } },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                }
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize()) {
                        Text("No pending prompts", Modifier.align(Alignment.Center))
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it.id }) { item ->
                            InboxRow(item) { labels -> vm.reply(item, labels) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(
    item: PendingFeedItem,
    onSend: (List<String>) -> Unit,
) {
    // cmux usually populates questions[]; fall back to the flat question_options.
    val questions = remember(item.id) {
        item.questions.ifEmpty {
            if (item.questionOptions.isNotEmpty()) {
                listOf(FeedQuestion(id = "q0", multiSelect = item.questionMultiSelect, options = item.questionOptions))
            } else {
                emptyList()
            }
        }
    }
    // Selection state per option, keyed "<questionId>|<optionId>".
    val selected = remember(item.id) { mutableStateMapOf<String, Boolean>() }
    fun key(q: FeedQuestion, o: FeedOption) = "${q.id}|${o.id}"
    fun toggle(q: FeedQuestion, o: FeedOption) {
        if (q.multiSelect) {
            selected[key(q, o)] = !(selected[key(q, o)] ?: false)
        } else {
            q.options.forEach { selected[key(q, it)] = (it.id == o.id) }
        }
    }

    val ready = questions.all { q -> q.options.isEmpty() || q.options.any { selected[key(q, it)] == true } }
    val agent = item.cwd.substringAfterLast('/').ifBlank { item.title.ifBlank { "agent" } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = agent, style = MaterialTheme.typography.titleMedium)
            questions.forEach { q ->
                val heading = q.prompt.ifBlank { q.header }
                if (heading.isNotBlank()) {
                    Text(
                        text = heading,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                q.options.forEach { o ->
                    OptionRow(o, selected[key(q, o)] == true, q.multiSelect) { toggle(q, o) }
                }
            }
            Button(
                onClick = {
                    val labels = questions.flatMap { q ->
                        q.options.filter { selected[key(q, it)] == true }.map { it.label }
                    }
                    onSend(labels)
                },
                enabled = ready,
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Send reply") }
        }
    }
}

@Composable
private fun OptionRow(
    option: FeedOption,
    selected: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
        Column(modifier = Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
            if (option.description.isNotBlank()) {
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
