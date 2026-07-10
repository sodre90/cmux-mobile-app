package com.sodre90.cmuxremote.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.ui.theme.CmuxTheme

/** Replaces the old single-pairing Settings screen: shows both
 *  [ConnectionSlot]s' paired/unpaired status side by side, each with its
 *  own (re)pair and forget action, so the user can see at a glance whether
 *  they have the automatic-fallback benefit (both paired) or just one
 *  transport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    relayConfigured: Boolean,
    directConfigured: Boolean,
    testPushState: TestPushUiState,
    onPair: (ConnectionSlot) -> Unit,
    onForget: (ConnectionSlot) -> Unit,
    onSendTestPush: () -> Unit,
    onDone: () -> Unit,
) {
    var forgetTarget by remember { mutableStateOf<ConnectionSlot?>(null) }
    val relayLabel = stringResource(R.string.connection_slot_relay)
    val directLabel = stringResource(R.string.connection_slot_direct)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.connections_title)) }) }) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionRow(
                label = relayLabel,
                description = stringResource(R.string.connection_relay_description),
                configured = relayConfigured,
                onPair = { onPair(ConnectionSlot.RELAY) },
                onForget = { forgetTarget = ConnectionSlot.RELAY },
            )
            ConnectionRow(
                label = directLabel,
                description = stringResource(R.string.connection_direct_description),
                configured = directConfigured,
                onPair = { onPair(ConnectionSlot.DIRECT) },
                onForget = { forgetTarget = ConnectionSlot.DIRECT },
            )
            if (relayConfigured || directConfigured) {
                TestPushRow(state = testPushState, onSendTestPush = onSendTestPush)
            }
            if (relayConfigured || directConfigured) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
    forgetTarget?.let { slot ->
        ForgetConnectionDialog(
            slotLabel = if (slot == ConnectionSlot.RELAY) relayLabel else directLabel,
            onDismiss = { forgetTarget = null },
            onConfirm = {
                onForget(slot)
                forgetTarget = null
            },
        )
    }
}

@Composable
private fun ConnectionRow(
    label: String,
    description: String,
    configured: Boolean,
    onPair: () -> Unit,
    onForget: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label)
            Text(description)
            val statusRes = if (configured) R.string.connection_status_paired else R.string.connection_status_not_paired
            Text(stringResource(statusRes))
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (configured) R.string.action_repair else R.string.action_pair))
            }
            if (configured) {
                OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_forget))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionRowPreview() {
    CmuxTheme {
        ConnectionRow(
            label = "Relay",
            description = "Reaches your Mac from anywhere, via the home server.",
            configured = false,
            onPair = {},
            onForget = {},
        )
    }
}

@Preview(showBackground = true, name = "Paired")
@Composable
private fun ConnectionRowPairedPreview() {
    CmuxTheme {
        ConnectionRow(
            label = "Tailscale (direct)",
            description = "Reaches your Mac directly over your tailnet.",
            configured = true,
            onPair = {},
            onForget = {},
        )
    }
}

/** A real, end-to-end push to this device only (see BridgeClient.sendTestPush)
 *  -- a 30-second way to check push setup actually works instead of waiting
 *  to notice it's broken. Failure surfaces the real error text: this is a
 *  debugging tool, so a silent failure defeats the point. */
@Composable
private fun TestPushRow(state: TestPushUiState, onSendTestPush: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.test_push_title))
            Text(stringResource(R.string.test_push_description))
            Button(
                onClick = onSendTestPush,
                enabled = state !is TestPushUiState.Sending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val buttonRes = if (state is TestPushUiState.Sending) {
                    R.string.status_sending
                } else {
                    R.string.test_push_send_button
                }
                Text(stringResource(buttonRes))
            }
            when (state) {
                is TestPushUiState.Success -> Text(
                    stringResource(R.string.test_push_success),
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestPushUiState.Error -> Text(
                    stringResource(R.string.test_push_failure_prefix, state.message),
                    color = MaterialTheme.colorScheme.error,
                )
                TestPushUiState.Idle, TestPushUiState.Sending -> Unit
            }
        }
    }
}

@Composable
private fun ForgetConnectionDialog(slotLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forget_connection_dialog_title, slotLabel)) },
        text = { Text(stringResource(R.string.forget_connection_dialog_body, slotLabel)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_forget)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
