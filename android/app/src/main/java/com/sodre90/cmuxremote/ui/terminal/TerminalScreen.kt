package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val BASE_FONT_SP = 13f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    // Pinch-zoom: accumulate scale, clamp so the derived font size stays in range.
    var zoomScale by remember { mutableFloatStateOf(1f) }
    val fontSizeSp = zoomedFontSizeSp(BASE_FONT_SP, zoomScale)

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
                    OutlinedButton(onClick = {
                        clipboard.getText()?.text?.let { vm.sendText(it) }
                    }) { Text("Paste") }
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
                s.error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.error)
                    Button(onClick = { vm.reconnect() }) { Text("Reconnect") }
                }

                s.grid == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                else -> {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    // Measure the bundled font's real cell from a 10-glyph run.
                    val (cellW, cellH) = remember(fontSizeSp) {
                        val r = measurer.measure(
                            AnnotatedString("MMMMMMMMMM"),
                            style = TextStyle(fontFamily = TerminalFont, fontSize = fontSizeSp.sp),
                        )
                        (r.size.width / 10f) to r.size.height.toFloat()
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    zoomScale = (zoomScale * zoom)
                                        .coerceIn(7f / BASE_FONT_SP, 22f / BASE_FONT_SP)
                                }
                            },
                    ) {
                        val wPx = with(density) { maxWidth.toPx() }
                        val hPx = with(density) { maxHeight.toPx() }
                        val (cols, rows) = gridDimensions(wPx, hPx, cellW, cellH)
                        // resize() only fires when (cols,rows) actually change.
                        LaunchedEffect(cols, rows) { vm.resize(cols, rows) }

                        RenderGridView(
                            grid = s.grid,
                            styles = s.styles,
                            fontSizeSp = fontSizeSp,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    }
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
        TerminalKeys.forEach { (label, seq) ->
            OutlinedButton(onClick = { onKey(seq) }) { Text(label) }
        }
    }
}
