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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.model.FeedOption
import com.sodre90.cmuxremote.model.FeedQuestion
import com.sodre90.cmuxremote.model.PendingFeedItem
import com.sodre90.cmuxremote.ui.UiState
import com.sodre90.cmuxremote.ui.theme.CmuxTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: InboxViewModel,
    onBack: () -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val actionError by vm.actionError.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
                actions = {
                    TextButton(onClick = vm::refresh) { Text(stringResource(R.string.action_refresh)) }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                actionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                }
                // Loading/Error deliberately render exactly like an empty Ready
                // list (see InboxViewModel's [_state] doc comment): this
                // preserves the pre-unification look (a plain "No pending
                // prompts" with the actionError banner above it, if any) --
                // InboxViewModel never actually reaches UiState.Error today.
                when (val s = state) {
                    is UiState.Loading, is UiState.Error -> Box(Modifier.fillMaxSize()) {
                        Text(stringResource(R.string.inbox_empty), Modifier.align(Alignment.Center))
                    }
                    is UiState.Ready -> if (s.data.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            Text(stringResource(R.string.inbox_empty), Modifier.align(Alignment.Center))
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(s.data, key = { it.id }) { item ->
                                InboxRow(
                                    item = item,
                                    onSend = { labels -> vm.reply(item, labels) },
                                    onOpenTerminal = {
                                        scope.launch { vm.terminalTarget(item)?.let(onOpenTerminal) }
                                    },
                                )
                            }
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
    onOpenTerminal: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = agent, style = MaterialTheme.typography.titleMedium)
                // Additional affordance alongside the reply flow below -- jumps
                // straight to the terminal this prompt came from instead of
                // making the user back out to Sessions and hunt for it.
                TextButton(onClick = onOpenTerminal) { Text(stringResource(R.string.inbox_open_terminal)) }
            }
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
            ) { Text(stringResource(R.string.inbox_send_reply)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun InboxRowPreview() {
    CmuxTheme {
        InboxRow(
            item = PendingFeedItem(
                id = "item-1",
                requestId = "req-1",
                kind = "question",
                title = "cmux-app",
                cwd = "/Users/dev/projects/cmux-app",
                questions = listOf(
                    FeedQuestion(
                        id = "q0",
                        prompt = "Which branch should this land on?",
                        multiSelect = false,
                        options = listOf(
                            FeedOption(id = "main", label = "main", description = "Ship straight to main"),
                            FeedOption(id = "feature", label = "feature branch", description = "Open a PR instead"),
                        ),
                    ),
                ),
            ),
            onSend = {},
            onOpenTerminal = {},
        )
    }
}

@Preview(showBackground = true, name = "Multi-select")
@Composable
private fun InboxRowMultiSelectPreview() {
    CmuxTheme {
        InboxRow(
            item = PendingFeedItem(
                id = "item-2",
                requestId = "req-2",
                kind = "permission",
                title = "cmux-app",
                cwd = "/Users/dev/projects/cmux-app",
                questions = listOf(
                    FeedQuestion(
                        id = "q0",
                        prompt = "Which tools should this rule cover?",
                        multiSelect = true,
                        options = listOf(
                            FeedOption(id = "read", label = "Read"),
                            FeedOption(id = "write", label = "Write"),
                            FeedOption(id = "bash", label = "Bash"),
                        ),
                    ),
                ),
            ),
            onSend = {},
            onOpenTerminal = {},
        )
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
