# Pairing key-exchange authentication: SAS/fingerprint confirmation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the Critical pairing-MITM finding by requiring a human to
confirm a short authentication string (SAS/fingerprint), derived
identically and locally on both the Go CLI and the Android app, before
either side commits trust (Android: POST `/devices/pair` + persist;
CLI: `sessions.AddDevice`). Applies uniformly to QR-scan and manual-entry
pairing — no path-based special-casing. See the paired design doc,
`docs/superpowers/specs/2026-07-10-pairing-mitm-fingerprint-design.md`, for
the full protocol audit (including why the CLI's leg of the handshake is
*unconditionally* exposed, not just the manual-entry path the originating
review named), the four evaluated directions, and the fingerprint
bit-length justification.

**Architecture:** One new pure function per side
(`e2e.PairingFingerprint` / `pairingFingerprint`), no wire-format changes.
Android's `PairingClient.pairInternal` splits into `prepare` (local-only,
computes the fingerprint) and `commit` (today's POST+derive+persist,
unchanged), gated by a new `PairingUiState.AwaitingConfirmation` screen
state. The CLI's `pairDevice` gains one interactive confirm step between
deriving the shared secret and calling `sessions.AddDevice`, via a new
`in io.Reader` parameter (mirrors the existing `out io.Writer`
testability pattern already used in that function).

**Tech stack:** Go 1.26 stdlib (`crypto/sha256`, `bufio`), Kotlin
`java.security.MessageDigest`, existing test frameworks
(`go test`, JUnit/`PairingClientTest`-style Kotlin unit tests — no new
dependencies either side).

## Global constraints

- No wire-format changes — every value the fingerprint needs is already
  present locally on both sides before this design's confirmation step
  runs (design doc's "Wire format" section). Do not add new JSON fields
  to `wire.PairingCodeInfoResp`/`wire.PairingCodeStatusResp`/QR payload.
- No `--yes`/non-interactive bypass flag on the CLI (design doc's
  "no bypass flag" note — a repo-wide grep found zero scripted
  `pair-device` usage; don't reintroduce a way to disable the one check
  that defeats the MITM).
- Existing `pair_test.go` tests (`TestPairDeviceStopsOnRedemption`,
  `TestPairDeviceRetriesOnTransientError`, `TestPairDeviceTimesOut`) keep
  passing with an added `in io.Reader` argument — feed them
  `strings.NewReader("y\n")` so their existing positive-path assertions
  hold unchanged.
- `PairingClientTest`'s existing coverage of `pairInternal`'s POST+derive
  +persist behavior must keep passing under its new name (`commit`) with
  the same assertions — this is a rename/reshape, not a behavior change.
- Every Go task ends with
  `cd bridge && go build ./... && go vet ./... && go test ./...` and
  `golangci-lint run` passing clean. Every Android task ends with
  `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
  passing clean (fresh, not cached — `--rerun` if in doubt).
- Commits authored solely by `sodre90 <erdos.peter.bme@gmail.com>`.
  **Never** add a `Co-Authored-By` or AI-attribution trailer.
- No narrating comments; "why" comments only for genuinely non-obvious
  constraints (matches this repo's existing style, e.g. `DeliveryTracker`'s
  main-thread-only doc comment, `ValidateAndCommitRecvCounter`'s TOCTOU
  note).

## Size estimate: S/M

Six small, independent tasks across two languages; no schema, no new
dependencies, no wire-format coordination. Each task is a single sitting.

## Rollout considerations

- This changes user-facing pairing UX on both platforms simultaneously —
  land the Go and Android changes together (or Go first, since an old
  Android build talking to a new CLI just sees one extra `Fprintf` line it
  already ignores; a new Android build against an old CLI works fine too,
  since the CLI-side prompt is independent of what the phone shows). No
  server-side/relay change at all — this fix lives entirely in the two
  endpoints, matching the design's "blind relay stays blind" property.
- No migration concerns: no persisted state changes shape, no new config.

## Tasks

### Task 1: `PairingFingerprint` (Go) + `pairingFingerprint` (Kotlin)

**Files:**
- Modify: `bridge/internal/e2e/cipher.go` (add `PairingFingerprint`, next
  to `DeriveSharedSecret`)
- Modify: `bridge/internal/e2e/cipher_test.go` (new tests: order-independence,
  distinct-keys-distinct-output, one fixed-input/fixed-output golden case)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt`
  (add `pairingFingerprint` + a small unsigned lexicographic-compare helper,
  next to `deriveSharedSecret`)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/CipherTest.kt`
  (or create if no test file exists yet for this file — check first)
  (mirror the same three test cases; assert the **same golden fixed-input
  output** as the Go test, to hand-verify both implementations produce
  byte-identical output for the same key pair — see design doc's
  acceptance test 1)

**Interfaces:**
- Go: `func PairingFingerprint(pubkeyA, pubkeyB []byte) string`, format
  `"XXXX-XXXX-XXXX"` (uppercase hex, 3 groups of 2 bytes).
- Kotlin: `fun pairingFingerprint(pubkeyA: ByteArray, pubkeyB: ByteArray): String`,
  identical format.
- [ ] `- [ ]` Write `PairingFingerprint`/`pairingFingerprint` exactly per
      the design doc's "Fingerprint function" section (order-independent
      via a lexicographic min/max, `SHA-256` over the concatenation, first
      6 digest bytes formatted as 3 hex groups).
- [ ] `- [ ]` Write the three test cases on both sides; pick one concrete
      key pair, compute the expected fingerprint by hand once (e.g. via a
      throwaway script), and assert that literal string on both sides so
      a future refactor of either implementation can't silently drift
      from the other.
- [ ] `- [ ]` `go build ./... && go vet ./... && go test ./...` and
      `./gradlew :app:assembleDebug :app:testDebugUnitTest` both pass.

### Task 2: CLI confirm gate (`pair.go`)

**Files:**
- Modify: `bridge/cmd/cmux-bridge/pair.go` (`pairDevice` signature: add
  `in io.Reader`; insert the fingerprint-print + `confirmFingerprint` call
  between deriving `secret` (`pair.go:162-165`) and `sessions.AddDevice`
  (`pair.go:166`); add `confirmFingerprint` as a new unexported function;
  update `runPairDevice`'s call site to pass `os.Stdin`)
- Modify: `bridge/cmd/cmux-bridge/pair_test.go` (thread
  `strings.NewReader("y\n")` through the three existing call sites; add
  `TestPairDeviceAbortsOnRejectedFingerprint` and
  `TestPairDeviceConfirmFingerprintFailsClosed` per the design doc's
  acceptance tests 5-6)

**Interfaces:**
- `func pairDevice(client *http.Client, agentBase, devicePairURL string, identity *e2e.Identity, sessions *e2e.Store, out io.Writer, in io.Reader, pollPeriod time.Duration, deadline time.Time) error`
  — `in` inserted after `out` to match the design doc's stated signature;
  confirm exact parameter ordering doesn't collide with any other
  in-flight change to this function before landing.
- `func confirmFingerprint(in io.Reader, out io.Writer) (bool, error)` —
  prints `"Confirm? [y/N]: "` to `out`, reads one line from `in` via
  `bufio.NewReader(in).ReadString('\n')`, returns `true` only for a
  trimmed/lowercased `"y"` or `"yes"`; EOF or any read error returns
  `(false, nil)` (fail closed, not a propagated error — an aborted
  pairing is expected, ordinary control flow, not exceptional).
- [ ] `- [ ]` Add `confirmFingerprint` and wire it into `pairDevice` exactly
      per the design doc's "CLI: confirm before AddDevice" section.
- [ ] `- [ ]` Update `runPairDevice`'s call site (`pair.go:242`) to pass
      `os.Stdin`.
- [ ] `- [ ]` Update the three existing tests' call sites with
      `strings.NewReader("y\n")`.
- [ ] `- [ ]` Add `TestPairDeviceAbortsOnRejectedFingerprint`
      (`strings.NewReader("n\n")`; assert non-nil error and
      `sessions.SharedSecret(tokenHash)` not-found afterward — mirrors
      `TestPairDeviceStopsOnRedemption`'s positive assertion, inverted).
- [ ] `- [ ]` Add `TestPairDeviceConfirmFingerprintFailsClosed`
      (`strings.NewReader("")`; assert abort, not a panic).
- [ ] `- [ ]` `go build ./... && go vet ./... && go test ./...` and
      `golangci-lint run` pass.

### Task 3: Android — split `PairingClient.pairInternal` into `prepare`/`commit`

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingClient.kt`
  (split `pairInternal` per the design doc's "Android: split resolve from
  commit" section: new `prepare(qr): String` — validates
  `qr.hasSafePairUrl()`, computes and returns the fingerprint, no network
  call; `commit(qr)` — today's `pairInternal` body, unchanged, renamed)
- Modify: `android/app/src/test/java/.../PairingClientTest.kt` (existing
  `pairInternal` tests keep their assertions, retargeted at `commit`; add
  a new test asserting `prepare` makes zero network calls and returns the
  expected fingerprint for a known key pair)

**Interfaces:**
- `internal suspend fun prepareInternal(qr: PairingQr, phonePublicKey: ByteArray): String`
  (free function alongside `pairInternal`→`commitInternal`, same
  free-function-for-testability pattern the file already uses and
  documents at `PairingClient.kt:105-108`)
- `internal suspend fun commitInternal(...)` — `pairInternal` renamed,
  body unchanged.
- `PairingClient.prepare(qr: PairingQr): String` /
  `PairingClient.commit(qr: PairingQr)` — thin wrappers, mirroring how
  `pair(qr)` currently wraps `pairInternal`.
- [ ] `- [ ]` Implement `prepareInternal`/`commitInternal` split; keep
      `hasSafePairUrl()` validation in `prepare` (fails fast before
      showing a confirmation screen for a malformed URL, rather than only
      catching it later at commit time).
- [ ] `- [ ]` Update `PairingClientTest.kt`: rename existing
      `pairInternal` test call sites to `commitInternal`; add the new
      `prepareInternal` test (zero network calls — assert against a
      failing/unreachable `OkHttpClient` to prove it's never touched).
- [ ] `- [ ]` `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes.

### Task 4: Android — `PairingViewModel` + `PairingScreen` confirmation UI

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingViewModel.kt`
  (`PairingUiState` gains `AwaitingConfirmation(val fingerprint: String, val qr: PairingQr)`;
  `onQrScanned`/`onManualEntrySubmitted` call `pairing.pairingClient(slot).prepare(qr)`
  and transition to `AwaitingConfirmation` instead of calling
  `pairAndUpdateState` directly; new `onConfirmed()` calls `commit` via
  the existing `pairAndUpdateState`-shaped try/catch, `onRejected()`
  returns to `Scanning` with no network call)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingScreen.kt`
  (new `is PairingUiState.AwaitingConfirmation` branch: show the
  fingerprint prominently, "Confirm"/"Cancel" buttons wired to
  `vm::onConfirmed`/`vm::onRejected`)
- Modify: `android/app/src/main/res/values/strings.xml` (new strings:
  fingerprint-confirmation prompt text, "Confirm", "Cancel" — reuse
  `action_pair`-style existing resources where one already fits; repo
  convention per the Phase 5 strings-to-resources cleanup is dedup over
  new near-duplicates)
- Modify: `android/app/src/test/java/.../PairingViewModelTest.kt` (if it
  exists — check first; add coverage for the new state transitions:
  scan/manual-entry → `AwaitingConfirmation` → confirm → `Success`, and
  → reject → `Scanning`)

**Interfaces:**
- `PairingUiState.AwaitingConfirmation(fingerprint: String, qr: PairingQr)`
- `PairingViewModel.onConfirmed()`, `PairingViewModel.onRejected()`
- [ ] `- [ ]` Implement the `PairingUiState`/`PairingViewModel` changes per
      the design doc.
- [ ] `- [ ]` Implement the `PairingScreen` UI branch.
- [ ] `- [ ]` Add/extend `PairingViewModelTest` coverage for both the
      confirm and reject paths (design doc acceptance tests 3-4).
- [ ] `- [ ]` Live-check on an emulator or device: pair once via QR, once
      via manual entry, confirm the fingerprint shown matches what the
      paired CLI printed in both cases, and confirm "Cancel" returns to
      the scanner with no partial/stuck state.
- [ ] `- [ ]` `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes.

### Task 5: `bridge/README.md` correction

**Files:**
- Modify: `bridge/README.md:200-230` — replace "Same single-use code, same
  handshake, same e2e result — just without a scan" with an accurate
  description: manual entry now requires confirming the printed
  fingerprint against the phone's confirmation screen before pairing
  completes, matching the new CLI/app behavior.

- [ ] `- [ ]` Update the README section; re-read the surrounding
      paragraph to make sure the correction reads naturally in context,
      not just a bolted-on caveat.

### Task 6: Full-suite verification

- [ ] `- [ ]` `cd bridge && go build ./... && go vet ./... && go test ./... && golangci-lint run`
- [ ] `- [ ]` `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun`
- [ ] `- [ ]` Manual end-to-end pairing test on real hardware (or emulator
      + real Mac agent) covering: QR-scan pair with matching fingerprint
      (succeeds), manual-entry pair with matching fingerprint (succeeds),
      and — if feasible to simulate — a deliberately mismatched
      fingerprint on one side (both sides refuse to complete pairing,
      no credential/session persisted on either side).
- [ ] `- [ ]` Update memory: document this fix (mirroring how
      `cmux-app-android-e2e-durability-fix.md` documents the earlier
      Critical finding) once merged, and update the multi-agent review's
      finding tracker to mark this one closed before starting the
      remaining 7.

## Tests summary (all tasks)

- Go: `cipher_test.go` (fingerprint properties + golden value),
  `pair_test.go` (existing 3 tests updated + 2 new: reject, fail-closed).
- Kotlin: `CipherTest.kt` (fingerprint properties + same golden value),
  `PairingClientTest.kt` (`commitInternal` = today's coverage,
  `prepareInternal` new), `PairingViewModelTest.kt` (new state-machine
  coverage for confirm/reject).
- No integration/instrumented tests added (matches this repo's existing
  posture — no Robolectric/`androidTest` source set exists, per
  `cmux-app-android-e2e-durability-fix` memory's prior finding on this
  same constraint); the manual end-to-end check in Task 6 is the
  real-device substitute.
