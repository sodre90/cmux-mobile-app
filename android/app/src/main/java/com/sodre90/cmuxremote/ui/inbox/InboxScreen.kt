package com.sodre90.cmuxremote.ui.inbox

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
                        items(items, key = { it.feedId }) { item -> InboxRow(item, vm::reply) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(
    item: AttentionItem,
    onReply: (AttentionItem, ReplyDecision, String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = item.title.ifBlank { item.kind.ifBlank { "Prompt" } },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = item.kind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            if (item.kind == "question") {
                var answer by remember(item.feedId) { mutableStateOf("") }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("answer") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onReply(item, ReplyDecision.ANSWER, answer) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Reply") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onReply(item, ReplyDecision.APPROVE, "") }) { Text("Approve") }
                    OutlinedButton(onClick = { onReply(item, ReplyDecision.DENY, "") }) { Text("Deny") }
                }
            }
        }
    }
}
