package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sodre90.cmuxremote.ui.YoloBadge
import com.sodre90.cmuxremote.ui.yoloModeLabel
import kotlinx.coroutines.delay

// Reference size for the surface-viewport resize math (decoupled from the display
// zoom so pinching never re-resizes the surface).
private const val BASE_FONT_SP = 13f

// Display font bounds. The fit-to-width baseline lives in [MIN_FONT_SP, FIT_MAX_SP];
// pinching in can grow it up to MAX_FONT_SP.
private const val MIN_FONT_SP = 7f
private const val FIT_MAX_SP = 22f
private const val MAX_FONT_SP = 28f

// Pinch range, multiplied onto the fit baseline: 1x = exact fit, up to 6x to read in.
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val yoloMode by vm.yoloMode.collectAsState()
    val deliveryStatus by vm.deliveryStatus.collectAsState()
    val lostInputNotice by vm.lostInputNotice.collectAsState()
    // Not rendered anywhere -- kept only so diffToKeystrokes has an old value
    // to diff each keystroke against. The invisible capture field below is the
    // only place typed input touches the UI; the terminal's own echo is the
    // single visible record of what's been typed, instead of mirroring it in
    // a second, separately-scrolling box.
    var input by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // The "lost input" notice is a one-shot signal (see TerminalViewModel) --
    // show it briefly, then tell the view model it's been seen.
    LaunchedEffect(lostInputNotice) {
        if (lostInputNotice) {
            delay(4_000)
            vm.dismissLostInputNotice()
        }
    }

    // Remote terminal sessions are watched, not typed into continuously - don't
    // let the screen sleep mid-session. Reset on leaving so the rest of the app
    // keeps normal screen-timeout behavior.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Pinch-to-zoom factor over the fit-to-width baseline (1f = exact fit).
    var userZoom by remember { mutableFloatStateOf(1f) }
    // Word-wrap: on → zooming in reflows long rows onto extra lines; off → it stays
    // one row per line with horizontal panning (keeps tables/TUI layouts aligned).
    var wrap by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Terminal")
                        yoloModeLabel(yoloMode)?.let { YoloBadge(it) }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { vm.reconnect() }) { Text("Refresh") }
                    TextButton(onClick = { wrap = !wrap }) {
                        Text(if (wrap) "Wrap: on" else "Wrap: off")
                    }
                },
            )
        },
        bottomBar = {
            // A custom bottomBar (plain Column) does not consume insets the way
            // NavigationBar/BottomAppBar do, so apply them here: lift the bar above
            // the system navigation bar, and above the IME when it opens. Union (not
            // chained padding) so the two bottom insets don't stack.
            Column(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom),
                ),
            ) {
                val appCursorKeys = state.grid?.applicationCursorKeys ?: false
                ArrowPad(
                    applicationCursorKeys = appCursorKeys,
                    onKey = vm::sendText,
                    onPaste = { clipboard.getText()?.text?.let { vm.sendText(it) } },
                )
                KeyBar(applicationCursorKeys = appCursorKeys, onKey = vm::sendText)
                DeliveryStatusLabel(status = deliveryStatus, lostInputNotice = lostInputNotice)
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
                    // Glyph advance per 1sp, measured once at the base size. The font is
                    // scalable, so advance scales linearly with size — this lets us solve
                    // for the font that makes the surface width fit the viewport.
                    val advancePerSp = remember {
                        val w = measurer.measure(
                            AnnotatedString("MMMMMMMMMM"),
                            style = TextStyle(fontFamily = TerminalFont, fontSize = BASE_FONT_SP.sp),
                        ).size.width / 10f
                        w / BASE_FONT_SP
                    }
                    // Surface-viewport (resize) cell box: a fixed reference, independent of
                    // display zoom, so pinching never re-resizes the surface.
                    val cellWBase = advancePerSp * BASE_FONT_SP
                    val cellHBase = with(density) { (BASE_FONT_SP * TerminalLineHeightFactor).sp.toPx() }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                // Reliable pinch: claim multi-touch on the Initial pass so the
                                // grid's vertical/horizontal scroll never fights it. Single-finger
                                // events are left unconsumed and fall through to those scrolls.
                                awaitEachGesture {
                                    awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.count { it.pressed } >= 2) {
                                            val zoom = event.calculateZoom()
                                            if (zoom != 1f) {
                                                userZoom = (userZoom * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                                event.changes.forEach { if (it.pressed) it.consume() }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            // Tapping the terminal is how you start typing -- there's no
                            // separate input box to tap into anymore.
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            },
                    ) {
                        // RenderGridView insets its content by 8.dp on every side; subtract
                        // it so the measured fit matches the real text area (no clipped edge).
                        val padPx = with(density) { 8.dp.toPx() }
                        val wPx = with(density) { maxWidth.toPx() } - 2 * padPx
                        val hPx = with(density) { maxHeight.toPx() } - 2 * padPx
                        val (cols, rows) = gridDimensions(wPx, hPx, cellWBase, cellHBase)
                        // resize() only fires when (cols,rows) actually change.
                        LaunchedEffect(cols, rows) { vm.resize(cols, rows) }

                        // Fit-to-width: size the font so the surface's full column count fits
                        // the viewport; pinch (userZoom) grows it from there to read a section.
                        val gridCols = s.grid.columns.takeIf { it > 0 } ?: cols
                        val fitFontSp = fitFontSizeSp(wPx, gridCols, advancePerSp, MIN_FONT_SP, FIT_MAX_SP)
                        val fontSizeSp = (fitFontSp * userZoom).coerceIn(MIN_FONT_SP, MAX_FONT_SP)

                        RenderGridView(
                            grid = s.grid,
                            styles = s.styles,
                            fontSizeSp = fontSizeSp,
                            wrap = wrap,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )

                        // The real capture point for the keyboard: fully transparent and
                        // 1dp so nothing renders, but still focusable, so the terminal's
                        // own echo (via RenderGridView above) is the only place typed text
                        // is visible -- not duplicated in a second on-screen field.
                        BasicTextField(
                            value = input,
                            onValueChange = { new ->
                                val diff = diffToKeystrokes(input, new)
                                android.util.Log.d(
                                    "TerminalInput",
                                    "onValueChange old=${describeForLog(input)} new=${describeForLog(new)} diff=${describeForLog(diff)}",
                                )
                                if (diff.isNotEmpty()) vm.sendText(diff)
                                input = new
                            },
                            textStyle = TextStyle(color = Color.Transparent),
                            cursorBrush = SolidColor(Color.Transparent),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                vm.sendText("\r")
                                input = ""
                            }),
                            modifier = Modifier
                                .size(1.dp)
                                .alpha(0f)
                                .focusRequester(focusRequester)
                                // Backspace on an already-empty field is a no-op as far as
                                // the field's own text is concerned, so onValueChange never
                                // fires -- there's nothing for diffToKeystrokes to diff. Most
                                // IMEs (Gboard included) still dispatch a raw KEYCODE_DEL in
                                // that case for compatibility; catch it here and send the
                                // erase byte directly.
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Backspace &&
                                        input.isEmpty()
                                    ) {
                                        vm.sendText(DEL)
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A small, easy-to-miss-on-purpose line reporting delivery trouble: recent
 * input that's stuck unconfirmed, or a reconnect that dropped some in-flight
 * input whose fate is now unknowable. Renders nothing when everything's
 * confirmed, so normal typing never shows a persistent status line.
 */
@Composable
private fun DeliveryStatusLabel(status: DeliveryStatus, lostInputNotice: Boolean) {
    val text = when {
        lostInputNotice -> "Reconnected — check your last few keystrokes went through"
        status == DeliveryStatus.DELAYED -> "Input not confirmed — check connection"
        status == DeliveryStatus.SENDING -> "Sending…"
        else -> null
    }
    if (text != null) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * An always-visible single row of arrow keys above the scrollable key bar, so menu
 * navigation never requires scrolling to find them. Each arrow resolves SS3 vs CSI
 * against [applicationCursorKeys] like any cursor key.
 */
@Composable
private fun ArrowPad(applicationCursorKeys: Boolean, onKey: (String) -> Unit, onPaste: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArrowButton(ArrowLeft, applicationCursorKeys, onKey)
            ArrowButton(ArrowUp, applicationCursorKeys, onKey)
            ArrowButton(ArrowDown, applicationCursorKeys, onKey)
            ArrowButton(ArrowRight, applicationCursorKeys, onKey)
        }
        OutlinedButton(onClick = onPaste) { Text("Paste") }
    }
}

@Composable
private fun ArrowButton(key: CursorKey, applicationCursorKeys: Boolean, onKey: (String) -> Unit) {
    OutlinedButton(
        onClick = { onKey(key.sequence(applicationCursorKeys)) },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) { Text(key.label) }
}

@Composable
private fun KeyBar(applicationCursorKeys: Boolean, onKey: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TerminalKeys.forEach { key ->
            OutlinedButton(onClick = { onKey(key.sequence(applicationCursorKeys)) }) {
                Text(key.label)
            }
        }
    }
}
