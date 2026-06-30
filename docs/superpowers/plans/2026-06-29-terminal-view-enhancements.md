# Terminal View Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android cmux terminal view readable at phone size, faithful to cmux's colors/styles/cursor, able to follow + scroll streaming output, with rich keys/copy/paste, pinch-zoom, and reconnect.

**Architecture:** Harden the existing `decode → DecodedGrid → Compose Text-per-line` pipeline in `ui/terminal/`. Heavy logic lives in small pure functions (geometry, style resolution, key map, decoder) that are unit-tested with JUnit4; the Compose views are kept thin over them and verified on the running emulator. No new Gradle dependencies.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, foundation, ui-text), kotlinx.serialization, JUnit4. Build with Gradle (`./gradlew`), JDK 21 (Corretto), Android SDK 35.

## Global Constraints

- Commits authored **solely** by `sodre90 <erdos.peter.bme@gmail.com>`; **never** add `Co-Authored-By` or any AI-attribution trailer. The repo's local git identity is already `sodre90`.
- minSdk 26, compile/target 35, JVM target 17 (unchanged). Do not add Gradle dependencies.
- Don't touch unrelated code; follow existing `ui/terminal/` patterns.
- Input model is **line-based + keys + copy/paste** (no raw per-keystroke streaming).
- Scrollback + incremental handling: each `full` frame is authoritative; scrollback is best-effort; do **not** implement `full:false` delta merge or CJK `cell_width` or an ANSI-256/named palette.
- Spec: `docs/superpowers/specs/2026-06-29-terminal-view-enhancements-design.md`.

### Environment preamble (run once per shell before any command below)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=$HOME/Library/Android/sdk
export ADB=$ANDROID_HOME/platform-tools/adb
cd /Users/perdos/prj/cmux-app/android
```

- Unit tests: `./gradlew :app:testDebugUnitTest --tests "<pattern>"`
- Build APK: `./gradlew :app:assembleDebug`
- Install on the running `cmux_test` emulator: `$ADB install -r app/build/outputs/apk/debug/app-debug.apk`
- The emulator already has the app configured (base URL, phone-1 cert, token), so opening a session connects live.

## File Structure

| File | New/Modify | Responsibility |
|------|-----------|----------------|
| `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalColors.kt` | New | Terminal canvas color palette + default |
| `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalStyle.kt` | New | `parseColor`, `resolveSpan` (inverse/faint/decorations) |
| `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalGeometry.kt` | New | `gridDimensions`, `zoomedFontSizeSp` |
| `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalKeys.kt` | New | Key bar label→sequence map |
| `app/src/main/java/com/sodre90/cmuxremote/model/RenderGrid.kt` | Modify | Decode `scrollback_spans` into `DecodedGrid.scrollbackLines` |
| `app/src/main/res/font/jetbrains_mono_nerd_regular.ttf` `_bold.ttf` | New | Bundled Nerd Font |
| `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt` | Modify | Dark canvas, font, full styles, cursor, selection, follow + jump FAB |
| `app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt` | Modify | Log dropped frames |
| `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt` | Modify | `reconnect()` |
| `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalScreen.kt` | Modify | Cell measure, pinch-zoom, resize, key bar, paste, reconnect button |
| Tests under `app/src/test/java/com/sodre90/cmuxremote/...` | New/Modify | One per pure unit |

---

## Task 1: Terminal colors + style resolver

**Files:**
- Create: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalColors.kt`
- Create: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalStyle.kt`
- Modify: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt` (delete its now-duplicate `parseColor`)
- Test: `app/src/test/java/com/sodre90/cmuxremote/ui/terminal/TerminalStyleTest.kt`

**Interfaces:**
- Produces: `data class TerminalColors(background: Color, foreground: Color, cursor: Color, selection: Color, faintAlpha: Float = 0.6f)`; `val DefaultTerminalColors`; `fun parseColor(value: String?): Color?`; `data class ResolvedSpan(fg: Color, bg: Color, bold: Boolean, italic: Boolean, underline: Boolean, strikethrough: Boolean)`; `fun resolveSpan(style: Style?, colors: TerminalColors): ResolvedSpan`.
- Consumes: `com.sodre90.cmuxremote.model.Style` (fields `foregroundString`, `backgroundString`, `bold`, `faint`, `italic`, `underline`, `inverse`, `strikethrough`).
- **Move, not duplicate:** `parseColor` currently lives as an `internal fun` in `RenderGridView.kt`. This task moves it to `TerminalStyle.kt` as a public `fun`. Two top-level `parseColor` in the same package would be a conflicting-declarations error, so Step 4b deletes the old one. The old `buildLine` in `RenderGridView.kt` keeps compiling — its `parseColor(...)` calls resolve to the moved public function (same package, identical behavior).

- [ ] **Step 1: Write the failing test**

`TerminalStyleTest.kt`:

```kotlin
package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Style
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStyleTest {
    private val colors = TerminalColors(
        background = Color(0xFF000000),
        foreground = Color(0xFFFFFFFF),
        cursor = Color(0xFFFF0000),
        selection = Color(0xFF333333),
    )

    @Test fun usesStyleColors() {
        val s = Style(id = 1, foreground = JsonPrimitive("#00ff00"), background = JsonPrimitive("#112233"))
        val r = resolveSpan(s, colors)
        assertEquals(Color(0xFF00FF00), r.fg)
        assertEquals(Color(0xFF112233), r.bg)
    }

    @Test fun fallsBackToThemeWhenNoColor() {
        val r = resolveSpan(Style(id = 0), colors)
        assertEquals(colors.foreground, r.fg)
        assertEquals(colors.background, r.bg)
    }

    @Test fun inverseSwapsForegroundAndBackground() {
        val s = Style(id = 1, foreground = JsonPrimitive("#00ff00"), background = JsonPrimitive("#112233"), inverse = true)
        val r = resolveSpan(s, colors)
        assertEquals(Color(0xFF112233), r.fg)
        assertEquals(Color(0xFF00FF00), r.bg)
    }

    @Test fun faintReducesForegroundAlpha() {
        val s = Style(id = 1, foreground = JsonPrimitive("#ffffff"), faint = true)
        val r = resolveSpan(s, colors)
        assertEquals(0.6f, r.fg.alpha, 0.001f)
    }

    @Test fun carriesDecorationFlags() {
        val s = Style(id = 1, bold = true, italic = true, underline = true, strikethrough = true)
        val r = resolveSpan(s, colors)
        assertTrue(r.bold); assertTrue(r.italic); assertTrue(r.underline); assertTrue(r.strikethrough)
    }

    @Test fun parsesHexColors() {
        assertEquals(Color(0xFF00FF00), parseColor("#00ff00"))
        assertEquals(null, parseColor("blue"))
        assertEquals(null, parseColor(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*TerminalStyleTest"`
Expected: FAIL — `TerminalColors` / `resolveSpan` unresolved (compilation error). (`parseColor` still exists as the old `internal` fun at this point; it's moved in Step 4/4b.)

- [ ] **Step 3: Write `TerminalColors.kt`**

```kotlin
package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color

/** Colors for the terminal canvas, independent of the app's Material theme. */
data class TerminalColors(
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selection: Color,
    val faintAlpha: Float = 0.6f,
)

/** Catppuccin-Mocha-ish dark canvas; tweak freely. */
val DefaultTerminalColors = TerminalColors(
    background = Color(0xFF1E1E2E),
    foreground = Color(0xFFCDD6F4),
    cursor = Color(0xFFF5E0DC),
    selection = Color(0x553B4261),
)
```

- [ ] **Step 4: Write `TerminalStyle.kt`** (move `parseColor` here from `RenderGridView.kt`)

```kotlin
package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Style

/** A fully resolved span, ready to map onto a Compose SpanStyle. */
data class ResolvedSpan(
    val fg: Color,
    val bg: Color,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
)

/** Parses `#rrggbb` / `#aarrggbb` to a [Color]; returns null for other forms. */
fun parseColor(value: String?): Color? {
    val hex = value?.removePrefix("#") ?: return null
    return when (hex.length) {
        6 -> runCatching { Color("FF$hex".toLong(16)) }.getOrNull()
        8 -> runCatching { Color(hex.toLong(16)) }.getOrNull()
        else -> null
    }
}

/** Resolves a cmux [Style] against the terminal [colors], applying inverse/faint. */
fun resolveSpan(style: Style?, colors: TerminalColors): ResolvedSpan {
    var fg = parseColor(style?.foregroundString) ?: colors.foreground
    var bg = parseColor(style?.backgroundString) ?: colors.background
    if (style?.inverse == true) {
        val swap = fg; fg = bg; bg = swap
    }
    if (style?.faint == true) {
        fg = fg.copy(alpha = fg.alpha * colors.faintAlpha)
    }
    return ResolvedSpan(
        fg = fg,
        bg = bg,
        bold = style?.bold == true,
        italic = style?.italic == true,
        underline = style?.underline == true,
        strikethrough = style?.strikethrough == true,
    )
}
```

- [ ] **Step 4b: Delete the old `parseColor` from `RenderGridView.kt`**

`RenderGridView.kt` currently ends with an `internal fun parseColor` (the last function in the file). Delete exactly that block so only the moved `TerminalStyle.parseColor` remains:

```kotlin
/** Parses `#rrggbb` / `#aarrggbb` to a [Color]; returns null for other forms. */
internal fun parseColor(value: String?): Color? {
    val hex = value?.removePrefix("#") ?: return null
    return when (hex.length) {
        6 -> runCatching { Color("FF$hex".toLong(16)) }.getOrNull()
        8 -> runCatching { Color(hex.toLong(16)) }.getOrNull()
        else -> null
    }
}
```

Leave the rest of `RenderGridView.kt` untouched — its `buildLine` still calls `parseColor(...)`, now resolving to the moved public function. Do not remove the `import androidx.compose.ui.graphics.Color` (still used by `buildLine`).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*TerminalStyleTest"`
Expected: PASS (6 tests). The whole `:app` module compiles (no duplicate `parseColor`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalColors.kt \
        app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalStyle.kt \
        app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt \
        app/src/test/java/com/sodre90/cmuxremote/ui/terminal/TerminalStyleTest.kt
git commit -m "Add terminal color palette and style resolver"
```

---

## Task 2: Terminal geometry + zoom math

**Files:**
- Create: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalGeometry.kt`
- Test: `app/src/test/java/com/sodre90/cmuxremote/ui/terminal/TerminalGeometryTest.kt`

**Interfaces:**
- Produces: `fun gridDimensions(widthPx: Float, heightPx: Float, cellWidthPx: Float, cellHeightPx: Float, minCols: Int = 20, maxCols: Int = 400, minRows: Int = 5, maxRows: Int = 200): Pair<Int, Int>`; `fun zoomedFontSizeSp(baseSp: Float, scale: Float, min: Float = 7f, max: Float = 22f): Float`.

- [ ] **Step 1: Write the failing test**

`TerminalGeometryTest.kt`:

```kotlin
package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalGeometryTest {
    @Test fun fitsColumnsAndRows() {
        val (cols, rows) = gridDimensions(800f, 1500f, 8f, 15f)
        assertEquals(100, cols)
        assertEquals(100, rows)
    }

    @Test fun clampsToBounds() {
        val (cols, rows) = gridDimensions(50f, 30f, 8f, 15f)
        assertEquals(20, cols)
        assertEquals(5, rows)
    }

    @Test fun guardsZeroCell() {
        assertEquals(20 to 5, gridDimensions(800f, 1500f, 0f, 0f))
    }

    @Test fun zoomClampsToRange() {
        assertEquals(22f, zoomedFontSizeSp(13f, 5f), 0.001f)
        assertEquals(7f, zoomedFontSizeSp(13f, 0.1f), 0.001f)
        assertEquals(13f, zoomedFontSizeSp(13f, 1f), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*TerminalGeometryTest"`
Expected: FAIL — `gridDimensions` / `zoomedFontSizeSp` unresolved.

- [ ] **Step 3: Write `TerminalGeometry.kt`**

```kotlin
package com.sodre90.cmuxremote.ui.terminal

import kotlin.math.floor

/** Visible columns/rows that fit a viewport given a measured monospace cell. */
fun gridDimensions(
    widthPx: Float,
    heightPx: Float,
    cellWidthPx: Float,
    cellHeightPx: Float,
    minCols: Int = 20,
    maxCols: Int = 400,
    minRows: Int = 5,
    maxRows: Int = 200,
): Pair<Int, Int> {
    if (cellWidthPx <= 0f || cellHeightPx <= 0f) return minCols to minRows
    val cols = floor(widthPx / cellWidthPx).toInt().coerceIn(minCols, maxCols)
    val rows = floor(heightPx / cellHeightPx).toInt().coerceIn(minRows, maxRows)
    return cols to rows
}

/** Maps an accumulated pinch [scale] onto a clamped font size in sp. */
fun zoomedFontSizeSp(baseSp: Float, scale: Float, min: Float = 7f, max: Float = 22f): Float =
    (baseSp * scale).coerceIn(min, max)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*TerminalGeometryTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalGeometry.kt \
        app/src/test/java/com/sodre90/cmuxremote/ui/terminal/TerminalGeometryTest.kt
git commit -m "Add terminal geometry and zoom math"
```

---

## Task 3: Key bar sequence map

**Files:**
- Create: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalKeys.kt`
- Test: `app/src/test/java/com/sodre90/cmuxremote/ui/terminal/TerminalKeysTest.kt`

**Interfaces:**
- Produces: `val TerminalKeys: List<Pair<String, String>>` (label → byte sequence).

- [ ] **Step 1: Write the failing test**

> IMPORTANT: write escape sequences with explicit unicode escapes (`\u001b` for ESC, `\u0003` for Ctrl-C). Never paste raw control bytes into source — they are invisible and get silently stripped, producing wrong assertions (a stripped ESC makes a broken arrow key).

`TerminalKeysTest.kt`:

```kotlin
package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeysTest {
    private val map = TerminalKeys.toMap()

    @Test fun arrowUpIsCsiA() = assertEquals("\u001b[A", map["↑"])
    @Test fun pageUpIsCsi5Tilde() = assertEquals("\u001b[5~", map["PgUp"])
    @Test fun ctrlCIsEtx() = assertEquals("\u0003", map["^C"])
    @Test fun homeIsCsiH() = assertEquals("\u001b[H", map["Home"])
    @Test fun endIsCsiF() = assertEquals("\u001b[F", map["End"])
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*TerminalKeysTest"`
Expected: FAIL — `TerminalKeys` unresolved.

- [ ] **Step 3: Write `TerminalKeys.kt`**

```kotlin
package com.sodre90.cmuxremote.ui.terminal

// Control sequences built from code points so no raw control bytes live in source.
private val ESC = Char(27).toString()
private val CTRL_C = Char(3).toString()
private val CTRL_D = Char(4).toString()
private val CTRL_Z = Char(26).toString()

/** Label → byte sequence for the terminal key bar. */
val TerminalKeys: List<Pair<String, String>> = listOf(
    "Esc" to ESC,
    "Tab" to "\t",
    "^C" to CTRL_C,
    "^D" to CTRL_D,
    "^Z" to CTRL_Z,
    "↑" to ESC + "[A",
    "↓" to ESC + "[B",
    "←" to ESC + "[D",
    "→" to ESC + "[C",
    "PgUp" to ESC + "[5~",
    "PgDn" to ESC + "[6~",
    "Home" to ESC + "[H",
    "End" to ESC + "[F",
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*TerminalKeysTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalKeys.kt \
        app/src/test/java/com/sodre90/cmuxremote/ui/terminal/TerminalKeysTest.kt
git commit -m "Add expanded terminal key bar sequence map"
```

---

## Task 4: Decode scrollback into the grid

**Files:**
- Modify: `app/src/main/java/com/sodre90/cmuxremote/model/RenderGrid.kt`
- Test: `app/src/test/java/com/sodre90/cmuxremote/model/RenderGridDecoderTest.kt`

**Interfaces:**
- Consumes: `RenderGrid` (existing). Adds field `@SerialName("scrollback_spans") val scrollbackSpans: List<RowSpan> = emptyList()`.
- Produces: `DecodedGrid` gains `val scrollbackLines: List<DecodedLine> = emptyList()`; `RenderGridDecoder.decode` populates it from `scrollbackSpans` over `scrollbackRows` rows. **Assumption (unconfirmed live):** scrollback spans use row indices `0..scrollbackRows-1`. Confirm against a busy session (see Task 8 verification); if indices differ, only `layout()` here changes.

- [ ] **Step 1: Add the failing tests** (append to `RenderGridDecoderTest.kt`)

```kotlin
    @Test
    fun decodesScrollbackLinesBeforeVisible() {
        val js = """
            {"columns":5,"rows":1,"scrollback_rows":2,
             "scrollback_spans":[
               {"row":0,"column":0,"style_id":0,"text":"old1"},
               {"row":1,"column":0,"style_id":0,"text":"old2"}],
             "row_spans":[{"row":0,"column":0,"style_id":0,"text":"live"}]}
        """.trimIndent()
        val d = RenderGridDecoder.decode(BridgeJson.decodeFromString(RenderGrid.serializer(), js))
        assertEquals(2, d.scrollbackLines.size)
        assertEquals("old1 ", d.scrollbackLines[0].text)
        assertEquals("old2 ", d.scrollbackLines[1].text)
        assertEquals("live ", d.lines[0].text)
    }

    @Test
    fun scrollbackEmptyWhenAbsent() {
        val d = RenderGridDecoder.decode(grid())
        assertTrue(d.scrollbackLines.isEmpty())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*RenderGridDecoderTest"`
Expected: FAIL — `scrollbackLines` unresolved.

- [ ] **Step 3: Add `scrollbackSpans` to `RenderGrid`** (after the `rowSpans` field, ~line 21)

```kotlin
    @SerialName("row_spans") val rowSpans: List<RowSpan> = emptyList(),
    @SerialName("scrollback_spans") val scrollbackSpans: List<RowSpan> = emptyList(),
```

- [ ] **Step 4: Add `scrollbackLines` to `DecodedGrid`** (replace the existing `data class DecodedGrid`)

```kotlin
data class DecodedGrid(
    val columns: Int,
    val rows: Int,
    val lines: List<DecodedLine>,
    val cursor: Cursor?,
    val scrollbackLines: List<DecodedLine> = emptyList(),
)
```

- [ ] **Step 5: Refactor `decode` to share layout and emit scrollback** (replace the `RenderGridDecoder` object body)

```kotlin
object RenderGridDecoder {
    private const val BLANK = ' '

    /** Expand the sparse [grid] into a dense [DecodedGrid] (visible + scrollback). */
    fun decode(grid: RenderGrid): DecodedGrid {
        val cols = grid.columns.coerceAtLeast(0)
        val rowCount = grid.rows.coerceAtLeast(0)
        val lines = layout(grid.rowSpans, rowCount, cols)

        val sbRows = grid.scrollbackRows.coerceAtLeast(0)
        val scrollback =
            if (sbRows == 0 || grid.scrollbackSpans.isEmpty()) emptyList()
            else layout(grid.scrollbackSpans, sbRows, cols)

        return DecodedGrid(cols, rowCount, lines, grid.cursor, scrollback)
    }

    /** Lay [spans] onto a dense [rowCount] x [cols] grid of cells (width-1 chars). */
    private fun layout(spans: List<RowSpan>, rowCount: Int, cols: Int): List<DecodedLine> {
        val cells = Array(rowCount) { Array(cols) { Cell(BLANK, 0) } }
        for (span in spans) {
            if (span.row < 0 || span.row >= rowCount) continue
            var col = span.column
            for (ch in span.text) {
                if (col >= cols) break
                if (col >= 0) cells[span.row][col] = Cell(ch, span.styleId)
                col++
            }
        }
        return cells.map { DecodedLine(it.toList()) }
    }
}
```

- [ ] **Step 6: Run the full decoder test class to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*RenderGridDecoderTest"`
Expected: PASS (existing 5 + 2 new = 7 tests). Existing cases stay green because `layout` is the old logic extracted verbatim.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sodre90/cmuxremote/model/RenderGrid.kt \
        app/src/test/java/com/sodre90/cmuxremote/model/RenderGridDecoderTest.kt
git commit -m "Decode render-grid scrollback into DecodedGrid"
```

---

## Task 5: Bundle the Nerd Font

**Files:**
- Create: `app/src/main/res/font/jetbrains_mono_nerd_regular.ttf`
- Create: `app/src/main/res/font/jetbrains_mono_nerd_bold.ttf`
- Create: `THIRD_PARTY_LICENSES/JetBrainsMono-OFL.txt`

**Interfaces:** Produces resource ids `R.font.jetbrains_mono_nerd_regular` and `R.font.jetbrains_mono_nerd_bold` (consumed by Task 6).

> Note: `res/font/` accepts only font files — keep the license **out** of `res/font/`.

- [ ] **Step 1: Download and place the fonts**

```bash
curl -sL -o /tmp/JBM.zip https://github.com/ryanoasis/nerd-fonts/releases/download/v3.3.0/JetBrainsMono.zip
mkdir -p app/src/main/res/font /Users/perdos/prj/cmux-app/THIRD_PARTY_LICENSES
unzip -p /tmp/JBM.zip JetBrainsMonoNerdFontMono-Regular.ttf > app/src/main/res/font/jetbrains_mono_nerd_regular.ttf
unzip -p /tmp/JBM.zip JetBrainsMonoNerdFontMono-Bold.ttf    > app/src/main/res/font/jetbrains_mono_nerd_bold.ttf
unzip -p /tmp/JBM.zip LICENSE > /Users/perdos/prj/cmux-app/THIRD_PARTY_LICENSES/JetBrainsMono-OFL.txt
```

- [ ] **Step 2: Verify the files are valid TrueType and non-empty**

```bash
ls -l app/src/main/res/font/
file app/src/main/res/font/jetbrains_mono_nerd_regular.ttf
```
Expected: two files ~2.4 MB each; `file` reports "TrueType Font" / "OpenType font data".

- [ ] **Step 3: Verify resources compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (aapt registers `R.font.jetbrains_mono_nerd_regular` / `_bold`). The fonts aren't referenced yet — this only confirms they're valid resources.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/font/jetbrains_mono_nerd_regular.ttf \
        app/src/main/res/font/jetbrains_mono_nerd_bold.ttf \
        THIRD_PARTY_LICENSES/JetBrainsMono-OFL.txt
git commit -m "Bundle JetBrainsMono Nerd Font for the terminal"
```

---

## Task 6: Rewrite RenderGridView (canvas, font, styles, cursor, selection, follow)

**Files:**
- Modify: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt`
- Test: `app/src/test/java/com/sodre90/cmuxremote/ui/terminal/RenderGridViewTest.kt`

**Interfaces:**
- Consumes: `DecodedGrid` (+`scrollbackLines`, `cursor`), `Style`, `resolveSpan`, `ResolvedSpan`, `TerminalColors`, `DefaultTerminalColors`, `R.font.*`.
- Produces: `val TerminalFont: FontFamily`; `@Composable fun RenderGridView(grid: DecodedGrid, styles: List<Style>, fontSizeSp: Float = 13f, colors: TerminalColors = DefaultTerminalColors, modifier: Modifier = Modifier)`; `internal fun buildLine(line: DecodedLine, styles: Map<Int, Style>, colors: TerminalColors, cursorColumn: Int?): AnnotatedString` (consumed by the test only).
- **Why `fontSizeSp` has a default:** the only caller, `TerminalScreen`, is not updated until Task 8. Without a default, the still-3-arg call site (`RenderGridView(grid=…, styles=…, modifier=…)`) would fail to compile, and `testDebugUnitTest` compiles the whole `:app` main source set — so this task's own test step would not compile. The default keeps the module green now; Task 8 always passes `fontSizeSp` explicitly.

- [ ] **Step 1: Write the failing test** (`RenderGridViewTest.kt`)

```kotlin
package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Cell
import com.sodre90.cmuxremote.model.DecodedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RenderGridViewTest {
    private val colors = TerminalColors(
        background = Color(0xFF101010),
        foreground = Color(0xFFEEEEEE),
        cursor = Color(0xFFFF0000),
        selection = Color(0xFF333333),
    )
    private val line = DecodedLine(listOf(Cell('a', 0), Cell('b', 0), Cell('c', 0)))

    @Test fun preservesLineText() {
        val s = buildLine(line, emptyMap(), colors, cursorColumn = null)
        assertEquals("abc", s.text)
    }

    @Test fun cursorCellUsesCursorBackground() {
        val s = buildLine(line, emptyMap(), colors, cursorColumn = 1)
        val span = s.spanStyles.firstOrNull { it.start == 1 && it.end == 2 }
        assertNotNull(span)
        assertEquals(colors.cursor, span!!.item.background)
        assertEquals(colors.background, span.item.color)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RenderGridViewTest"`
Expected: FAIL — `buildLine` signature changed / unresolved (`cursorColumn` param, `colors` param).

- [ ] **Step 3: Replace `RenderGridView.kt` entirely**

```kotlin
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
                        lineHeight = (fontSizeSp * 1.25f).sp,
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
```

- [ ] **Step 4: Run the unit test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*RenderGridViewTest"`
Expected: PASS (2 tests). The whole `:app` module compiles: `TerminalScreen` still uses the 3-arg call, which is valid because `fontSizeSp` now defaults. Task 8 replaces that call with an explicit `fontSizeSp`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt \
        app/src/test/java/com/sodre90/cmuxremote/ui/terminal/RenderGridViewTest.kt
git commit -m "Render terminal grid with dark canvas, full styles, cursor, selection, follow"
```

---

## Task 7: ViewModel reconnect + log dropped frames

**Files:**
- Modify: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt`
- Modify: `app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt`

**Interfaces:**
- Produces: `TerminalViewModel.reconnect()` (re-subscribes the socket, resets to loading). `TerminalUiState` unchanged (cursor/scrollback travel inside `grid`).

- [ ] **Step 1: Replace the body of `TerminalViewModel`** (keep the package + `TerminalUiState`)

```kotlin
class TerminalViewModel(
    container: AppContainer,
    surfaceId: String,
) : ViewModel() {

    private val socket = container.terminalSocket(surfaceId)
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()
    private var job: Job? = null

    init { connect() }

    /** (Re)subscribe to the terminal stream, resetting to the loading state. */
    fun reconnect() = connect()

    private fun connect() {
        val s = socket
        if (s == null) {
            _state.value = TerminalUiState(error = "Bridge not configured")
            return
        }
        job?.cancel()
        _state.value = TerminalUiState() // loading (grid == null, error == null)
        job = viewModelScope.launch {
            try {
                s.connect().collect { frame ->
                    val rg = frame.grid ?: return@collect
                    _state.value = TerminalUiState(
                        grid = RenderGridDecoder.decode(rg),
                        styles = rg.styles,
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Terminal disconnected")
            }
        }
    }

    fun sendText(text: String) {
        socket?.send(TerminalUp(type = "input", text = text))
    }

    fun resize(columns: Int, rows: Int) {
        socket?.send(TerminalUp(type = "resize", columns = columns, rows = rows))
    }

    override fun onCleared() {
        socket?.close()
    }
}
```

Add the import near the top: `import kotlinx.coroutines.Job`.

- [ ] **Step 2: Log dropped frames in `TerminalSocket.onMessage`** (replace the `onMessage(text)` override)

```kotlin
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { BridgeJson.decodeFromString(TerminalDown.serializer(), text) }
                    .onFailure { android.util.Log.w("TerminalSocket", "dropped frame: ${it.message}") }
                    .getOrNull()
                    ?.let { trySend(it) }
            }
```

- [ ] **Step 3: Run the existing socket tests (must stay green)**

Run: `./gradlew :app:testDebugUnitTest --tests "*TerminalSocketTest"`
Expected: PASS. If it fails with `Method w in android.util.Log not mocked`, add to `android { }` in `app/build.gradle.kts`:

```kotlin
    testOptions { unitTests { isReturnDefaultValues = true } }
```

then re-run — expected PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt \
        app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt
# include app/build.gradle.kts only if Step 3 required it
git commit -m "Add terminal reconnect and log dropped frames"
```

---

## Task 8: Wire TerminalScreen (cell measure, pinch-zoom, resize, keys, paste, reconnect)

**Files:**
- Modify: `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalScreen.kt`

**Interfaces:**
- Consumes: `RenderGridView(grid, styles, fontSizeSp, colors, modifier)`, `TerminalKeys`, `gridDimensions`, `zoomedFontSizeSp`, `TerminalFont`, `DefaultTerminalColors`, `vm.reconnect()`, `vm.resize()`, `vm.sendText()`.

- [ ] **Step 1: Replace `TerminalScreen.kt` entirely**

```kotlin
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
```

- [ ] **Step 2: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (This is the compile gate for Tasks 6+8 together.)

> If the compiler flags `rememberTextMeasurer` / `measurer.measure(...)` as experimental on this Compose BOM, add `@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)` to `TerminalScreen` (the `measure` AnnotatedString overload was stabilized in Compose UI 1.6; older BOMs need the opt-in).

- [ ] **Step 3: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing + new tests).

- [ ] **Step 4: Install and verify on the emulator**

```bash
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.sodre90.cmuxremote/.MainActivity
```

Open a **terminal** session (e.g. `sodre90@home-server`) and confirm visually
(`$ADB exec-out screencap -p > /tmp/term.png`):
- Solid dark canvas; powerline/nerd glyphs render (no `□` boxes).
- Text fits the width (no horizontal scrolling needed); colors look right.
- A block cursor is visible at the prompt.
- Pinch out/in changes text size and the columns reflow to fit.
- The key bar scrolls and shows Esc…End; Paste inserts clipboard text.

Then open a **busy agent** session and confirm:
- Output is readable and follows live (stays pinned to the bottom).
- Scrolling up reveals history and shows the ▼ jump-to-bottom button; tapping it
  snaps back to live. **Confirm the scrollback decode assumption (Task 4):** if
  history lines are misordered or blank, capture a frame and adjust `layout()`'s
  row indexing.

- [ ] **Step 5: Verify reconnect**

Open one of the **instantly-closing** secondary surfaces (a duplicate session
entry). Expect the error state with a **Reconnect** button; tapping it re-attempts
(and shows the same error or connects). Confirm no crash.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalScreen.kt
git commit -m "Wire terminal screen: measured fit-to-width, pinch-zoom, keys, paste, reconnect"
```

---

## Notes for the implementer

- **`RenderGridView`'s `fontSizeSp` defaults (Task 6)** specifically so the module compiles before Task 8 updates the `TerminalScreen` call site. Don't remove that default until Task 8 passes the argument explicitly. Task 8's `assembleDebug` is still the integration gate that proves the new screen wiring compiles.
- **Unit tests run on plain JVM** (no Robolectric). `androidx.compose.ui.graphics.Color`, `buildAnnotatedString`, `SpanStyle`, `FontWeight/Style`, `TextDecoration` are all JVM-safe. Only `android.util.Log` (Task 7) may need `unitTests.isReturnDefaultValues = true`.
- **Scrollback indexing is the one unconfirmed assumption.** It's isolated to `RenderGridDecoder.layout()`; Task 8 Step 4 is where you confirm it against real history.
- **Deliberate spec divergence:** the spec's `shouldStickToBottom(wasAtBottom)` "trivial unit test" is intentionally omitted. Stick-to-bottom is the `prevMax` logic inside `RenderGridView` (Task 6) and is verified on the emulator (Task 8 Step 4) — a pure identity helper would test nothing. Follow logic is the most likely UI to need on-device tuning.
- Keep YAGNI: no raw keystroke mode, no CJK `cell_width`, no incremental-frame merge, no 256/named palette.
```
