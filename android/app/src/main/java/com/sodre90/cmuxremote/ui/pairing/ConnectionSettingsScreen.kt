package com.sodre90.cmuxremote.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.CredentialStatus
import com.sodre90.cmuxremote.data.TERMINAL_POLL_CHOICES
import com.sodre90.cmuxremote.ui.terminal.MAX_ZOOM
import com.sodre90.cmuxremote.ui.terminal.MIN_ZOOM
import com.sodre90.cmuxremote.ui.terminal.ZOOM_STEP
import com.sodre90.cmuxremote.ui.theme.CmuxTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

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
    relayCredentialStatus: CredentialStatus,
    directCredentialStatus: CredentialStatus,
    testPushState: TestPushUiState,
    fontZoom: Float,
    wheelScrolling: Boolean,
    terminalPollMs: Int,
    appVersion: String,
    bridgeVersion: BridgeVersionUiState,
    onPair: (ConnectionSlot) -> Unit,
    onForget: (ConnectionSlot) -> Unit,
    onSendTestPush: () -> Unit,
    onFontZoomChange: (Float) -> Unit,
    onWheelScrollingChange: (Boolean) -> Unit,
    onTerminalPollMsChange: (Int) -> Unit,
    onDone: () -> Unit,
) {
    var forgetTarget by remember { mutableStateOf<ConnectionSlot?>(null) }
    val relayLabel = stringResource(R.string.connection_slot_relay)
    val directLabel = stringResource(R.string.connection_slot_direct)
    val paired = relayConfigured || directConfigured
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                // The only labelled way out used to be a Done button below two
                // connection cards, the font stepper and Test push -- off the
                // bottom of the screen. On first run there is genuinely nowhere
                // to go back to (this is the start destination until something
                // is paired), which is the same condition Done already had.
                navigationIcon = {
                    if (paired) {
                        IconButton(onClick = onDone) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!paired) {
                FirstRunIntro()
            }
            ConnectionRow(
                label = relayLabel,
                description = stringResource(R.string.connection_relay_description),
                configured = relayConfigured,
                credentialStatus = relayCredentialStatus,
                recoveryHint = stringResource(R.string.connection_recovery_relay),
                onPair = { onPair(ConnectionSlot.RELAY) },
                onForget = { forgetTarget = ConnectionSlot.RELAY },
            )
            ConnectionRow(
                label = directLabel,
                description = stringResource(R.string.connection_direct_description),
                configured = directConfigured,
                credentialStatus = directCredentialStatus,
                recoveryHint = stringResource(R.string.connection_recovery_direct),
                onPair = { onPair(ConnectionSlot.DIRECT) },
                onForget = { forgetTarget = ConnectionSlot.DIRECT },
            )
            FontSizeRow(zoom = fontZoom, onZoomChange = onFontZoomChange)
            WheelScrollingRow(enabled = wheelScrolling, onEnabledChange = onWheelScrollingChange)
            TerminalPollRow(pollMs = terminalPollMs, onPollMsChange = onTerminalPollMsChange)
            if (paired) {
                TestPushRow(state = testPushState, onSendTestPush = onSendTestPush)
            }
            AboutRow(appVersion = appVersion, bridgeVersion = bridgeVersion)
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

/** [credentialStatus] is what the slot's *server* last said, which is a
 *  different question from [configured] (whether this phone has credentials
 *  stored at all) -- a slot can be fully configured and still rejected. */
@Composable
private fun ConnectionRow(
    label: String,
    description: String,
    configured: Boolean,
    credentialStatus: CredentialStatus,
    recoveryHint: String,
    onPair: () -> Unit,
    onForget: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label)
            Text(description)
            val rejected = configured && credentialStatus == CredentialStatus.REJECTED
            val statusRes = when {
                rejected -> R.string.connection_status_rejected
                configured -> R.string.connection_status_paired
                else -> R.string.connection_status_not_paired
            }
            Text(
                stringResource(statusRes),
                color = if (rejected) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            if (rejected) {
                Text(recoveryHint, color = MaterialTheme.colorScheme.error)
            }
            // Pair is the primary action only while there is nothing paired.
            // Once a slot is set up, Re-pair is the rarest thing on the screen
            // and had no business being the loudest -- and Forget, which throws
            // the credentials away, sat next to it looking equally routine.
            if (configured) {
                OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_repair))
                }
                TextButton(
                    onClick = onForget,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_forget))
                }
            } else {
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_pair))
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
            credentialStatus = CredentialStatus.UNKNOWN,
            recoveryHint = "",
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
            credentialStatus = CredentialStatus.LIVE,
            recoveryHint = "",
            onPair = {},
            onForget = {},
        )
    }
}

@Preview(showBackground = true, name = "Rejected")
@Composable
private fun ConnectionRowRejectedPreview() {
    CmuxTheme {
        ConnectionRow(
            label = "Tailscale (direct)",
            description = "Reaches your Mac directly over your tailnet.",
            configured = true,
            credentialStatus = CredentialStatus.REJECTED,
            recoveryHint = "Run `cmux-bridge pair-device -direct` on the Mac, then tap Re-pair.",
            onPair = {},
            onForget = {},
        )
    }
}

/** Sets the same persisted zoom [TerminalScreen][com.sodre90.cmuxremote.ui.terminal.TerminalScreen]'s
 *  pinch gesture reads/writes -- an explicit way to pick a starting size
 *  without having to pinch inside a live terminal first. */
@Composable
private fun FontSizeRow(zoom: Float, onZoomChange: (Float) -> Unit) {
    val decreaseDescription = stringResource(R.string.terminal_font_size_decrease)
    val increaseDescription = stringResource(R.string.terminal_font_size_increase)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.terminal_font_size_title))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onZoomChange(steppedZoomDown(zoom)) },
                    enabled = zoom > MIN_ZOOM,
                    modifier = Modifier.semantics { contentDescription = decreaseDescription },
                ) { Text("-") }
                Text(
                    "${(zoom * 100).roundToInt()}%",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = { onZoomChange(steppedZoomUp(zoom)) },
                    enabled = zoom < MAX_ZOOM,
                    modifier = Modifier.semantics { contentDescription = increaseDescription },
                ) { Text("+") }
            }
            // What 100% means is not guessable, and a pinch can leave the value
            // somewhere no stepper tap would ever produce -- so say what the
            // baseline is and give a one-tap way back to it.
            Text(
                stringResource(R.string.terminal_font_size_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onZoomChange(MIN_ZOOM) }, enabled = zoom > MIN_ZOOM) {
                Text(stringResource(R.string.terminal_font_size_reset))
            }
        }
    }
}

/**
 * Picks how panes that report mouse tracking are scrolled. Both answers are
 * defensible and the better one depends on the link, so it is a choice rather
 * than a constant -- see
 * [TerminalDisplayGateway.loadWheelScrolling][com.sodre90.cmuxremote.data.TerminalDisplayGateway.loadWheelScrolling].
 * Panes that do not report mouse tracking are unaffected either way.
 */
@Composable
private fun WheelScrollingRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.terminal_wheel_scrolling_title),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                stringResource(
                    if (enabled) {
                        R.string.terminal_wheel_scrolling_on_help
                    } else {
                        R.string.terminal_wheel_scrolling_off_help
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Picks how often the bridge re-reads an open pane for output.
 *
 * The one setting on this screen that spends data rather than shaping the
 * picture, so the help text is a rate and not a taste: watching a busy pane
 * costs a frame per tick, and halving the rate halves the cost. Typing is
 * unaffected at any setting -- input is answered immediately rather than on the
 * next tick -- so the only thing slower buys back is how promptly an agent's
 * own output appears.
 */
@Composable
private fun TerminalPollRow(pollMs: Int, onPollMsChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.terminal_poll_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TERMINAL_POLL_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = choice == pollMs,
                        onClick = { onPollMsChange(choice) },
                        label = { Text(pollChoiceLabel(choice)) },
                    )
                }
            }
            Text(
                stringResource(R.string.terminal_poll_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sub-second intervals read better as milliseconds, whole seconds as seconds. */
@Composable
private fun pollChoiceLabel(ms: Int): String =
    if (ms < MILLIS_PER_SECOND) {
        stringResource(R.string.terminal_poll_millis, ms)
    } else {
        stringResource(R.string.terminal_poll_seconds, ms / MILLIS_PER_SECOND)
    }

private const val MILLIS_PER_SECOND = 1000

/** The two versions that can differ. The app updates from a release APK and the
 *  agent from a binary on the Mac, so "what am I running" has two answers, and
 *  before this neither was visible anywhere on the phone. */
@Composable
private fun AboutRow(appVersion: String, bridgeVersion: BridgeVersionUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.about_title))
            VersionLine(label = stringResource(R.string.about_app_version), value = appVersion)
            VersionLine(
                label = stringResource(R.string.about_bridge_version),
                value = bridgeVersionText(bridgeVersion),
            )
        }
    }
}

@Composable
private fun VersionLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun bridgeVersionText(state: BridgeVersionUiState): String = when (state) {
    is BridgeVersionUiState.Known -> state.version
    BridgeVersionUiState.Loading -> stringResource(R.string.about_version_loading)
    BridgeVersionUiState.Unavailable -> stringResource(R.string.about_version_unavailable)
}

@Preview(showBackground = true)
@Composable
private fun AboutRowPreview() {
    CmuxTheme {
        AboutRow(appVersion = "0.3.0", bridgeVersion = BridgeVersionUiState.Known("0.3.0"))
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutRowUnavailablePreview() {
    CmuxTheme {
        AboutRow(appVersion = "0.3.0", bridgeVersion = BridgeVersionUiState.Unavailable)
    }
}

/**
 * The next step *below* [zoom], snapped onto the ZOOM_STEP grid rather than
 * subtracted from wherever a pinch happened to land. Stepping by a fixed offset
 * from an arbitrary pinch value (1.36, say) meant the buttons could only ever
 * reach 111/136/161% -- and once you had drifted off the grid there was no way
 * back onto it.
 */
internal fun steppedZoomDown(zoom: Float): Float =
    ((ceil(zoom / ZOOM_STEP) - 1) * ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)

/** The next step above [zoom] -- see [steppedZoomDown]. */
internal fun steppedZoomUp(zoom: Float): Float =
    ((floor(zoom / ZOOM_STEP) + 1) * ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)

/** Shown until something is paired, which is also when this screen is the start
 *  destination -- so it is the first thing the app ever says. It used to say
 *  nothing: two cards, two "Pair" buttons, and no mention that the Mac has to be
 *  running the agent, or which of the two slots to start with. */
@Composable
private fun FirstRunIntro() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.connections_intro_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.connections_intro_body))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FontSizeRowPreview() {
    CmuxTheme {
        FontSizeRow(zoom = 1.5f, onZoomChange = {})
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
