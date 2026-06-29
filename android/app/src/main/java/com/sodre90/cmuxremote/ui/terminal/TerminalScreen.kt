package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Control sequences, built from code points so no raw control bytes live in source.
private val ESC = Char(27).toString() // ESC / 
private val CTRL_C = Char(3).toString() // ETX / 

private val KEYS = listOf(
    "Esc" to ESC,
    "Tab" to "\t",
    "Ctrl-C" to CTRL_C,
    "Enter" to "\r",
    "Up" to ESC + "[A",
    "Down" to ESC + "[B",
    "Left" to ESC + "[D",
    "Right" to ESC + "[C",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        bottomBar = {
            Column {
                KeyBar(onKey = vm::sendText)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("input") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        vm.sendText(input + "\r")
                        input = ""
                    }) { Text("Send") }
                }
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            val s = state
            when {
                s.error != null -> Text(
                    text = s.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                s.grid == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Approximate the remote viewport from available space (monospace
                    // cell ~7.2dp wide x 14dp tall at 12sp) and keep cmux in sync.
                    val cols = (maxWidth.value / 7.2f).toInt().coerceIn(20, 240)
                    val rows = (maxHeight.value / 14f).toInt().coerceIn(5, 120)
                    LaunchedEffect(cols, rows) { vm.resize(cols, rows) }

                    RenderGridView(
                        grid = s.grid,
                        styles = s.styles,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyBar(onKey: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KEYS.forEach { (label, seq) ->
            OutlinedButton(onClick = { onKey(seq) }) { Text(label) }
        }
    }
}
