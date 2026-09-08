# The terminal view rebuilds the whole grid from scratch on every frame — Design

`cmux-app-dfl` was filed as "opening an agent pane costs a ~200 ms frame". The
resize half of it is fixed and verified (6811f11: twelve resizes per open became
one). Re-measuring the render half turned up something the bead did not ask
about and that matters more:

> Watching a *running* agent, with no interaction at all, produces 42 frames at
> ~32 ms each — 100% over budget, every one UI-thread bound, GPU idle by
> comparison. (Emulator, debug build, so treat 32 ms as a shape and not the
> phone's number.)

That is not jank. Nothing animates, so nothing visibly drops; these frames are
produced only when content changes. What it costs is CPU and battery,
continuously, for as long as someone watches an agent work — which is the app's
single most common activity.

The open frame and the steady-state frame turn out to have the same cause, so
one design covers both.

## Where the work actually goes

Three facts, all read off the code, none of them about drawing:

**1. Every cell of every row is allocated fresh on every frame.**
`RenderGridDecoder.layout` (model/RenderGrid.kt) opens with

```kotlin
val cells = Array(rowCount) { Array(cols) { Cell(BLANK, 0) } }
```

The inner lambda runs per element, so that is `rowCount * cols` fresh `Cell`
objects before a single span is applied. `cmux` hands back exactly 240
scrollback rows on every non-empty replay (measured across all 13 surfaces of
6 workspaces), at 178 columns, so the scrollback alone is **~42,700 `Cell`
allocations per frame**, plus the visible screen, plus a second copy of every
row from the closing `cells.map { DecodedLine(it.toList()) }`. At the observed
~4 frames per second that is on the order of 200,000 short-lived objects a
second, sustained, for a grid that is almost entirely identical to the one
before it.

**2. Nothing is reused between frames, so Compose cannot skip anything.**
`RenderGridView` guards the expensive `buildLine` with

```kotlin
val annotated = remember(rendered, styleMap, colors, cur) { buildLine(...) }
```

That cache works — `DecodedLine` is a data class, so a structurally equal row
hits it (there is a test for exactly this,
`RenderGridViewTest.wrapModeProducesStructurallyEqualButFreshLines`). But
"hits it" means `equals` walked all 178 `Cell`s of that row to find out,
because the incoming instance is always a different object. Across ~280 rows
that is ~50,000 field-pair comparisons per frame spent proving nothing changed.

**3. The row count is not the problem the bead assumed.**
`MaxScrollbackLines = 2000` never binds: cmux caps the scrollback it returns at
240. An open composes ~260–320 rows for ~40 visible ones, not 2000. So the
originally suggested fix — cap the composed scrollback far below 2000 — would
do almost nothing.

## Decision: stop rebuilding rows that did not change

**Make the decoder frame-to-frame incremental, so an unchanged row keeps its
previous `DecodedLine` instance, and let identity do the rest.**

Everything downstream already keys on value equality, and Kotlin's generated
`equals` opens with `this === other`. So the moment an unchanged row arrives as
the *same object* it did last frame, `remember`'s key comparison collapses from
178 field pairs to one reference check, and Compose skips the row's
recomposition for free. No call site changes.

The comparison that decides "unchanged" must be cheaper than the one it
replaces, and it is: rows are compared **by their spans, not by their cells**.
A `RowSpan` is `(row, column, cellWidth, styleId, text)` and a terminal row is
typically 1–5 of them; comparing those is one to two orders of magnitude less
work than comparing 178 `Cell`s, and it happens *before* the row is expanded
rather than after.

So `layout` gains a previous-frame argument and becomes, per row: group this
frame's spans by row, compare that row's span list against the previous frame's,
and on a match reuse the previous `DecodedLine` and skip the expansion
entirely. Only genuinely changed rows allocate.

Two smaller changes fall out of the same pass and belong with it:

- **Share the blank cell.** `Cell(BLANK, 0)` is immutable and every instance is
  interchangeable; hoisting one and filling with it removes the `rowCount * cols`
  prefill allocation outright, and it helps even on the rows that *do* change.
- **Drop the double copy.** `cells.map { DecodedLine(it.toList()) }` builds each
  row twice. Building the row's list once is the same code, shorter.

The blank-cell hoist is worth calling out separately because it is the only part
of this that is unconditionally free — it needs no cross-frame state and cannot
regress anything, since sharing an immutable value is invisible to every
consumer.

## Why not a LazyColumn

Because it breaks a shipped feature. The rows sit inside a `SelectionContainer`
and cross-row text selection does not survive a Lazy layout — the rows outside
the composed window do not exist to be selected. Terminal text selection is
live-verified and in use (see `terminal-view-enhancements`), so windowing the
`Column` trades a rendering cost for a functional regression.

Windowing is also aimed at the wrong thing. It would stop *composing* the ~240
off-screen rows, but the decoder would still allocate and expand all of them
every frame, because it runs in the view model before the view sees the grid.
Fixing the decoder helps the visible rows too, and the off-screen ones become
nearly free as a side effect: a row that is unchanged is neither expanded nor
compared nor recomposed.

If, after this, the remaining per-frame cost is still dominated by composing
off-screen rows, windowing becomes worth revisiting — with hand-rolled selection
as its price, argued on its own evidence.

## What this does not fix

The one-off open frame is only partly addressed. The first frame of a pane has
no previous grid to diff against, so it pays full expansion by definition; what
it stops paying is the *second through nth* frames arriving while the pane is
still settling. Whether that is most of the observed burst (14 frames ≥100 ms in
the first 10 s) or a little of it is not predictable from the code and has to be
measured.

## How this gets believed

The bead already specifies the acceptance measurement and it is the right one:
`dumpsys gfxinfo com.sodre90.cmuxremote reset` immediately before the tap that
opens a pane, dumped 10 s after, before and after the change. Two constraints
carried from the last attempt, both learned the hard way:

- **Release build, not debug.** Compose debug builds are materially slower than
  release+R8, and the 32 ms baseline was measured on a debug emulator. A
  before/after comparison must hold the build type fixed, and should be quoted
  from release.
- **Confirm the taps landed.** The first run of the last measurement showed a
  clean 57 ms max and was worthless — the taps had missed and the pane never
  opened. Screenshot or dump the hierarchy before trusting a good number.

And a steady-state measurement the bead did not have: `gfxinfo reset` while a
pane with a running agent is open and untouched, dumped 10 s later. That is the
number this design is really aimed at, and it is the one that should move most.
