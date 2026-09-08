# Plan: stop rebuilding the terminal grid from scratch on every frame

Design: `docs/superpowers/specs/2026-09-08-terminal-render-cost-design.md`.
Issue: cmux-app-dfl (render half; the resize half is already closed by 6811f11).

Two commits, deliberately split, because they carry very different risk. The
first cannot change behaviour at all and can land on its own evidence. The
second introduces cross-frame state into a decoder that has been stateless
since it was written, and is the one that needs the measurement.

## Commit 1 — allocate the blank cell once

`model/RenderGrid.kt` (`RenderGridDecoder.layout`), plus tests.

Hoist the single blank `Cell` out of the prefill and fill with it, and build
each row's list once instead of `Array` → `toList()`. Removes `rowCount * cols`
allocations per decode (~42,700 for a 240x178 scrollback) and one full copy of
every row.

Purely an allocation change: `Cell` is an immutable data class, so a shared
instance is indistinguishable from a fresh one to every consumer, and the
returned `DecodedGrid` is structurally identical.

Tests:
- the existing decoder tests keep passing unchanged — that *is* the safety
  argument for this commit, so do not touch them
- blank cells in a decoded row compare equal to `Cell(' ', 0)` (guards against
  anyone later making the shared blank carry a style id)
- a span that writes a styled blank still produces its own cell and does not
  alias the shared one

Negative control: revert the hoist and confirm the new tests still pass — they
are about correctness, not allocation, and must not be mistaken for proof the
optimisation happened. The proof for that is the measurement below.

## Commit 2 — reuse unchanged rows between frames

`model/RenderGrid.kt`, `ui/terminal/TerminalViewModel.kt`, plus tests.

`layout` gains the previous frame's rows and the previous frame's spans-by-row.
Per row: compare this frame's span list for that row against the previous
frame's; on a match, reuse the previous `DecodedLine` instance and skip
expansion entirely; otherwise expand as today.

`decode` therefore has to become stateful across calls, and where that state
lives is the one real design decision left in this commit. Put it in the caller,
not in the `object`: `RenderGridDecoder` is a singleton, one shared mutable
previous-frame would be wrong the moment two panes are open. `TerminalViewModel`
already holds the per-pane frame loop (`RenderGridDecoder.decode(rg)` at the
socket callback) and is the natural owner — so `decode` takes the previous
`DecodedGrid` and the previous `RenderGrid` as parameters and stays a pure
function of its inputs.

Rows must be compared by spans, not by cells: comparing cells is the cost this
commit exists to remove, and doing it to decide whether to skip work would be a
wash.

Tests (all in the existing decoder test file):
- a second decode with identical spans returns rows that are the **same
  instances** — assert reference identity (`assertSame`), because structural
  equality already held before this change and would pass against the old code
- a row whose spans changed is a fresh instance, and its neighbours are still
  reused
- a frame with a different column count or row count reuses nothing (the
  geometry changed, so cached rows are the wrong width)
- the scrollback scrolling by one row does not silently reuse a shifted row:
  spans carry their own `row`, so this must fall out of the comparison rather
  than be special-cased — assert it explicitly
- an empty frame after a non-empty one returns empty, not the previous rows
- decoding two different panes' frames through the same object does not leak
  rows between them (the state is the caller's; this test pins that)

Negative control for the whole commit: force the span comparison to always
report "changed" and confirm the identity tests fail while every structural
test still passes. That is what separates "the rows are correct" from "the rows
were reused", and only the second is what this commit claims.

## Measurement, and what would make this not worth keeping

Per the design, on a **release** build, with the taps confirmed to have landed:

1. `gfxinfo reset` immediately before opening a pane, dump 10 s later.
   Baseline to beat: 51 frames, 84.3% janky, p90 200 ms, 14 frames ≥100 ms.
2. `gfxinfo reset` with a pane on a *running* agent open and untouched, dump
   10 s later. Baseline to beat: 42 frames, 100% janky, p50 32 ms, all
   UI-thread bound. This is the number the design is aimed at.

Take both baselines on release first — the numbers above are debug-emulator and
cannot be compared against a release run.

If (2) does not move materially, commit 2 should be reverted rather than kept:
it buys its win by putting cross-frame state into a decoder whose statelessness
is worth something, and that trade is only justified by the measurement. Commit
1 stands either way — it has no such cost.

Do not claim any improvement from reasoning about allocation counts. The exact
counts are known from the code; the wall-clock split between allocation, GC,
and Compose's key comparison is not, and only gfxinfo settles it.
