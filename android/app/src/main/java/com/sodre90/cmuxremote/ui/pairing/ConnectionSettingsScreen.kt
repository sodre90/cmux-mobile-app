package com.sodre90.cmuxremote.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.data.ConnectionSlot

/** Replaces the old single-pairing Settings screen: shows both
 *  [ConnectionSlot]s' paired/unpaired status side by side, each with its
 *  own (re)pair action, so the user can see at a glance whether they have
 *  the automatic-fallback benefit (both paired) or just one transport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    relayConfigured: Boolean,
    directConfigured: Boolean,
    onPair: (ConnectionSlot) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Connections") }) }) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionRow(
                label = "Relay",
                description = "Reaches your Mac from anywhere, via the home server.",
                configured = relayConfigured,
                onPair = { onPair(ConnectionSlot.RELAY) },
            )
            ConnectionRow(
                label = "Tailscale (direct)",
                description = "Reaches your Mac directly over your tailnet -- used automatically if the relay is unreachable.",
                configured = directConfigured,
                onPair = { onPair(ConnectionSlot.DIRECT) },
            )
            if (relayConfigured || directConfigured) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ConnectionRow(label: String, description: String, configured: Boolean, onPair: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label)
            Text(description)
            Text(if (configured) "Paired" else "Not paired")
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                Text(if (configured) "Re-pair" else "Pair")
            }
        }
    }
}
