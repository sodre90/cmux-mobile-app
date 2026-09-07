package com.sodre90.cmuxremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R

/**
 * The failed-to-load state shared by the sessions list and the inbox: a sentence
 * the user can act on, a Retry, and the raw message kept behind Details for when
 * the sentence is not enough (or is [BridgeFailure.Unknown] and so says nothing).
 */
@Composable
fun ErrorState(
    rawMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember(rawMessage) { mutableStateOf(false) }
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(classifyBridgeFailure(rawMessage).message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(stringResource(R.string.action_details))
        }
        if (showDetails) {
            Text(
                text = rawMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
