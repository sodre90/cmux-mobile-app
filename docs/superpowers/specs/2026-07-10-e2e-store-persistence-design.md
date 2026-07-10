# E2E/YOLO agent-local store persistence: SQLite migration design

## Context

`docs/improvement-guide.md` §5.3 (Phase 2 — Go structural, tagged **M/L**,
"highest correctness payoff" item remaining) flags `internal/e2e/store.go`:
it reloads and rewrites the *entire* `sessions.json` inside one mutex on
every send-counter bump, every recv-counter commit, and every `AddDevice` —
once per terminal frame — and `pair.go`/the running `agent` process open the
same file from separate OS processes with no cross-process lock, so
last-writer-wins on `os.Rename` can clobber a counter commit (a
replay-window regression → nonce-reuse risk in the e2e crypto, invariant §1.4)
or drop a freshly paired device.

This is a design-only pass per repo `CLAUDE.md`'s rule for L-sized items:
"write a spec + plan pair under `docs/superpowers/` first ... get it
reviewed, then implement." No production code changes accompany this
document.

## Problem statement, precisely scoped

The guide's one-paragraph description bundles two independent problems.
Confirmed from code (see audit below), they are not the same bug and do not
have the same severity:

1. **Whole-file rewrite per call, same process.** Every `e2e.Store` method —
   read or write — reloads and re-parses the entire `sessions.json`, and
   every mutating method re-marshals and renames the entire file. This
   happens on the *hot path*: every outbound terminal frame, feed push, and
   HTTP response body calls `NextSendCounter`/`ValidateAndCommitRecvCounter`
   through `SharedSecret` first, so it's actually **two** full load
   round-trips per outbound frame and **two** loads plus **one** save per
   inbound frame (see "Per-frame call chain" below). This cost grows with
   total paired-device count and is paid by the single, always-running
   `cmux-bridge agent` process.
2. **Cross-process clobber, whole-file granularity.** `cmux-bridge
   pair-device` (a separate, short-lived process) and the running
   `cmux-bridge agent` each open independent `*e2e.Store` handles on the same
   path, each with its own **in-process-only** `sync.Mutex`. Because the
   persistence unit is "the entire devices map," *any* two concurrent writes
   to *any* two unrelated devices (not just the same device) can clobber
   each other: whichever process's `save()` renames its temp file over the
   target last wins, silently discarding whatever the other process's `load()`
   didn't see yet — even if that other write touched a completely different
   `deviceID`.

Problem 2 is the rarer event (pairing a device happens occasionally);
problem 1 is the everyday cost (every keystroke, every render-grid push).
Both must be fixed; the guide's accept criteria ("no full-file rewrite per
frame" and "concurrent pair + counter-commit loses neither") name each one
explicitly.

## Access-pattern audit (confirmed from code)

### `internal/e2e/store.go`

- `type Store struct { path string; mu sync.Mutex }` (store.go:36) — no
  in-memory cache; every call re-touches disk. `type fileFormat struct {
  Devices map[string]deviceSession }` (store.go:32) is the entire on-disk
  shape — one JSON document, one map.
- `load()` (store.go:45) — `os.ReadFile` + `json.Unmarshal` of the whole
  file. Called by every method below.
- `save(f fileFormat)` (store.go:66) — `json.MarshalIndent` of the whole map,
  write to `<path>.tmp`, `os.Rename` over `path`. Called by every mutating
  method below. This rename is the clobber point: two processes racing this
  function each start from their own `load()`'s snapshot, so the second
  `Rename` wins in full, discarding the first process's write in full — not
  just a merge conflict on one field, but total loss of anything the losing
  process wrote that the winning process's stale `load()` didn't already
  have.
- `AddDevice` (store.go:81) — `load()` + `save()`. **The only production
  call site is `cmd/cmux-bridge/pair.go:166`** (confirmed via
  `grep -rn '\.AddDevice(' --include=*.go .` across `bridge/`, excluding
  tests) — i.e., `AddDevice` only ever runs in the short-lived `pair-device`
  process, never in the long-running `agent` process.
- `DeviceIDs` (store.go:101), `ActiveDeviceIDs` (store.go:131),
  `SharedSecret` (store.go:148) — each calls `load()` and **silently
  swallows a load error**, returning `nil`/`false` as if the store were
  simply empty (store.go:105-107, 135-137, 152-154). A corrupt file makes
  every device look unpaired.
- `NextSendCounter` (store.go:166) — `load()`, increment one field in the
  in-memory copy, `save()` the whole map back.
- `ValidateAndCommitRecvCounter` (store.go:238) — `load()`, replay-window
  check, run `decrypt()`, `save()` the whole map back, all under one held
  `s.mu.Lock()`. This method's own doc comment explains *why* the whole
  operation is one locked critical section: it closes a TOCTOU race between
  two concurrent decrypts of the same captured counter (regression test:
  `TestConcurrentDecryptOfSameCounterAcceptsExactlyOnce`,
  store_test.go:300-333). That correctness property is **same-process
  only** — `ValidateAndCommitRecvCounter` is called exclusively from the
  running `agent` process's request/frame handlers (`pair.go` never calls
  it), so an in-process mutex is sufficient for it; it does not by itself
  need cross-process locking. This matters for scoping the fix below.

### Per-frame call chain (why "per frame" means *two* store round-trips each way)

- `frame.go:32 EncryptFrame` → `SharedSecret` (one `load()`) → `NextSendCounter`
  (one more `load()` + one `save()`) — every outbound terminal frame, feed
  push, or `/events` frame.
- `frame.go:44 DecryptFrame` → `SharedSecret` (one `load()`) →
  `ValidateAndCommitRecvCounter` (one more `load()` + one `save()`) — every
  inbound terminal frame.
- `envelope.go:15 EncryptBody` / `envelope.go:31 DecryptBody` — identical
  shape, for HTTP request/response bodies under `encryptionMiddleware`
  (`internal/server/encryption.go`).

So "once per terminal frame" undersells it slightly: it's the whole-file
`load()`+`json.Unmarshal` **twice**, plus one `save()`+`json.MarshalIndent`+
rename, for every single frame in either direction.

### Cross-process access points (confirmed from code)

- `cmd/cmux-bridge/agent.go:284` — `srv.SetSessions(e2e.OpenStore(cfg.SessionStore))`,
  in the long-running `cmux-bridge agent` process.
- `cmd/cmux-bridge/pair.go:236` — `sessions := e2e.OpenStore(cfg.SessionStore)`,
  in the short-lived `cmux-bridge pair-device` process, invoked by the
  operator while `agent` is (normally) already running.
- `cmd/cmux-bridge/pair.go:166` — `sessions.AddDevice(tokenHash, devicePub, secret)`,
  the sole production writer from the second process.

**Refinement of the guide's framing:** `internal/yolo/store.go`'s
`OpenStore` is called from exactly one place — `cmd/cmux-bridge/agent.go:285`
(confirmed via the same grep). No second process ever opens `yolo.json`.
Yolo has the same whole-file-per-call cost (`internal/yolo/store.go:46-108`,
identical `load`/`save` shape) and the same silent-swallowed-load-error bug
(`Mode`, store.go:83-91, returns `""` on load failure), but **it does not
have the cross-process clobber bug** — there is no second writer today.
Migrating it alongside `e2e.Store` is justified by architectural consistency
and the loud-corruption fix, not by an observed concurrency bug. `yolo.Mode`
is also called far less often than the e2e counters: once per workspace per
`/sessions` poll (`internal/server/sessions.go:47`) and once per inbound
cmux event frame in `autoResolveYolo` (`internal/server/yolo.go:74`) — not
once per terminal keystroke.

### `bridge/internal/auth/store.go` (the pattern the guide says to reuse)

- `Open(path string) (*Store, error)` (auth/store.go:132) — `os.MkdirAll`,
  then `sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)")`
  (auth/store.go:139), then apply `schema` (a `CREATE TABLE IF NOT EXISTS`
  block) and `migrations` (a slice of idempotent `ALTER TABLE` statements,
  `applyMigrations` at auth/store.go:81-91, tolerating SQLite's "duplicate
  column name" error as a no-op re-run).
- The type's own doc comment (auth/store.go:129-131) states the property we
  need for `e2e`/`yolo` too: "Safe to call from multiple short-lived
  processes ... against the same file — SQLite handles the locking, so there
  is no in-memory cache to fall out of sync."
- Every method still takes `s.mu.Lock()` (e.g. `Issue`, auth/store.go:272;
  `RedeemPairingCode`'s transaction, auth/store.go:462) even though SQLite
  already serializes file access — the in-process mutex is kept for
  Go-level call ordering/atomicity within one process (e.g.
  `RedeemPairingCode`'s check-then-insert), layered *on top of*, not instead
  of, SQLite's own cross-process safety.
- `modernc.org/sqlite v1.53.0` (bridge/go.mod) is **already a direct
  dependency** — pulled in for `auth.Store`. Reusing it for `e2e`/`yolo`
  adds zero new external dependencies.

## Directions evaluated

The guide names three, in preference order. Each is evaluated here against
the three acceptance-test legs named in the guide: (i) concurrent pair +
counter-commit loses neither, (ii) no full-file rewrite per frame, (iii)
corruption is loud.

### (a) Migrate `e2e` (and `yolo`) state to SQLite, reusing `auth.Store`'s patterns

**Mechanism:** replace the JSON map with a `devices` table
(`internal/e2e`) / `yolo_modes` table (`internal/yolo`), one row per device
or workspace. `NextSendCounter` becomes `UPDATE devices SET send_counter =
send_counter + 1 WHERE device_id = ? RETURNING send_counter` (or a
`SELECT`-then-`UPDATE` inside a transaction if the driver's `RETURNING`
support is a concern — verify during implementation);
`ValidateAndCommitRecvCounter` becomes a transaction that reads the one
row, runs `decrypt()`, and updates that one row; `AddDevice` becomes an
`INSERT`. `Open(path) (*Store, error)` mirrors `auth.Store.Open` exactly:
`busy_timeout` pragma, schema, and (if needed later) an `applyMigrations`-style
slice.

**Why this satisfies all three legs:**

- **(i) No clobber, structurally, not just serialized.** The JSON design's
  bug isn't merely "two writers raced" — flock-style serialization alone
  doesn't fix it, because the *unit of persistence* is the whole file:
  even with perfect mutual exclusion around each `save()`, whichever writer
  goes second must have re-`load()`ed *immediately before* writing to avoid
  clobbering the other's unrelated row, which is exactly the load-every-call
  pattern already in the code today (see (b) below for why this matters).
  SQLite's row-level `UPDATE ... WHERE device_id = ?` / `INSERT` statements
  are independent per row: `pair.go`'s `INSERT` for a new device and
  `agent`'s `UPDATE` for an unrelated device's counter cannot clobber each
  other at all, with no cooperative locking protocol required beyond what
  `sql.Open`'s `busy_timeout` pragma already provides (a writer that finds
  the DB momentarily locked retries instead of erroring, exactly as
  `auth.Store` already relies on).
- **(ii) No full-file rewrite per frame.** A single-row `UPDATE`/transaction
  touches one B-tree page, not a `json.MarshalIndent` of every paired
  device. Cost no longer scales with total device count.
- **(iii) Corruption is loud, and detection moves from "per call, silently"
  to "once, at `Open`, loudly."** Verified empirically against the exact
  vendored version (`modernc.org/sqlite v1.53.0`): opening a garbage file
  and running the schema `CREATE TABLE` against it returns
  `*sqlite.Error` with `.Error() == "file is not a database (26)"` and
  `.Code() == 26`, which is the exported constant
  `modernc.org/sqlite/lib.SQLITE_NOTADB` (`lib/sqlite.go:4040`). `Open` can
  `errors.As` for `*sqlite.Error`, check `Code() == sqlite3.SQLITE_NOTADB`
  (and, for completeness, `sqlite3.SQLITE_CORRUPT`, SQLite's "database disk
  image is malformed" code, for a file that opens but fails mid-read),
  rename the bad file aside, log loudly, and retry fresh — a precise,
  typed check, not a fragile substring match. (`auth.Store`'s own
  `applyMigrations` resorts to a substring match only because "duplicate
  column name" has no typed sentinel in this driver; `SQLITE_NOTADB` does.)

**Why "L" overstates it:** the guide tags 5.3 **M/L** and explicitly asks
"is it really L, or does reusing `auth.Store`'s schema/migration patterns
make it M?" Concretely:

- `frame.go`/`envelope.go` need **zero changes** — `EncryptFrame`,
  `DecryptFrame`, `EncryptBody`, `DecryptBody` only call `Store` methods by
  their existing signatures; the crypto/framing logic is untouched.
- `internal/server/*.go`'s **production** code needs **zero changes** —
  `encryption.go`, `events.go`, `push.go`, `terminal.go` all call
  `s.sessions.SharedSecret`/`EncryptFrame`/`DecryptFrame`/`EncryptBody`/
  `DecryptBody`/`ActiveDeviceIDs`, all of which keep identical signatures.
  `internal/relay/*` is untouched entirely — this is agent-local state the
  relay never sees. Its **tests** are a different story: `grep -rn
  "OpenStore" internal/server/` finds seven direct
  `e2e.OpenStore`/`yolo.OpenStore` call sites across `push_test.go`,
  `encryption_test.go`, `sessions_test.go`, and `direct_test.go` — these are
  one-line-each mechanical updates to the new fallible `Open(...)
  (*Store, error)` signature, not a design change, but they are real,
  in-scope edits the plan must account for (see the plan's Task 1).
- The rewrite is bounded to two small files (`internal/e2e/store.go`, 267
  lines; `internal/yolo/store.go`, 109 lines) plus their two test files,
  plus two call sites (`cmd/cmux-bridge/agent.go:284-285`,
  `cmd/cmux-bridge/pair.go:236`) that only need an added error check because
  `Open` becomes fallible where `OpenStore` wasn't.
- **This exact migration already happened once in this repo**, for
  `auth.Store`: `docs/superpowers/plans/2026-07-01-multi-tenant-relay-transport.md`
  Task 1 ("Rewrite `auth.Store` as a multi-tenant SQLite-backed store",
  lines 22-647) is a complete, already-executed template for "flat JSON
  file → SQLite, same package, same call sites" in this codebase, including
  its own test rewrite. Copying that shape mechanically, without the
  additional multi-tenant schema complexity (`e2e`/`yolo` don't need
  tenant scoping — they're already single-tenant-per-agent), is
  meaningfully *less* work than the precedent, not more.

Given the above, this is **M**, not L: a mechanical, low-blast-radius
storage-substrate swap behind an unchanged interface, using a dependency and
a schema/migration idiom already live in the same binary, with a concrete
precedent to mirror. See the paired plan doc for the task breakdown and size
justification in full.

**One overstatement in the guide worth flagging:** it says "the multi-tenant
design doc already recommends this." Checked directly
(`docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md`, the only
"SQLite" hit in that file is line 122): the design doc recommends moving
`auth/store.go` off JSON to SQLite — it does **not** mention `internal/e2e`
or a session/device-secret store at all in that context. Migrating `e2e`
(and `yolo`) to SQLite is a reasonable, consistent *extension* of that
doc's direction (same repo, same already-adopted storage substrate,
inherits the same migration story), but it is this guide's own
recommendation, not something the multi-tenant doc already asked for
verbatim. Doesn't change the recommendation below, just the citation.

### (b) Keep JSON; load once into memory, persist debounced, add `flock`

**Why this fails leg (i) unless it stops being "load once":** the existing
test `TestCrossProcessVisibilityNoInMemoryCache` (store_test.go:335-348)
encodes a real, currently-true invariant: two independent `*Store` handles
on the same file see each other's writes immediately, because *every* call
reloads from disk. "Load once into memory" breaks this outright unless a
process also re-validates against disk before trusting its cache — which,
worked through fully, means either (a) building a real cross-process
cache-invalidation protocol (mtime-watch, a generation counter file, or
similar) — itself an L-sized correctness problem with its own race
conditions, not a smaller one than SQLite — or (b) reloading under the
`flock` immediately before every write anyway, which is functionally the
existing load-mutate-save pattern, just now serialized with an OS
advisory lock instead of an in-process mutex. Read (b) narrowly (flock
added, but the existing always-reload-every-call pattern otherwise kept,
and the "debounce" idea dropped) is a real, smaller fix — but it still:

- **Fails leg (ii) outright.** It's explicitly still a full-file
  `load()`+`save()` on every call; `flock` only adds cross-process mutual
  exclusion around the existing per-frame rewrite, it does nothing to
  reduce its cost. The guide's own accept criterion ("no full-file rewrite
  per frame") is not met by this narrower reading either.
- **Turns the debounced-write variant's crash safety worse, not better,**
  for the exact property this whole item exists to protect: a
  debounce window between "counter bumped in memory" and "flushed to disk"
  means a `SIGKILL`/crash inside that window silently reverts a committed
  send/recv counter, re-opening precisely the nonce-reuse risk (§1.4) this
  work is meant to close — today's code is actually crash-safe *per call*,
  since every single successful frame is durably persisted (via `rename`,
  which is atomic on the platforms this runs on) before the method returns.
- **Needs a new dependency anyway.** Go's stdlib has no cross-platform
  `flock`; this repo would need `golang.org/x/sys/unix` (Unix-only, fine
  for macOS/Linux, but still a new import) or a third-party wrapper like
  `github.com/gofrs/flock` — whereas (a) needs zero new dependencies.

Net: (b), read as the guide states it (cache + debounce + flock), cannot
satisfy leg (ii) and actively regresses the crash-safety property behind
leg (i)'s spirit. A narrower "just flock the existing per-call
load-mutate-save, no cache, no debounce" reading is internally consistent
and would close the cross-process clobber (partially satisfying leg (i) for
the *rare* pairing race) but still fails leg (ii) by design, since it keeps
the whole-file rewrite. Not recommended, in either reading.

### (c) Have `pair-device` write through the running agent (RPC) instead of the shared file

**Mechanism:** add a small authenticated local IPC surface (unix socket or
loopback-only endpoint) that `pair-device` calls instead of instantiating
its own `e2e.Store`; the running `agent` process becomes the sole writer.

**What it fixes:** genuinely eliminates the *specific* cross-process
interleaving between `pair.go`'s `AddDevice` and `agent`'s
`NextSendCounter`/`ValidateAndCommitRecvCounter`, since only one process
ever opens the file for writing again. Leg (i) becomes satisfiable this way
(there's no longer a second writer to race).

**Why it doesn't clear the bar alone:**

- **Fails leg (ii) completely and by design.** This direction is
  orthogonal to the per-frame full-file-rewrite cost: it only changes *who*
  calls `AddDevice`, not what `NextSendCounter`/`ValidateAndCommitRecvCounter`
  do internally. The agent's own hot path — every terminal keystroke, every
  push frame — is completely untouched and keeps doing the exact same
  `load()`+`save()` dance described above. Since that happens on every
  frame (high frequency) versus pairing happening once per device (low
  frequency), (c) fixes the rarer, lower-impact half of the problem and
  leaves the more frequent, higher-impact half exactly as-is.
- **New trust-boundary surface for a partial fix.** `pair-device` would need
  to discover and authenticate to a live agent process (a new local IPC
  channel, however small) purely to relocate one already-rare write path —
  meaningful new surface area for a direction that, even if implemented
  perfectly, would still need (a) or (b) layered on top to satisfy leg (ii).
  This matches why the guide lists it third: it is at best a partial
  mitigation to combine with one of the other two, not a standalone
  alternative to them.

## Recommendation: (a), SQLite migration

(a) is the only direction that satisfies all three acceptance-test legs on
its own, with a bounded, already-instantiated pattern in this exact repo
(`auth.Store`, plus a literal historical precedent plan for the same JSON→
SQLite move), zero new dependencies (`modernc.org/sqlite` is already
vendored), and zero changes required outside `internal/e2e`,
`internal/yolo`, and two call sites in `cmd/cmux-bridge`. Size: **M**, per
the justification above — see the paired plan for the task-by-task
breakdown that backs that estimate.

## Loud corruption handling

Applies to both `e2e.Store.Open` and `yolo.Store.Open` identically, and
**lands as part of this work**, not as a separate follow-up: under the
SQLite direction, `Open` must already be able to fail (unlike today's
`OpenStore`, which never errors), so distinguishing "genuinely corrupt
file" from "any other open failure" and handling it gracefully is inherent
to correctly implementing `Open` in the first place — it is not additional
scope layered on top.

Design:

1. `Open` attempts `sql.Open` + schema creation as normal.
2. If the error, via `errors.As` into `*sqlite.Error`
   (`modernc.org/sqlite`), has `Code()` equal to
   `sqlite3.SQLITE_NOTADB` (26, confirmed empirically above) or
   `sqlite3.SQLITE_CORRUPT`, treat the file as corrupt:
   - Close the failed `*sql.DB` handle.
   - Rename the bad file to `<path>.corrupt.<unix-timestamp>` (timestamped,
     not a single fixed `.corrupt` suffix, so a second corruption event
     doesn't silently clobber the first forensic copy).
   - Log at the current codebase's plain `log` level (this repo has no
     `slog` yet — that's guide item 6.1, separately scoped and still open —
     so match existing style, e.g. `log.Printf`) a message that names the
     original path, the corrupt-copy path, the underlying SQLite error, and
     the operational consequence: **all previously paired devices must
     re-pair** (this is the same end-user impact as today's silent failure —
     the fix is entirely about the operator *knowing*, not about avoiding
     re-pairing, which isn't recoverable from a genuinely corrupt file
     regardless).
   - Recurse once into a fresh `Open` at the same path, which now creates a
     brand-new, empty, valid database.
3. Any other `Open`-time error (permissions, disk full, directory
   uncreatable) is *not* corruption-handled — it propagates as a real
   error and fails agent/pair-device startup, exactly matching how
   `auth.Store.Open` already behaves for its own non-corruption failures.
4. Read methods (`SharedSecret`, `DeviceIDs`, `ActiveDeviceIDs`, `Mode`)
   that hit a genuine post-`Open` query error (e.g. disk failure mid-run,
   not corruption-at-rest, since `Open` already validated the file format
   once at startup) must log the error before returning their zero value,
   rather than swallowing it silently as today's `store.go:104-107` /
   `store.go:134-137` / `store.go:151-154` / `yolo/store.go:86-90` do. This
   is a materially smaller residual concern under SQLite than under JSON:
   the primary corruption-detection point moves from "every single call,
   by chance, if it happens to hit the bad bytes" to "once, at startup,
   guaranteed" — a post-`Open` query error signals an active runtime
   malfunction (worth logging), not silent-at-rest corruption (already
   handled at `Open`).

## Acceptance test definition

Three tests, one per accept-criteria leg named in the guide
("a test proving concurrent pair + counter-commit loses neither; no full-file
rewrite per frame; corruption is loud"). Precise enough to write directly
against the new SQLite-backed `internal/e2e/store.go`.

### 1. Concurrent pair + counter-commit loses neither

`TestConcurrentPairAndCounterCommitLosesNeither` (new test in
`internal/e2e`, alongside `store_test.go`):

- `t.TempDir()`-backed path; `s1, _ := Open(path)` and `s2, _ := Open(path)` —
  two independent `*Store` handles on the same file, standing in for the
  `agent` process and the `pair-device` process respectively (SQLite
  supports multiple connections to one file from within a single test
  binary, so this genuinely exercises the same code path two OS processes
  would hit, without needing `exec.Command` subprocess forking).
- `s1.AddDevice("dev1", pub1, secret1)` synchronously first, so there's a
  counter to bump.
- Launch two goroutines concurrently:
  - Goroutine A calls `s2.AddDevice("dev2", pub2, secret2)` once (the
    `pair-device` process pairing a brand-new second device).
  - Goroutine B calls `s1.NextSendCounter("dev1")` in a tight loop, `M`
    times (e.g. `M = 200`; the running `agent` pushing frames to the
    already-paired `dev1`), collecting every returned counter value.
- After both goroutines finish, assert:
  - `s1.SharedSecret("dev2")` (or `s2.SharedSecret("dev2")`) succeeds and
    returns exactly `secret2` — `AddDevice` from the second handle was not
    lost.
  - The `M` counter values collected from goroutine B are exactly
    `{0, 1, ..., M-1}` with no duplicates and no gaps — every increment
    landed durably, none were lost to a stale-read-then-overwrite race.
- Document in the test's doc comment (mirroring
  `TestConcurrentDecryptOfSameCounterAcceptsExactlyOnce`'s existing style)
  that this is the direct regression test for the bug this migration fixes:
  run unmodified against the *old* JSON-backed `Store`, this test is
  expected to fail or flake, since two independent JSON-backed `*Store`
  handles racing `AddDevice`/`NextSendCounter` on the same file can clobber
  each other's writes (whichever `save()`'s `os.Rename` lands last wins in
  full). Confirming that expectation against the pre-migration code once, by
  hand, before switching the implementation over, is part of implementing
  this test — not required to remain runnable against both versions
  afterward.

### 2. No full-file rewrite per frame

Framed as a scaling assertion rather than a literal byte-diff (SQLite has
no "rewrite the whole file" step to measure directly, and asserting on
engine internals would be brittle): the acceptance property is that a
single `NextSendCounter` call's cost does not grow with the total number of
paired devices, whereas the old JSON design's cost is `O(total devices)`
per call (whole-map marshal every time) regardless of which device is
touched.

`TestNextSendCounterCostDoesNotScaleWithDeviceCount` (new test in
`internal/e2e`):

- Open a fresh store; pair 10 devices (`dev0`..`dev9`).
- Call `NextSendCounter("dev0")` a handful of times to warm up (discard
  timing — first-call filesystem/page-cache effects).
- Time `N` (e.g. 2,000) consecutive `NextSendCounter("dev0")` calls; record
  average per-call duration as `avgSmall`.
- Pair 490 more devices (500 total).
- Warm up again, then time `N` more `NextSendCounter("dev0")` calls; record
  `avgLarge`.
- Assert `avgLarge` is not more than some generous, noise-tolerant multiple
  of `avgSmall` (e.g. `avgLarge <= avgSmall * 3`) — a ratio-based bound is
  robust to absolute machine speed, but would clearly fail under the old
  `O(total devices)` design at a 50x device-count increase (500 vs 10),
  while a genuine `O(1)`-per-row `UPDATE` should show no meaningful growth.
- This is a proxy for "no full-file rewrite," not a literal instruction-count
  assertion — note that explicitly in the test's doc comment so a future
  reader understands what property it's actually checking and why a ratio
  bound was chosen over an absolute one.

### 3. Corruption is loud

`TestOpenRecoversFromCorruptFileLoudly` (new test in `internal/e2e`, and
its structural twin in `internal/yolo`):

- `t.TempDir()`-backed path; write garbage bytes (not a valid SQLite file,
  e.g. `[]byte("not a database")`) directly to that path.
- Capture log output for the duration of the call — either by redirecting
  `log.SetOutput` to a `bytes.Buffer` for the test (restoring the previous
  output via `t.Cleanup`) or, if the implementation threads a logger through
  `Open` instead of using the package-level `log` default, injecting a test
  logger — the implementer should pick whichever this codebase's existing
  style favors (it currently has no injected-logger convention anywhere;
  `log.SetOutput` redirection is the lower-risk choice given that).
- Call `Open(path)`. Assert:
  - It returns a non-nil `*Store` and a nil error (the corrupt file is
    recovered from, not fatal to startup — matches "the agent should still
    come up so the operator can re-pair," not "corruption crashes the
    process").
  - The original path no longer contains the garbage bytes; a sibling file
    matching `<path>.corrupt.*` exists and contains exactly the original
    garbage bytes (forensic copy preserved).
  - The captured log output is non-empty and contains, at minimum, the
    original path and the word "corrupt" (exact wording is an
    implementation choice; the test should assert on substance — something
    was logged, it's attributable to this path — not byte-for-byte message
    text, so the message can be improved later without breaking the test).
  - The returned `*Store` is immediately usable: `store.AddDevice(...)`
    (or, for `yolo`, `store.SetMode(...)`) succeeds against it.

## Explicit non-goals (this design)

- Cross-tenant/multi-agent sharding of `e2e`/`yolo` state — out of scope;
  these remain one file (now one SQLite DB) per Mac agent, exactly as today.
- Building a general local-IPC framework for the agent — direction (c) is
  rejected above; no new IPC surface is introduced by this design.
- Adopting `slog` (guide item 6.1) — this design uses the existing plain
  `log` package to match current style; revisit logging call sites here
  once 6.1 lands, not as part of this work.
- Encrypting the SQLite file at rest beyond today's `0o600`/`0o700`
  permission scheme — unchanged from the JSON file's existing protection
  level; not a regression, not an improvement, out of scope here.
