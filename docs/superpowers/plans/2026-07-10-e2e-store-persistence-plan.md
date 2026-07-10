# E2E/YOLO agent-local store persistence: SQLite migration implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `internal/e2e/store.go`'s and `internal/yolo/store.go`'s
whole-file-JSON-per-call persistence with SQLite-backed stores (same public
method signatures, `auth.Store`'s patterns reused), fixing the per-frame
full-file-rewrite cost and the cross-process `pair-device`/`agent` clobber
race, and making a corrupt store file loud instead of silently disabling
e2e/push. See the paired design doc,
`docs/superpowers/specs/2026-07-10-e2e-store-persistence-design.md`, for the
full problem analysis, the evaluation of the three directions, and why this
is sized **M**, not L.

**Architecture:** `internal/e2e.Store` and `internal/yolo.Store` each move
from a `path string; mu sync.Mutex` over a hand-marshaled JSON map to a
`mu sync.Mutex; db *sql.DB` over a small SQLite schema (one table each),
mirroring `internal/auth/store.go`'s `Open`/pragma/schema shape exactly.
Every existing public method keeps its exact signature — `frame.go`,
`envelope.go`, and all of `internal/server/*.go` need zero changes. Only
`OpenStore(path) *Store` becomes `Open(path) (*Store, error)`, which ripples
into the two call sites that construct these stores
(`cmd/cmux-bridge/agent.go`, `cmd/cmux-bridge/pair.go`) and nowhere else. A
one-time, self-terminating import from the legacy `.json` file runs inside
`Open` the first time it sees no existing database, so upgrading operators
keep their paired devices and yolo settings with no manual step.

**Tech stack:** Go 1.26, `modernc.org/sqlite v1.53.0` (already a direct
dependency via `internal/auth`, pure-Go/no cgo — no `go.mod`/`go.sum`
change needed), stdlib `database/sql`, `testing`.

## Global constraints

- Every existing test in `internal/e2e/store_test.go` and
  `internal/yolo/store_test.go` must keep passing, rewritten only where the
  underlying mechanism (`load`/`save`) it directly references no longer
  exists (e.g. `setLastActive`'s helper, store_test.go:88-103, pokes
  `s.load()`/`s.save()` directly and needs a SQL-based replacement that
  achieves the same "backdate this device's last-active time" effect).
  `TestCrossProcessVisibilityNoInMemoryCache` (store_test.go:335-348) must
  keep passing **unmodified in spirit** — two independent `*Store` handles
  on the same file must still see each other's committed writes with no
  explicit reload call; SQLite gives this for free.
- No changes to `internal/server/*.go`, `internal/relay/*.go`, or the
  Android app. This is entirely an `internal/e2e`, `internal/yolo`, and
  `cmd/cmux-bridge` change.
- No new dependencies. `modernc.org/sqlite` is already vendored.
- Every task ends with `cd bridge && go build ./... && go vet ./... && go test ./...`
  and `golangci-lint run` (the repo's `.golangci.yml`: `errcheck` +
  `staticcheck` on top of standard defaults) passing clean.
- Commits authored solely by `sodre90 <erdos.peter.bme@gmail.com>`. **Never**
  add a `Co-Authored-By` or any AI-attribution trailer to any commit message.
- Follow this repo's existing comment discipline: wire/behavior "why" notes
  are expected (see `ValidateAndCommitRecvCounter`'s existing TOCTOU
  comment, which must be preserved/ported, not dropped, since the same
  atomicity property still needs the same explanation under the new
  implementation); no narrating comments restating what a line obviously
  does.

## Size estimate: M

Justification (full detail in the design doc's "Why 'L' overstates it"
section): the crypto/framing layer (`frame.go`, `envelope.go`) and every
caller in `internal/server` are untouched; the rewrite is bounded to two
small files plus their tests plus two call sites; `auth.Store`'s
`Open`/pragma/schema pattern and the historical
`docs/superpowers/plans/2026-07-01-multi-tenant-relay-transport.md` Task 1
(the same JSON→SQLite move, already executed once in this repo for
`auth.Store`) are direct templates to mirror, without that precedent's
additional multi-tenant-schema complexity. Estimated at 6 tasks, each a
single sitting.

## Migration/rollout considerations

- **Existing installs have `~/.config/cmux-bridge/sessions.json` and
  `~/.config/cmux-bridge/yolo.json` on disk today** (`internal/config/agent.go`
  defaults, `agentDefaults()`). The new default paths become
  `~/.config/cmux-bridge/sessions.db` / `~/.config/cmux-bridge/yolo.db`
  (Task 4). `Open(dbPath)` derives the legacy path by swapping the
  extension: `legacyPath := strings.TrimSuffix(dbPath, filepath.Ext(dbPath)) + ".json"`
  — this works for both the new default and any operator-customized
  `session_store`/`yolo_store` TOML value, as long as that custom path also
  ends in a recognizable extension. An operator with a custom path lacking
  a `.json`/`.db`-shaped extension is a genuinely rare edge case; document
  it in the migration's log output / a CHANGELOG note rather than
  over-engineering a second detection strategy for it.
- **Migration trigger condition:** not "the `.db` file doesn't exist yet"
  (fragile against a crash mid-migration leaving a half-populated `.db`
  behind) but "`SELECT COUNT(*) FROM devices` (or `yolo_modes`) is `0`
  **and** a legacy `.json` file exists at the derived path" — idempotent
  and safe to re-run on every `Open` until the first device/mode is
  genuinely imported or added.
- **Self-terminating, mirroring this repo's existing migration idiom**
  (Android's `Settings.migrateLegacyIfNeeded`/the `KEY_P12` wipe, and this
  same plan's own historical precedent): once imported, rename the legacy
  `.json` to `<legacyPath>.migrated` (keep it, don't delete — a forensic/
  rollback copy costs nothing and this is security-sensitive state) so the
  condition above naturally never fires again (no device rows will ever be
  `0` again once at least one real device exists, short of a fresh
  from-scratch pairing).
- **A legacy file that fails to parse** (already-corrupt `sessions.json`,
  pre-dating this work) must not block `Open`/agent startup — log loudly
  and proceed with an empty (but valid) new database, same operational
  posture as the corruption-recovery path in Task 1/2 below.
- **Rollback:** if an operator downgrades the binary after this ships,
  the old JSON-only binary won't read the new `.db` file and won't find
  `sessions.json` either (renamed to `.migrated`) — document in the
  CHANGELOG (not part of this plan's tasks; call out for the human to
  decide whether it's worth a note) that downgrading after this ships loses
  pairings, matching how `auth.Store`'s original SQLite migration was
  presumably also a one-way door.

---

### Task 1: Rewrite `internal/e2e/store.go` as SQLite-backed, with legacy-JSON import and loud corruption recovery

**Files:**
- Modify: `bridge/internal/e2e/store.go` (full rewrite of the persistence
  layer; `deviceSession`/`fileFormat` types are kept but demoted to
  migration-only helpers used solely by the legacy-JSON importer)
- Modify: `bridge/internal/e2e/store_test.go` (adjust `setLastActive` and
  `OpenStore(...)` call sites to the new `Open(...) (*Store, error)`
  signature; add the new tests from Task 5)
- Modify: `bridge/internal/server/push_test.go` (2 call sites, lines 159 and
  374), `bridge/internal/server/encryption_test.go` (line 39),
  `bridge/internal/server/direct_test.go` (3 call sites, lines 63, 91, 123)
  — each is a direct `e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))`
  that becomes `e2e.Open(...)` with an added `if err != nil { t.Fatal(err) }`
  (or equivalent `t.Helper()`-wrapped fatal, matching each file's existing
  test-setup style). One-line-each mechanical updates confirmed via
  `grep -rn "OpenStore" bridge/internal/server/` — no behavior or assertion
  in any of these tests changes, only how the `*e2e.Store` fixture is
  constructed.

**Interfaces:**
- Produces: `func Open(path string) (*Store, error)` (replaces
  `func OpenStore(path string) *Store`). Every other public method
  (`AddDevice`, `DeviceIDs`, `ActiveDeviceIDs`, `SharedSecret`,
  `NextSendCounter`, `ValidateAndCommitRecvCounter`) keeps its exact
  existing signature — no change visible to `frame.go`, `envelope.go`, or
  `internal/server`.
- Consumes: `modernc.org/sqlite` (already imported by `internal/auth`), the
  package's own existing `crypto/ecdh` usage (unchanged).

**Schema:**

```go
const schema = `
CREATE TABLE IF NOT EXISTS devices (
	device_id         TEXT PRIMARY KEY,
	device_pubkey     TEXT NOT NULL,
	shared_secret     TEXT NOT NULL,
	send_counter      INTEGER NOT NULL DEFAULT 0,
	recv_highest      INTEGER NOT NULL DEFAULT 0,
	recv_highest_set  INTEGER NOT NULL DEFAULT 0,
	recv_window_bits  INTEGER NOT NULL DEFAULT 0,
	last_active_unix  INTEGER NOT NULL
);
`
```

Note on `recv_window_bits`: this is a `uint64` bitmask in Go
(`deviceSession.RecvWindowBits`) where `commitRecvCounter`
(store.go:209-225) can legitimately set bit 63 (`replayWindowSize = 64`
means shifts up to 63 occur). SQLite's native `INTEGER` is a signed 64-bit
value. Storing/reading via `int64(windowBits)` /
`uint64(retrievedInt64)` is a lossless bit-reinterpretation (Go's
int64↔uint64 conversions never truncate or sign-extend incorrectly for
this purpose) — call this out explicitly in the implementation's doc
comment on the read/write helpers, since getting it wrong would silently
corrupt the replay window (a §1.4 invariant violation, not just a bug).

**`Open`, mirroring `auth.Store.Open` (auth/store.go:132-153):**

```go
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create dir for session store: %w", err)
	}
	dsn := path + "?_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open session store %s: %w", path, err)
	}
	if _, err := db.Exec(schema); err != nil {
		_ = db.Close()
		if recovered, rerr := recoverIfCorrupt(path, err); rerr != nil {
			return nil, fmt.Errorf("session store %s: %w", path, rerr)
		} else if recovered {
			return Open(path) // bad file moved aside; retry fresh
		}
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	s := &Store{db: db}
	if err := s.importLegacyJSON(path); err != nil {
		// A broken legacy import must not block startup -- log loudly (see
		// Task 3) and proceed with whatever was imported before the error,
		// same posture as a corrupt file: the operator needs a signal, not
		// a crashed agent.
		log.Printf("e2e: legacy session import from %s incomplete: %v", legacyPath(path), err)
	}
	return s, nil
}
```

`recoverIfCorrupt` uses the empirically-verified, typed check (see design
doc's "Loud corruption handling" section):

```go
func recoverIfCorrupt(path string, openErr error) (recovered bool, err error) {
	var sqliteErr *sqlite.Error
	if !errors.As(openErr, &sqliteErr) {
		return false, nil
	}
	if sqliteErr.Code() != sqlite3.SQLITE_NOTADB && sqliteErr.Code() != sqlite3.SQLITE_CORRUPT {
		return false, nil
	}
	corrupt := fmt.Sprintf("%s.corrupt.%d", path, time.Now().Unix())
	if err := os.Rename(path, corrupt); err != nil {
		return false, fmt.Errorf("rename corrupt store aside: %w", err)
	}
	log.Printf("e2e: session store %s was corrupt (%v); moved to %s -- every paired device must re-pair", path, openErr, corrupt)
	return true, nil
}
```

(Requires `sqlite "modernc.org/sqlite"` and `sqlite3 "modernc.org/sqlite/lib"`
imports — the same two packages `internal/auth` already depends on
transitively; `sqlite3.SQLITE_NOTADB`/`SQLITE_CORRUPT` are the same
constants verified empirically during design: opening a garbage file with
this exact vendored version returns `*sqlite.Error` with
`.Error() == "file is not a database (26)"` and `.Code() == 26 ==
sqlite3.SQLITE_NOTADB`.)

**Legacy import**, self-terminating per the Migration section above:

```go
func legacyPath(dbPath string) string {
	return strings.TrimSuffix(dbPath, filepath.Ext(dbPath)) + ".json"
}

func (s *Store) importLegacyJSON(dbPath string) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil // already has real data; never re-import
	}
	raw, err := os.ReadFile(legacyPath(dbPath))
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var f fileFormat // pre-migration JSON shape, kept only for this import
	if err := json.Unmarshal(raw, &f); err != nil {
		log.Printf("e2e: legacy session store %s is unreadable (%v); starting empty instead of blocking startup", legacyPath(dbPath), err)
		return nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback() //nolint:errcheck // no-op after a successful Commit
	for id, d := range f.Devices {
		secret, err := base64.StdEncoding.DecodeString(d.SharedSecret)
		if err != nil {
			continue // skip an unparseable row rather than aborting the whole import
		}
		if _, err := tx.Exec(`INSERT INTO devices (device_id, device_pubkey, shared_secret, send_counter, recv_highest, recv_highest_set, recv_window_bits, last_active_unix) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			id, d.DevicePubKey, base64.StdEncoding.EncodeToString(secret), d.SendCounter, d.RecvHighest, boolToInt(d.RecvHighestSet), int64(d.RecvWindowBits), d.LastActiveUnix); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	migratedTo := legacyPath(dbPath) + ".migrated"
	if err := os.Rename(legacyPath(dbPath), migratedTo); err != nil {
		log.Printf("e2e: imported %d device(s) from %s but could not rename it aside (%v) -- remove it manually", len(f.Devices), legacyPath(dbPath), err)
		return nil
	}
	log.Printf("e2e: migrated %d paired device(s) from %s to %s", len(f.Devices), legacyPath(dbPath), dbPath)
	return nil
}
```

**Method bodies** — each keeps its exact existing signature; internals move
from `load()`/`save()` to targeted SQL:

- `AddDevice`: single `INSERT ... ON CONFLICT(device_id) DO UPDATE SET ...`
  (re-pairing an existing device ID overwrites its row — matches today's
  map-assignment semantics in `AddDevice`, store.go:88-92, which
  unconditionally overwrites).
- `DeviceIDs`/`ActiveDeviceIDs`: `SELECT device_id[, last_active_unix]
  FROM devices[ WHERE last_active_unix >= ?]` — `staleDeviceAge` constant
  and its doc comment (store.go:115-123) are unchanged, just the query
  changes shape. **Do not silently swallow the query error** — log it (see
  Task 3) before returning `nil`, unlike today's store.go:105-107/135-137.
- `SharedSecret`: `SELECT shared_secret FROM devices WHERE device_id = ?`;
  same base64-decode step as today; same logging change as above.
- `NextSendCounter`: inside `s.mu.Lock()` (kept, matching `auth.Store`'s
  layering of an in-process mutex on top of SQLite's own safety — see
  design doc's atomicity discussion), `UPDATE devices SET send_counter =
  send_counter + 1 WHERE device_id = ?` then `SELECT send_counter FROM
  devices WHERE device_id = ?` to return the *pre-increment* value (today's
  contract: `n := d.SendCounter; d.SendCounter++; return n`, store.go:177-183
  — read the value back and subtract 1, or read-then-update in that order
  inside one transaction, whichever is cleaner to get exactly right; add a
  unit test asserting the returned sequence starts at `0` for a freshly
  paired device, matching `TestNextSendCounterIncrementsAndPersistsAcrossInstances`,
  store_test.go:147-172).
- `ValidateAndCommitRecvCounter`: kept as one `s.mu.Lock()`-guarded method
  (same TOCTOU-closing property, same doc comment ported over,
  store.go:227-237); internally, `SELECT recv_highest, recv_highest_set,
  recv_window_bits FROM devices WHERE device_id = ?`, run
  `canAcceptRecvCounter`/`decrypt()`/`commitRecvCounter` exactly as today
  (these three pure functions, store.go:196-225, need **zero changes** —
  they operate on plain `uint64`/`bool` values regardless of storage
  backend), then `UPDATE devices SET recv_highest = ?, recv_window_bits =
  ?, recv_highest_set = 1, last_active_unix = ? WHERE device_id = ?`.

- [ ] **Step 1: Write the new store_test.go alongside the rewrite** — port
  every existing test (`TestAddDeviceAndSharedSecret` through
  `TestCrossProcessVisibilityNoInMemoryCache`, store_test.go:23-348) to call
  `Open` instead of `OpenStore` and handle the returned error; replace
  `setLastActive`'s direct `s.load()`/`s.save()` calls
  (store_test.go:88-103) with a small SQL helper (`UPDATE devices SET
  last_active_unix = ? WHERE device_id = ?`) that achieves the same
  backdating effect for the test.

- [ ] **Step 2: Run tests to verify they fail** — `Open`/schema/SQL don't
  exist yet.

Run: `cd bridge && go test ./internal/e2e/... -run . -v`
Expected: FAIL — compile errors, `Open` undefined.

- [ ] **Step 3: Write the implementation** — `store.go` as sketched above.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/e2e/... -v`
Expected: PASS, all existing + ported tests green (Task 5 adds the three
new acceptance tests as a separate task/commit, not required here).

- [ ] **Step 5: `go vet` and `golangci-lint`**

Run: `cd bridge && go vet ./... && golangci-lint run ./internal/e2e/...`
Expected: clean. Pay particular attention to `errcheck` on the new
`tx.Rollback()` deferred calls (mirror `auth.Store`'s existing
`//nolint:errcheck` comment style, auth/store.go:469) and any `sql.Rows`
`Close()` calls (mirror `auth.Store.List`, auth/store.go:323).

- [ ] **Step 6: Commit**

```bash
git add bridge/internal/e2e/store.go bridge/internal/e2e/store_test.go
git commit -m "bridge: migrate e2e.Store from JSON to SQLite"
```

---

### Task 2: Rewrite `internal/yolo/store.go` as SQLite-backed, with legacy-JSON import and loud corruption recovery

**Files:**
- Modify: `bridge/internal/yolo/store.go`
- Modify: `bridge/internal/yolo/store_test.go`
- Modify: `bridge/internal/server/sessions_test.go` (line 49:
  `s.SetYoloStore(yolo.OpenStore(t.TempDir() + "/yolo.json"))` → `yolo.Open(...)`
  with an added error check, same one-line mechanical shape as Task 1's
  `e2e.OpenStore` call-site updates)

**Interfaces:**
- Produces: `func Open(path string) (*Store, error)` (replaces
  `func OpenStore(path string) *Store`). `Mode(workspaceID string) string`
  and `SetMode(workspaceID, mode string) error` keep their exact existing
  signatures.

Much smaller than Task 1 — one table, no counters, no replay window:

```go
const schema = `
CREATE TABLE IF NOT EXISTS yolo_modes (
	workspace_id TEXT PRIMARY KEY,
	mode         TEXT NOT NULL
);
`
```

- `Mode`: `SELECT mode FROM yolo_modes WHERE workspace_id = ?`; no row →
  `""` (matches today's "off" default, store.go:90); a genuine query error
  must be logged, not swallowed (same fix as Task 1's read methods).
- `SetMode`: empty `mode` → `DELETE FROM yolo_modes WHERE workspace_id = ?`
  (matches today's store.go:102-103 "empty mode removes the entry"
  semantics exactly); non-empty → `INSERT ... ON CONFLICT(workspace_id) DO
  UPDATE SET mode = excluded.mode`.

`Open`, `recoverIfCorrupt`, and the legacy-JSON importer are structurally
identical to Task 1's (import `fileFormat{Modes map[string]string}` instead
of the devices shape) — genuinely small enough to either share a tiny
internal helper package or duplicate the ~15-line corruption-recovery
function; given `internal/e2e` and `internal/yolo` are independent packages
with no existing shared-internals convention between them, duplicating the
small `recoverIfCorrupt` shape (not worth a new shared package for ~15
lines used twice) is the lower-risk choice — note this as a explicit,
deliberate decision in the PR description rather than silently picking one
way, per this repo's "propose your choice with a sentence of rationale"
convention (`docs/improvement-guide.md` §2).

- [ ] **Step 1: Write the new store_test.go** — port
  `yolo/store_test.go`'s existing (currently 54-line) suite to `Open`'s
  fallible signature.
- [ ] **Step 2: Run tests, verify failure** (same shape as Task 1 Step 2).
- [ ] **Step 3: Write the implementation.**
- [ ] **Step 4: Run tests, verify pass.**
- [ ] **Step 5: `go vet` + `golangci-lint`.**
- [ ] **Step 6: Commit**

```bash
git add bridge/internal/yolo/store.go bridge/internal/yolo/store_test.go
git commit -m "bridge: migrate yolo.Store from JSON to SQLite"
```

---

### Task 3: Update `cmd/cmux-bridge/agent.go` and `pair.go` for the now-fallible `Open`

**Files:**
- Modify: `bridge/cmd/cmux-bridge/agent.go` (around line 284-285)
- Modify: `bridge/cmd/cmux-bridge/pair.go` (around line 236)

**Interfaces:**
- Consumes: `e2e.Open`, `yolo.Open` (Tasks 1-2).

`agent.go:284-285` currently:

```go
srv.SetSessions(e2e.OpenStore(cfg.SessionStore))
srv.SetYoloStore(yolo.OpenStore(cfg.YoloStore))
```

becomes (matching this file's existing early-return-on-error style, e.g.
the `directStore, err := auth.Open(cfg.DirectAuthStore)` block a few lines
above it):

```go
sessions, err := e2e.Open(cfg.SessionStore)
if err != nil {
	log.Printf("agent: open session store: %v", err)
	return 1
}
srv.SetSessions(sessions)

yoloStore, err := yolo.Open(cfg.YoloStore)
if err != nil {
	log.Printf("agent: open yolo store: %v", err)
	return 1
}
srv.SetYoloStore(yoloStore)
```

`pair.go:236` currently:

```go
sessions := e2e.OpenStore(cfg.SessionStore)
```

becomes (matching this file's existing `log.Printf("pair-device: %v", err); return 1`
style used a few lines above for `e2e.LoadOrCreateIdentity`):

```go
sessions, err := e2e.Open(cfg.SessionStore)
if err != nil {
	log.Printf("pair-device: open session store: %v", err)
	return 1
}
```

No other change to either file — the rest of `pairDevice`/`runPairDevice`
and `runAgent` is untouched.

- [ ] **Step 1: Make the two edits above.**
- [ ] **Step 2: Build**

Run: `cd bridge && go build ./...`
Expected: clean.

- [ ] **Step 3: Commit**

```bash
git add bridge/cmd/cmux-bridge/agent.go bridge/cmd/cmux-bridge/pair.go
git commit -m "bridge: handle e2e/yolo store open errors in agent and pair-device"
```

---

### Task 4: `internal/config/agent.go` — new default paths and doc comments

**Files:**
- Modify: `bridge/internal/config/agent.go`

**Interfaces:** no signature changes — `AgentConfig.SessionStore`/
`YoloStore` stay `string` TOML fields; only their default values and doc
comments change.

- [ ] **Step 1:** update `agentDefaults()` (agent.go:56-64):
  `SessionStore: "~/.config/cmux-bridge/sessions.db"`,
  `YoloStore: "~/.config/cmux-bridge/yolo.db"`.
- [ ] **Step 2:** update the two fields' doc comments (agent.go:30-35) from
  "the JSON file holding ..." to "the SQLite database holding ..." —
  mention the one-time automatic import from a same-named `.json` sibling
  for upgrading installs, so an operator reading the config reference
  understands why a `.json` file might still be sitting next to the `.db`
  one after upgrading (renamed to `.migrated`, per the design's rollout
  section).
- [ ] **Step 3: Build + test**

Run: `cd bridge && go build ./... && go test ./...`
Expected: clean — `LoadAgent`'s own tests (if any reference the literal
default path string) should be checked and updated if they assert on the
old `.json` default.

- [ ] **Step 4: Commit**

```bash
git add bridge/internal/config/agent.go
git commit -m "bridge: default session/yolo stores to SQLite paths"
```

---

### Task 5: Acceptance tests

**Files:**
- Modify: `bridge/internal/e2e/store_test.go` (add
  `TestConcurrentPairAndCounterCommitLosesNeither`,
  `TestNextSendCounterCostDoesNotScaleWithDeviceCount`,
  `TestOpenRecoversFromCorruptFileLoudly`)
- Modify: `bridge/internal/yolo/store_test.go` (add
  `TestOpenRecoversFromCorruptFileLoudly`, the `yolo`-package twin)

Write these exactly as specified in the design doc's "Acceptance test
definition" section — that section is written to be directly
implementable; this task is the checkbox for doing so, not a re-derivation.
Key shapes to restate here for the implementer's convenience:

- **Concurrency test:** two `*Store` handles on one file, race
  `AddDevice` (new device) against a tight loop of `NextSendCounter`
  (existing device); assert the new device's secret survived and the
  counter sequence has no gaps/duplicates. Doc-comment it as the direct
  regression test for the pre-migration clobber bug.
- **Scaling test:** time `NextSendCounter` on one device at 10 vs. 500
  total paired devices; assert the ratio stays bounded (e.g. `<= 3x`), not
  that either absolute number is small — robust to test-machine variance.
- **Corruption test:** write garbage bytes to the store path, call `Open`,
  assert a non-nil `*Store`/nil error, a `.corrupt.*` sibling file
  containing the original bytes, non-empty captured log output naming the
  path, and that the returned store is immediately usable.

- [ ] **Step 1: Write the three `e2e` tests + one `yolo` test.**
- [ ] **Step 2: Run and confirm they pass against the Task 1/2 implementation.**

Run: `cd bridge && go test ./internal/e2e/... ./internal/yolo/... -v`
Expected: PASS.

- [ ] **Step 3 (verification-only, not required to remain in the final
  diff): confirm the concurrency test actually catches the old bug.**
  Temporarily check out the pre-Task-1 version of `internal/e2e/store.go`
  (`git show <commit-before-task-1>:bridge/internal/e2e/store.go`) into a
  scratch location, adapt the new concurrency test's `Open` calls back to
  `OpenStore` for that one manual run, and confirm it fails or flakes.
  Discard the scratch adaptation afterward — this step is a one-time sanity
  check on the test's own validity, not a permanent dual-implementation
  test harness. Report the result (pass/fail/flaky, and how many of N runs)
  in this task's summary.

- [ ] **Step 4: Commit**

```bash
git add bridge/internal/e2e/store_test.go bridge/internal/yolo/store_test.go
git commit -m "bridge: add e2e/yolo store concurrency, scaling, and corruption tests"
```

---

### Task 6: Full-suite verification and docs touch-up

**Files:** none modified beyond a final check; this task is verification +
an optional doc note, not new production code.

- [ ] **Step 1: Full build/vet/test/lint**

Run:
```bash
cd bridge && go build ./... && go vet ./... && go test ./... && golangci-lint run
```
Expected: clean, including `internal/relay` and every other package
untouched by this plan — confirms the "zero changes outside e2e/yolo/
cmd/cmux-bridge" claim from the design doc empirically, not just by
inspection.

- [ ] **Step 2: Confirm `internal/relay/multitenant_test.go` still passes
  unmodified** (repo invariant #2) — it should not have been touched by any
  task above; run it explicitly:

Run: `cd bridge && go test ./internal/relay/... -run TestMultitenant -v`
(or whatever the actual test names are — `grep -n '^func Test' internal/relay/multitenant_test.go`
first if unsure) to get an explicit pass, not just an incidental pass
inside the full-suite run above.

- [ ] **Step 3 (optional, human's call, not required to land in this PR):**
  add a one-line `CHANGELOG.md`/README note about the one-time `sessions.json`/
  `yolo.json` → `.db` migration and the one-way-downgrade caveat from the
  design doc's rollout section — flag this to the human rather than
  deciding unilaterally, since `CHANGELOG.md` doesn't exist yet in this repo
  (it's a separate, unstarted guide item, §8) and adding the first entry to
  a not-yet-created file is a scope call worth a sentence of confirmation.

- [ ] **Step 4: Final report** — summarize: task-by-task commit hashes, the
  manual old-vs-new concurrency-test comparison result from Task 5 Step 3,
  and confirmation that `internal/relay`, the Android app, and the wire
  protocol were genuinely untouched, and that `internal/server`'s only
  changes are the seven mechanical test-fixture call-site updates from
  Tasks 1-2 (no production `.go` file in `internal/server` changed). A
  `git diff --stat main..HEAD` should show changes confined to
  `internal/e2e/`, `internal/yolo/`, `cmd/cmux-bridge/`,
  `internal/config/agent.go`, and the seven `internal/server/*_test.go`
  call sites named above — nothing else — as the concrete evidence for
  this claim.

---

## Tests summary (all tasks)

- `internal/e2e/store_test.go`: every existing test ported to `Open`'s
  fallible signature (Task 1) + 3 new acceptance tests (Task 5).
- `internal/yolo/store_test.go`: every existing test ported (Task 2) + 1
  new acceptance test (Task 5).
- No *new* tests needed in `internal/server`/`cmd/cmux-bridge` — only the
  seven existing-test call-site updates named in Tasks 1-2
  (`push_test.go` x2, `encryption_test.go` x1, `direct_test.go` x3,
  `sessions_test.go` x1), confirmed via
  `grep -rn "OpenStore" bridge/internal/server/`. All existing assertions
  in those files are otherwise unchanged.
