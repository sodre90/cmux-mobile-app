package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.sodre90.cmuxremote.model.DecodedGrid
import com.sodre90.cmuxremote.model.DecodedLine
import com.sodre90.cmuxremote.model.Style

/**
 * Renders a [DecodedGrid] as a monospace, horizontally/vertically scrollable
 * grid. Each row becomes one no-wrap line of styled runs (fg/bg/bold).
 */
@Composable
fun RenderGridView(
    grid: DecodedGrid,
    styles: List<Style>,
    modifier: Modifier = Modifier,
) {
    val styleMap = remember(styles) { styles.associateBy { it.id } }
    val defaultColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        grid.lines.forEach { line ->
            Text(
                text = buildLine(line, styleMap, defaultColor),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

/** Groups consecutive same-style cells into styled runs. */
internal fun buildLine(
    line: DecodedLine,
    styles: Map<Int, Style>,
    defaultColor: Color,
): AnnotatedString = buildAnnotatedString {
    val cells = line.cells
    var i = 0
    while (i < cells.size) {
        val styleId = cells[i].styleId
        val run = StringBuilder()
        while (i < cells.size && cells[i].styleId == styleId) {
            run.append(cells[i].char)
            i++
        }
        val style = styles[styleId]
        withStyle(
            SpanStyle(
                color = parseColor(style?.foregroundString) ?: defaultColor,
                background = parseColor(style?.backgroundString) ?: Color.Unspecified,
                fontWeight = if (style?.bold == true) FontWeight.Bold else FontWeight.Normal,
            ),
        ) {
            append(run.toString())
        }
    }
}
