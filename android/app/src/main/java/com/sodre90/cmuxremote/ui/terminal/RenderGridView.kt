package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

/** Scrollback beyond this many lines is dropped from the render buffer -- the
 *  agent's own history is untouched, only what this view holds/composes. Without
 *  a ceiling, a long-lived session's buffer (and the per-frame `+` reallocation
 *  building it) grows without bound, and every one of those rows is composed
 *  since the Column below has no windowing. */
internal const val MaxScrollbackLines = 2000

/**
 * Renders a [DecodedGrid] (scrollback + visible screen) on a solid dark canvas:
 * one no-wrap styled line per row, the cursor drawn by inverting its cell, native
 * long-press selection, and stick-to-bottom follow with a jump-to-bottom button.
 */
@Composable
fun RenderGridView(
    grid: DecodedGrid,
    styles: List<Style>,
    // caller (TerminalScreen, Task 8) always passes the live zoom size; default
    // only keeps the pre-Task-8 call site compiling
    fontSizeSp: Float = 13f,
    colors: TerminalColors = DefaultTerminalColors,
    wrap: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val styleMap = remember(styles) { styles.associateBy { it.id } }
    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val buffer = remember(grid) { cappedScrollback(grid.scrollbackLines) + grid.lines }
    val cursorRow = grid.cursor?.takeIf { it.visible }?.let {
        it.row + minOf(grid.scrollbackLines.size, MaxScrollbackLines)
    }
    val cursorCol = grid.cursor?.column

    // Stick to bottom across frames: if we were at (or near) the previous bottom,
    // re-pin after the new content lays out. prevMax remembers the bottom before
    // this frame so growth above (scrollback) or below keeps the live screen visible.
    // Keyed on scroll.maxValue too, not just buffer -- maxValue also moves when the
    // viewport itself shrinks with no new content at all (e.g. the IME's own
    // suggestion strip growing taller as you type), and without that key this stayed
    // pinned to the *old* bottom, leaving the real bottom (and the cursor) below the
    // fold until you manually swiped down.
    var prevMax by remember { mutableStateOf(0) }
    LaunchedEffect(buffer, scroll.maxValue) {
        val wasAtBottom = scroll.value >= prevMax - 4
        if (wasAtBottom) scroll.scrollTo(scroll.maxValue)
        prevMax = scroll.maxValue
    }
    // The user has scrolled up from the live screen → show the jump-to-bottom FAB.
    val showJump by remember { derivedStateOf { scroll.value < scroll.maxValue - 4 } }

    // High-contrast selection on the dark canvas: the bright blue highlight plus an
    // opaque handle (Compose's default primary highlight was nearly invisible here).
    val selectionColors = remember(colors.selection) {
        TextSelectionColors(
            handleColor = colors.selection.copy(alpha = 1f),
            backgroundColor = colors.selection,
        )
    }
    Box(modifier = modifier.background(colors.background)) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            SelectionContainer {
                // wrap=false: draw each row at the surface's native width (no wrap) and
                // allow horizontal pan for the overflow when zoomed in past fit or at the
                // legibility floor. wrap=true: reflow long rows onto extra display lines so
                // zooming in wraps instead of sliding — the Column fills the viewport width
                // to give Text a wrap boundary, and no horizontalScroll is attached.
                val columnModifier = if (wrap) {
                    Modifier.fillMaxWidth().verticalScroll(scroll)
                } else {
                    Modifier.verticalScroll(scroll).horizontalScroll(hScroll)
                }
                Column(modifier = columnModifier) {
                    buffer.forEachIndexed { index, line ->
                        val cur = if (index == cursorRow) cursorCol else null
                        // In wrap mode, reshape the row: drop pure box-drawing borders, strip
                        // the rule glyphs off a titled separator (keeping its label), and trim
                        // trailing padding blanks. A null result means the row is dropped.
                        val rendered = if (wrap) {
                            wrapModeLine(line, cur) ?: return@forEachIndexed
                        } else {
                            line
                        }
                        val annotated = remember(rendered, styleMap, colors, cur) {
                            buildLine(rendered, styleMap, colors, cur)
                        }
                        Text(
                            text = annotated,
                            fontFamily = TerminalFont,
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp * TerminalLineHeightFactor).sp,
                            softWrap = wrap,
                            maxLines = if (wrap) Int.MAX_VALUE else 1,
                        )
                    }
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

// Box-drawing horizontals, corners and junctions, plus ASCII rule chars. A row made
// solely of these (and blanks) is a border/separator, not wrappable prose.
private val RuleChars = setOf(
    '─', '━', '┄', '┅', '┈', '┉', '╌', '╍', '═', '╾', '╼', '-', '=',
    '╭', '╮', '╰', '╯', '┌', '┐', '└', '┘',
    '├', '┤', '┬', '┴', '┼', '╞', '╡', '╪',
    '╔', '╗', '╚', '╝', '╠', '╣', '╦', '╩', '╬',
)

/** Keeps only the most recent [max] scrollback lines, oldest history dropped. */
internal fun cappedScrollback(scrollbackLines: List<DecodedLine>, max: Int = MaxScrollbackLines): List<DecodedLine> =
    if (scrollbackLines.size <= max) scrollbackLines else scrollbackLines.takeLast(max)

/** True if the row's only non-blank glyphs are box-drawing/ASCII rule characters. */
internal fun isHorizontalRule(line: DecodedLine): Boolean {
    var sawRule = false
    for (cell in line.cells) {
        if (cell.char == ' ') continue
        if (cell.char !in RuleChars) return false
        sawRule = true
    }
    return sawRule
}

/** Longest run of consecutive rule glyphs — a run of 4+ marks drawn decoration. */
private fun maxRuleRun(line: DecodedLine): Int {
    var max = 0
    var run = 0
    for (cell in line.cells) {
        if (cell.char in RuleChars) {
            run++
            if (run > max) max = run
        } else {
            run = 0
        }
    }
    return max
}

/**
 * Reshapes a row for wrap mode, or returns null to drop it:
 *  - a row carrying the cursor is only trailing-trimmed (kept intact for the cursor);
 *  - a pure border/separator row is dropped;
 *  - a titled separator (`──── label ────`, a 4+ rule-glyph run around text) keeps
 *    only the label, dropping the box decoration that would otherwise wrap badly;
 *  - everything else is just trailing-trimmed.
 */
internal fun wrapModeLine(line: DecodedLine, cursorColumn: Int?): DecodedLine? {
    if (cursorColumn != null && cursorColumn in line.cells.indices) {
        return trimTrailingBlanks(line, cursorColumn)
    }
    if (isHorizontalRule(line)) return null
    if (maxRuleRun(line) >= 4) {
        return trimTrailingBlanks(DecodedLine(line.cells.filterNot { it.char in RuleChars }), null)
    }
    return trimTrailingBlanks(line, null)
}

/**
 * Drops a row's trailing padding blanks (space + default style 0, as the decoder
 * fills empty cells) so wrapping doesn't push them onto extra display lines. Keeps
 * styled trailing spaces (e.g. a colored status bar that extends to the edge) and
 * never trims past the cursor cell, so it can still be drawn. A fully blank row
 * collapses to an empty line, preserving its vertical slot.
 */
internal fun trimTrailingBlanks(line: DecodedLine, cursorColumn: Int?): DecodedLine {
    val cells = line.cells
    var last = -1
    for (i in cells.indices) {
        if (cells[i].char != ' ' || cells[i].styleId != 0) last = i
    }
    val cur = cursorColumn?.takeIf { it in cells.indices } ?: -1
    last = maxOf(last, cur)
    return if (last >= cells.size - 1) line else DecodedLine(cells.subList(0, last + 1))
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
