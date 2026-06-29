# Terminal View Enhancements — Design

**Date:** 2026-06-29
**Component:** Android app (`android/`, package `com.sodre90.cmuxremote`), terminal view.
**Status:** Approved direction; pending user review of this spec.

## Goal

Make the in-app terminal view (`ui/terminal/`) a comfortable surface for both
watching and driving live cmux sessions from a phone: readable at phone size,
faithful to cmux's colors/styles/cursor, able to follow streaming output and
scroll back through it, with practical input (rich keys, copy, paste) and a way
to recover from dropped/closed surfaces.

## Background — current state

The terminal view already works end-to-end (app → HA nginx mTLS → relay →
tunnel → Mac agent → cmux). Today:

- `TerminalSocket` streams `TerminalDown` frames (`cmux.render-grid.v1`);
  `TerminalViewModel` decodes each via `RenderGridDecoder.decode` into a
  `DecodedGrid` and exposes `TerminalUiState(grid, styles, error)`.
- `TerminalScreen` shows a spinner / error / grid. For the grid it estimates
  `cols/rows` from screen size (`cell ≈ 7.2dp × 14dp` guess), calls
  `vm.resize(cols, rows)`, and renders `RenderGridView`.
- `RenderGridView` lays out each grid line as one no-wrap monospace `Text`
  (`FontFamily.Monospace`, 12sp) inside vertical **and horizontal** scroll, with
  per-run `fg / bg / bold` only.
- A scrollable key bar (Esc, Tab, Ctrl-C, arrows) plus a line input + Send.

### Confirmed against live data (read-only WS probes, 2026-06-29)

- Style colors arrive as **hex strings** (`"#FEFFFF"` / `"#040404"`); the
  existing `parseColor` handles them. No palette-int path observed.
- Both **terminal and agent** surfaces stream a render-grid (an agent surface
  returned a full `170×79` grid, 420 spans), so these enhancements apply to all
  session kinds.
- All observed frames were `full=true` replays; `scrollback_rows` was `0` on the
  (idle) sessions probed — **populated scrollback and incremental (`full:false`)
  frames were not observed**, so their exact shape is unconfirmed.
- **Some surface ids close the socket immediately.** The `GET /sessions` list
  contains duplicate entries per session; the secondary id's `/terminal/<id>`
  stream closes with no frame. Root cause lives in the sessions list, not the
  terminal view (tracked separately); the terminal view needs to *recover*
  gracefully when a stream closes.

## Scope

### In scope (v1)

1. **Readability & fidelity:** fit-to-width sizing (no horizontal scroll), solid
   dark terminal canvas, visible cursor, full text styles
   (inverse/underline/italic/faint/strikethrough, not just fg/bg/bold).
2. **Glyphs & theming:** bundle a monospace Nerd Font so powerline/nerd glyphs
   render; apply a consistent terminal color scheme.
3. **Follow output (best-effort):** render scrollback (when present) + the live
   screen as one buffer; stick to bottom when already at bottom; a
   jump-to-bottom control otherwise.
4. **Input ergonomics:** expanded key bar; long-press select → Copy; Paste;
   keep the line input + Send.
5. **Pinch-to-zoom** font sizing (recomputes columns and resizes cmux).
6. **Reconnect** affordance on the error/closed-stream state.

### Out of scope (v1) — YAGNI

- Raw per-keystroke streaming mode (user chose line-based input).
- CJK/wide-character handling via `cell_width` (not seen in live data;
  decoder keeps width-1 layout).
- Incremental-frame (`full:false`) merge — each `full` frame is treated as
  authoritative. Revisit when incremental frames are observed.
- Full ANSI-256 / named-color palette (live data is hex). The style resolver is
  structured so a palette lookup can slot in later.
- Fixing the duplicate-session list entries / dead secondary surfaces (separate
  issue; the terminal view only handles the symptom via Reconnect).
- Blinking cursor animation (static cursor in v1).

## Global constraints

- **Commits:** authored solely by `sodre90 <erdos.peter.bme@gmail.com>`; **no**
  `Co-Authored-By` / AI-attribution trailers.
- **Min SDK 26**, compile/target 35 (unchanged).
- **Build:** `cd android && JAVA_HOME=<corretto-21> ANDROID_HOME=<sdk> ./gradlew :app:assembleDebug`.
- **Unit tests:** `./gradlew :app:testDebugUnitTest` (JUnit4; mirror the existing
  `RenderGridDecoderTest` style — JSON in / assert out, pure functions).
- Don't touch unrelated code; follow existing `ui/terminal/` patterns.

## Architecture

**Approach A — harden the existing Compose grid (chosen).** Keep the
`decode → DecodedGrid → Compose Text-per-line` pipeline and enhance each piece.
It fits the data model (we get cmux's *decoded grid*, not PTY bytes), reuses the
already-tested decoder, and keeps logic in small, independently testable pure
functions. (Rejected: a Canvas renderer — loses Compose text selection, more
code, overkill at these grid sizes; xterm.js-in-WebView — needs a PTY byte
stream we don't have.)

The view stays split so each unit has one responsibility and the heavy logic is
pure (testable without Compose instrumentation):

| Unit | File | Responsibility | Depends on |
|------|------|----------------|------------|
| Terminal colors | `ui/terminal/TerminalColors.kt` (new) | Canvas bg, default fg, cursor, selection, faint-alpha | — |
| Nerd font | `app/src/main/res/font/` (new) + family ref | Monospace glyph coverage | — |
| Geometry & zoom | `ui/terminal/TerminalGeometry.kt` (new, pure) | cols/rows from size+cell; clamp pinch scale → font size | — |
| Style resolver | `ui/terminal/TerminalStyle.kt` (new, pure) | `Style` (+inverse/faint/decorations) → `ResolvedSpan` | `TerminalColors` |
| Grid view | `ui/terminal/RenderGridView.kt` (modify) | Draw buffer lines + cursor + selection + follow | resolver, font, colors |
| Screen | `ui/terminal/TerminalScreen.kt` (modify) | Measure cell, pinch state, resize cmux, key bar, paste, reconnect | geometry, grid view |
| View model | `ui/terminal/TerminalViewModel.kt` (modify) | Expose cursor + scrollback; reconnect() | decoder, socket |
| Decoder | `model/RenderGrid.kt` (modify) | Decode scrollback rows alongside visible rows | — |

## Components

### 1. Terminal colors (`TerminalColors.kt`)

A plain data holder (not the Material scheme) so the canvas is consistent
regardless of app light/dark:

```kotlin
data class TerminalColors(
    val background: Color,   // solid canvas, e.g. 0xFF1E1E2E
    val foreground: Color,   // default fg when a style has none
    val cursor: Color,       // cursor block color
    val selection: Color,    // selection highlight
    val faintAlpha: Float = 0.6f,
)
val DefaultTerminalColors = TerminalColors(...)
```

### 2. Nerd Font (`res/font/`)

Bundle **JetBrainsMono Nerd Font Mono**, Regular + Bold (OFL-licensed; patched
Nerd Font glyphs are OFL-compatible). Add `res/font/terminal_mono.xml` font
family (regular + bold weights) and use it as the grid `FontFamily`. Record the
font license under `app/src/main/res/font/` (or `LICENSES/`). Expected APK
growth ~2–4 MB. (Choice can be swapped for a slimmer Nerd Font Mono if size
matters.)

### 3. Geometry & pinch-zoom (`TerminalGeometry.kt`, pure)

```kotlin
/** Visible columns/rows that fit the viewport at the measured cell size. */
fun gridDimensions(
    widthPx: Float, heightPx: Float,
    cellWidthPx: Float, cellHeightPx: Float,
    minCols: Int = 20, maxCols: Int = 400,
    minRows: Int = 5,  maxRows: Int = 200,
): Pair<Int, Int>

/** Maps an accumulated pinch scale onto a clamped font size in sp. */
fun zoomedFontSizeSp(baseSp: Float, scale: Float, min: Float = 7f, max: Float = 22f): Float
```

`TerminalScreen` measures the bundled font's actual advance with a
`TextMeasurer` at the current font size, derives `cellWidthPx/cellHeightPx`,
computes `(cols, rows)`, and calls `vm.resize(cols, rows)` — **debounced** so we
only resize when `(cols, rows)` actually change. Horizontal scroll is removed:
because we resize cmux to our column count, cmux reflows to our width. Pinch
gestures accumulate scale → `zoomedFontSizeSp` → new cell size → new cols/rows →
resize. Zooming in shows fewer, larger columns (normal terminal-resize
behavior).

### 4. Style resolver (`TerminalStyle.kt`, pure)

```kotlin
data class ResolvedSpan(
    val fg: Color, val bg: Color,
    val bold: Boolean, val italic: Boolean,
    val underline: Boolean, val strikethrough: Boolean,
)
fun resolveSpan(style: Style?, colors: TerminalColors): ResolvedSpan
```

Rules: base `fg = parseColor(style.foreground) ?: colors.foreground`,
`bg = parseColor(style.background) ?: colors.background`. **inverse** swaps fg/bg.
**faint** multiplies fg alpha by `colors.faintAlpha`. **bold/italic** → weight /
`FontStyle`. **underline/strikethrough** → combined `TextDecoration`.
`RenderGridView.buildLine` consumes `ResolvedSpan` to build each `SpanStyle` run.

### 5. Cursor

Rendered by **inverting the cell** at `(cursor.row, cursor.column)` when building
that row: the cursor cell's background becomes `colors.cursor` and its glyph
becomes `colors.background` (block cursor), so it survives scroll and selection
without absolute positioning. Hidden when `!cursor.visible`. Bar /
underline cursor styles downgrade to block in v1; blink is omitted. The cursor
row index is offset by the scrollback line count (see §6).

### 6. Follow output + scrollback (`RenderGridView`, best-effort)

Decoder change: `DecodedGrid` gains `scrollbackLines: List<DecodedLine>`, decoded
from `scrollback_spans` over `scrollback_rows` rows (same span shape as
`row_spans`; **confirm against a busy session during implementation**). The
rendered buffer is `scrollbackLines + lines`.

Scroll/follow uses a `ScrollState` (Column) with an `atBottom` derivation:
- On a new frame, if the user was at the bottom → snap to bottom (follow live).
- Otherwise keep position and show a **jump-to-bottom** FAB; tapping it snaps
  down and re-enables follow.

Each `full` frame replaces buffer state (authoritative). Scrollback lines were
captured at cmux's then-current width and may differ from the live column count;
they render at their own width and may clip — acceptable for v1.

### 7. Input (`TerminalScreen`)

- **Key bar** (grouped, horizontally scrollable): Esc, Tab, Ctrl-C, Ctrl-D,
  Ctrl-Z, ↑ ↓ ← →, PageUp (`ESC[5~`), PageDown (`ESC[6~`), Home (`ESC[H`), End
  (`ESC[F`). Sequences live in a pure `KEYS` map (tested).
- **Copy:** wrap the grid in a `SelectionContainer` → native long-press select +
  Copy (works once horizontal scroll is removed).
- **Paste:** a Paste button reads the clipboard and sends it as `TerminalUp`
  input; the line input field keeps native paste.
- Keep the existing line input + Send (`text + "\r"`).

### 8. Reconnect / error handling (`TerminalViewModel` + `TerminalScreen`)

`TerminalViewModel` exposes `reconnect()` that re-subscribes to
`TerminalSocket.connect()` (cancel + relaunch the collect, reset state to
loading). The error state in `TerminalScreen` shows a clearer message plus a
**Reconnect** button. This covers transient drops and the instantly-closing
secondary surfaces.

## Data flow

```
WS TerminalDown(text) ──▶ BridgeJson ──▶ RenderGridDecoder.decode
        │                                   │ (visible + scrollback + cursor)
        ▼                                   ▼
 TerminalViewModel.state = TerminalUiState(grid, styles, cursor, error)
        │
        ▼
 TerminalScreen: measure cell (TextMeasurer @ fontSize) ─▶ gridDimensions ─▶ resize(cols,rows) [debounced]
        │                                   ▲ pinch ─▶ zoomedFontSizeSp ──────┘
        ▼
 RenderGridView (SelectionContainer + ScrollState + follow):
   buffer = scrollbackLines + lines ─▶ buildLine(resolveSpan, cursor) ─▶ Text per line (Nerd font, dark canvas)
        ▲
 input: key bar / line input / paste ─▶ vm.sendText ─▶ TerminalUp{input} ─▶ WS
```

## Error handling

- Parse failures: keep `TerminalSocket`'s tolerant decode but **log dropped
  frames** (the silent `runCatching{}.getOrNull()` previously hid the `modes`
  bug). Minimal log, not a user-facing change. *(Related cleanup, in scope as a
  one-liner.)*
- Stream close / failure → error state with Reconnect (§8).
- Empty/oversized grids: `gridDimensions` clamps to min/max; decoder already
  clamps overflowing spans.

## Testing strategy (TDD, JUnit4)

Pure functions carry the tests; Compose UI is kept thin over them.

- `TerminalGeometryTest`: `gridDimensions` (fit, clamps), `zoomedFontSizeSp`
  (clamp min/max, identity at scale 1).
- `TerminalStyleTest`: `resolveSpan` — inverse swaps fg/bg, faint applies alpha,
  underline+strikethrough combine, defaults fall back to theme.
- `RenderGridDecoderTest` (extend): scrollback lines decoded and ordered before
  visible lines; cursor row offset; existing cases stay green.
- Key sequences: assert a few `KEYS` entries (`PageUp == ESC[5~`, etc.).
- Follow predicate: `shouldStickToBottom(wasAtBottom)` trivial unit test.

## Assumptions, risks, open questions

- **Scrollback shape unconfirmed** (idle sessions showed none). Assumption:
  `scrollback_spans` share `row_spans`' shape over `scrollback_rows` rows. First
  implementation task captures a busy session to confirm; if it differs, the
  decoder change is localized.
- **Incremental frames unobserved.** Assumption: cmux sends `full` snapshots per
  update. If `full:false` deltas appear, follow/scrollback may misbehave; handled
  in a later iteration.
- **Font size** vs APK growth — JetBrainsMono Nerd Font Mono adds ~2–4 MB;
  swappable.
- **Cell-advance measurement** must use the bundled font (not the 7.2dp guess),
  or cmux columns and on-screen columns drift.

## Related issues (not in this work)

- Duplicate `GET /sessions` entries and instantly-closing secondary surfaces —
  root cause in the sessions list / bridge, tracked separately.
- `TerminalSocket` silently swallowing parse errors — mitigated here by logging;
  a broader "surface decode errors" change is separate.

## Decisions log

- Enhancement scope: all four buckets (readability, glyphs/theming, input, follow).
- Input model: **line-based + keys + copy/paste** (no raw per-keystroke mode).
- Scrollback: **best-effort in v1** (confirm shape during implementation).
- Font sizing: **pinch-to-zoom**.
- Reconnect affordance: **included**.
