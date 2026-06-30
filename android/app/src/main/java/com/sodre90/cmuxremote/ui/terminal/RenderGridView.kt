package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.model.DecodedGrid
import com.sodre90.cmuxremote.model.DecodedLine
import com.sodre90.cmuxremote.model.Style
import kotlinx.coroutines.launch

/** Bundled Nerd Font (powerline + icon glyphs) for the terminal grid. */
val TerminalFont = FontFamily(
    Font(R.font.jetbrains_mono_nerd_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_nerd_bold, FontWeight.Bold),
)

/**
 * Line height as a multiple of the font size. Shared so the rendered row height
 * and TerminalScreen's row-count measurement use a single basis (otherwise the
 * grid resize sent to the backend would not match what is actually drawn).
 */
internal const val TerminalLineHeightFactor = 1.25f

/**
 * Renders a [DecodedGrid] (scrollback + visible screen) on a solid dark canvas:
 * one no-wrap styled line per row, the cursor drawn by inverting its cell, native
 * long-press selection, and stick-to-bottom follow with a jump-to-bottom button.
 */
@Composable
fun RenderGridView(
    grid: DecodedGrid,
    styles: List<Style>,
    fontSizeSp: Float = 13f, // caller (TerminalScreen, Task 8) always passes the live zoom size; default only keeps the pre-Task-8 call site compiling
    colors: TerminalColors = DefaultTerminalColors,
    modifier: Modifier = Modifier,
) {
    val styleMap = remember(styles) { styles.associateBy { it.id } }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val buffer = remember(grid) { grid.scrollbackLines + grid.lines }
    val cursorRow = grid.cursor?.takeIf { it.visible }?.let { it.row + grid.scrollbackLines.size }
    val cursorCol = grid.cursor?.column

    // Stick to bottom across frames: if we were at (or near) the previous bottom,
    // re-pin after the new content lays out. prevMax remembers the bottom before
    // this frame so growth above (scrollback) or below keeps the live screen visible.
    var prevMax by remember { mutableStateOf(0) }
    LaunchedEffect(buffer) {
        val wasAtBottom = scroll.value >= prevMax - 4
        if (wasAtBottom) scroll.scrollTo(scroll.maxValue)
        prevMax = scroll.maxValue
    }
    // The user has scrolled up from the live screen → show the jump-to-bottom FAB.
    val showJump by remember { derivedStateOf { scroll.value < scroll.maxValue - 4 } }

    Box(modifier = modifier.background(colors.background)) {
        SelectionContainer {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
                buffer.forEachIndexed { index, line ->
                    val cur = if (index == cursorRow) cursorCol else null
                    Text(
                        text = buildLine(line, styleMap, colors, cur),
                        fontFamily = TerminalFont,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * TerminalLineHeightFactor).sp,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
        if (showJump) {
            FloatingActionButton(
                onClick = { scope.launch { scroll.scrollTo(scroll.maxValue) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Text("▼") }
        }
    }
}

/** Groups consecutive same-style cells into styled runs; the cursor cell is inverted. */
internal fun buildLine(
    line: DecodedLine,
    styles: Map<Int, Style>,
    colors: TerminalColors,
    cursorColumn: Int?,
): AnnotatedString = buildAnnotatedString {
    val cells = line.cells
    var i = 0
    while (i < cells.size) {
        if (cursorColumn != null && i == cursorColumn) {
            withStyle(SpanStyle(color = colors.background, background = colors.cursor)) {
                append(cells[i].char)
            }
            i++
            continue
        }
        val styleId = cells[i].styleId
        val run = StringBuilder()
        while (i < cells.size && cells[i].styleId == styleId &&
            !(cursorColumn != null && i == cursorColumn)
        ) {
            run.append(cells[i].char)
            i++
        }
        val r = resolveSpan(styles[styleId], colors)
        withStyle(
            SpanStyle(
                color = r.fg,
                background = r.bg,
                fontWeight = if (r.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (r.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = decorationOf(r),
            ),
        ) { append(run.toString()) }
    }
}

private fun decorationOf(r: ResolvedSpan): TextDecoration? = when {
    r.underline && r.strikethrough ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    r.underline -> TextDecoration.Underline
    r.strikethrough -> TextDecoration.LineThrough
    else -> null
}
