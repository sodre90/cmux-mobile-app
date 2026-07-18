package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.ui.UiState
import com.sodre90.cmuxremote.ui.YoloBadge
import com.sodre90.cmuxremote.ui.theme.CmuxTheme
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
// Not private: also the bounds/step for the font-size stepper on
// ConnectionSettingsScreen, which edits the same persisted zoom value.
const val MIN_ZOOM = 1f
const val MAX_ZOOM = 6f
const val ZOOM_STEP = 0.25f

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
    var input by rememberSaveable { mutableStateOf("") }
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
    // Seeded from the persisted preference (see TerminalDisplayStore) so a
    // size set here or on ConnectionSettingsScreen survives leaving and
    // reopening a terminal, or restarting the app.
    var userZoom by rememberSaveable { mutableFloatStateOf(vm.loadFontZoom()) }
    // Word-wrap: on → zooming in reflows long rows onto extra lines; off → it stays
    // one row per line with horizontal panning (keeps tables/TUI layouts aligned).
    var wrap by rememberSaveable { mutableStateOf(true) }

    // Ctrl chip: armed by one tap, consumed by the next key sent (through
    // [sendKey], the single funnel every key-bar button, typed-letter diff,
    // and physical-key send below goes through) -- then disarms. Letters get
    // rewritten to their Ctrl byte via applyCtrlArm; anything else the chip
    // can't map (arrows, paste, PgUp, ...) is sent unchanged, but still
    // consumes the arm, since the user's next key press is the one it applies
    // to regardless of whether that key had a Ctrl form.
    var ctrlArmed by rememberSaveable { mutableStateOf(false) }
    val sendKey: (String) -> Unit = { text ->
        if (ctrlArmed) {
            vm.sendText(applyCtrlArm(text))
            ctrlArmed = false
        } else {
            vm.sendText(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.terminal_title))
                        yoloModeLabel(yoloMode)?.let { YoloBadge(it) }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
                actions = {
                    TextButton(onClick = { vm.reconnect() }) { Text(stringResource(R.string.action_refresh)) }
                    TextButton(onClick = { wrap = !wrap }) {
                        Text(stringResource(if (wrap) R.string.terminal_wrap_on else R.string.terminal_wrap_off))
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
                val appCursorKeys = (state as? UiState.Ready)?.data?.grid?.applicationCursorKeys ?: false
                ArrowPad(
                    applicationCursorKeys = appCursorKeys,
                    onKey = sendKey,
                    onPaste = { clipboard.getText()?.text?.let { sendKey(it) } },
                )
                KeyBar(
                    applicationCursorKeys = appCursorKeys,
                    ctrlArmed = ctrlArmed,
                    onToggleCtrl = { ctrlArmed = !ctrlArmed },
                    onKey = sendKey,
                )
                DeliveryStatusLabel(status = deliveryStatus, lostInputNotice = lostInputNotice)
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.message)
                    Button(onClick = { vm.reconnect() }) { Text(stringResource(R.string.action_reconnect)) }
                }

                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is UiState.Ready -> {
                    val grid = s.data.grid
                    val styles = s.data.styles
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
                                    var zoomChanged = false
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.count { it.pressed } >= 2) {
                                            val zoom = event.calculateZoom()
                                            if (zoom != 1f) {
                                                userZoom = (userZoom * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                                zoomChanged = true
                                                event.changes.forEach { if (it.pressed) it.consume() }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    // Persist once per completed pinch gesture rather than on
                                    // every intermediate frame -- a pinch can fire dozens of
                                    // zoom deltas a second, and a plain tap (the common case,
                                    // used to focus the keyboard) never touches zoom at all.
                                    if (zoomChanged) vm.saveFontZoom(userZoom)
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
                        val gridCols = grid.columns.takeIf { it > 0 } ?: cols
                        val fitFontSp = fitFontSizeSp(wPx, gridCols, advancePerSp, MIN_FONT_SP, FIT_MAX_SP)
                        val fontSizeSp = (fitFontSp * userZoom).coerceIn(MIN_FONT_SP, MAX_FONT_SP)

                        RenderGridView(
                            grid = grid,
                            styles = styles,
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
                                    "onValueChange old=${describeForLog(
                                        input
                                    )} new=${describeForLog(new)} diff=${describeForLog(diff)}",
                                )
                                if (diff.isNotEmpty()) sendKey(diff)
                                input = new
                            },
                            textStyle = TextStyle(color = Color.Transparent),
                            cursorBrush = SolidColor(Color.Transparent),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                sendKey("\r")
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
                                //
                                // Keys with no sensible meaning inside a single-line text
                                // field (arrows, Escape, Tab, Enter -- from a physical/
                                // Bluetooth keyboard) are intercepted the same way, instead
                                // of falling through into the field's own IME-driven capture
                                // where they'd be dropped or mangled.
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    if (event.key == Key.Backspace && input.isEmpty()) {
                                        sendKey(DEL)
                                        return@onPreviewKeyEvent true
                                    }
                                    val sequence = physicalKeySequence(event.key, grid.applicationCursorKeys)
                                        ?: return@onPreviewKeyEvent false
                                    sendKey(sequence)
                                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) input = ""
                                    true
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
    val textRes = when {
        lostInputNotice -> R.string.terminal_delivery_reconnected
        status == DeliveryStatus.DELAYED -> R.string.terminal_delivery_delayed
        status == DeliveryStatus.SENDING -> R.string.status_sending
        else -> null
    }
    val text = textRes?.let { stringResource(it) }
    if (text != null) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeliveryStatusLabelSendingPreview() {
    CmuxTheme {
        DeliveryStatusLabel(status = DeliveryStatus.SENDING, lostInputNotice = false)
    }
}

@Preview(showBackground = true, name = "Delayed")
@Composable
private fun DeliveryStatusLabelDelayedPreview() {
    CmuxTheme {
        DeliveryStatusLabel(status = DeliveryStatus.DELAYED, lostInputNotice = false)
    }
}

@Preview(showBackground = true, name = "Lost input notice")
@Composable
private fun DeliveryStatusLabelLostInputPreview() {
    CmuxTheme {
        DeliveryStatusLabel(status = DeliveryStatus.CONFIRMED, lostInputNotice = true)
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
        OutlinedButton(onClick = onPaste) { Text(stringResource(R.string.terminal_paste)) }
    }
}

@Composable
private fun ArrowButton(key: CursorKey, applicationCursorKeys: Boolean, onKey: (String) -> Unit) {
    val description = stringResource(key.contentDescriptionRes)
    OutlinedButton(
        onClick = { onKey(key.sequence(applicationCursorKeys)) },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) { Text(key.label) }
}

/**
 * The horizontally-scrolling key bar, with the latching Ctrl chip pinned
 * outside the scroll (like the D-pad, it must never require scrolling to
 * find). [ctrlArmed] is owned by the caller so the same arm/disarm state
 * also gates typed-letter input outside this composable.
 */
@Composable
private fun KeyBar(
    applicationCursorKeys: Boolean,
    ctrlArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onKey: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtrlChip(armed = ctrlArmed, onClick = onToggleCtrl)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalKeys.forEach { key ->
                val description = stringResource(key.contentDescriptionRes)
                OutlinedButton(
                    onClick = { onKey(key.sequence(applicationCursorKeys)) },
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Text(key.label)
                }
            }
        }
    }
}

/**
 * The general Ctrl modifier: tapping it arms sending the next key as its
 * Ctrl combination (see [applyCtrlArm]) instead of its literal form, then
 * disarms itself. Filled with the primary color while armed -- distinct
 * enough at a glance that a modal toggle doesn't get left on unnoticed. The
 * scrollable bar's own ^C/^D/^Z stay as one-tap shortcuts for the combos
 * used often enough to be worth a dedicated button; this chip covers
 * everything else (Ctrl+L, Ctrl+A/E, Ctrl+R, ...) without hardcoding a
 * button per combo.
 */
@Composable
private fun CtrlChip(armed: Boolean, onClick: () -> Unit) {
    val colors = if (armed) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        ButtonDefaults.outlinedButtonColors()
    }
    val ctrlContentDescription = stringResource(R.string.terminal_ctrl_content_description)
    val armedStateDescription = stringResource(
        if (armed) R.string.terminal_ctrl_state_armed else R.string.terminal_ctrl_state_not_armed,
    )
    OutlinedButton(
        onClick = onClick,
        colors = colors,
        // The armed/unarmed distinction is otherwise color-only (filled vs
        // outlined) -- stateDescription carries it to TalkBack without
        // changing the button's role away from the plain "double tap to
        // activate" hint that matches its actual one-shot-per-tap behavior.
        modifier = Modifier.semantics {
            contentDescription = ctrlContentDescription
            stateDescription = armedStateDescription
        },
    ) { Text(stringResource(R.string.terminal_ctrl_chip)) }
}
