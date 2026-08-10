# Pairing confirmation signal — implementation plan (cmux-app-gmo)

Design: `docs/superpowers/specs/2026-08-10-pairing-confirmation-signal-design.md`
Issue: cmux-app-gmo (P2) — blocks cmux-app-af1

One logical item per commit, per CLAUDE.md. **Order is load-bearing**: 1 → 2
→ 3. Commit 3 makes the phone wait for a confirmation, so it must not land
before commit 2 teaches the agent to send one, or every pairing times out.

Commit 2 is Go-only by nature (agent↔server), so the wire-lockstep invariant
does not apply to it. Commit 1 adds a device-facing DTO whose Kotlin mirror
lands in commit 3 — see the note under commit 1 for why that is not a
lockstep violation.

## Commit 1 — server: confirmation state and the two routes

`bridge/internal/auth/store.go`, `bridge/internal/wire/pairing.go`,
`bridge/internal/pairing/pairing.go`

Schema, as additive `ALTER TABLE` statements in the existing migration list
next to `redeemed_at`:

```
ALTER TABLE pairing_codes ADD COLUMN confirmed_at TEXT
ALTER TABLE pairing_codes ADD COLUMN refused_at TEXT
```

Store API:

- `ConfirmPairing(tenantID, code string) error` — stamps `confirmed_at` in a
  transaction. `ErrNotFound` for an unknown code or a foreign tenant (same
  shape `AbortPairing` uses, so the handlers can share the 403/404 split).
  A distinct error for already-refused and for past-TTL, both surfacing as
  409.
- `AbortPairing` — replace the `DELETE FROM pairing_codes` with
  `UPDATE … SET refused_at = ?`. Device-token deletion is unchanged. Its
  existing tests assert the code is gone; they become "the code reads
  refused, and is still unredeemable".
- `PairingConfirmationState(code string) (state string, ok bool)` — not
  tenant-scoped, because the device calling it does not know its tenant, same
  reasoning as `PairingCodeInfo`. Derives the state per the design's table
  and applies the `PairingConfirmTTL` fail-closed rule on read.

`wire`: `PairingConfirmTTL = 5 * time.Minute`, and

```go
// PairStatusResp is the response to GET /devices/pair-status/{code}.
type PairStatusResp struct {
    State string `json:"state"` // pending | confirmed | refused
}
```

Routes in `Mount`, beside the existing five:

```go
mux.Handle("POST /agent/pairing-code/{code}/confirm", http.HandlerFunc(h.confirmPairing))
mux.Handle("GET /devices/pair-status/{code}", http.HandlerFunc(h.pairStatus))
```

`confirmPairing` resolves the tenant and 403s when it can't, exactly like
`abortPairing`. `pairStatus` does not resolve a tenant at all and must not
echo one back.

Wire-lockstep note: `PairStatusResp` is a new endpoint's DTO, not a change to
a struct Kotlin already mirrors. Nothing on the Kotlin side diverges from
anything by its absence, and no existing field changes meaning. Commit 3
adds the mirror together with the only code that reads it.

Tests — `internal/auth/store_test.go`:
- confirm stamps, and a second confirm is idempotent (not an error).
- confirm on another tenant's code → `ErrNotFound`, row untouched.
- confirm after `AbortPairing` → 409-class error.
- confirm with `redeemed_at` older than `PairingConfirmTTL` → 409-class error.
- a redeemed, unconfirmed row reads `pending`; past the TTL the same row
  reads `refused` with nothing written.
- after `AbortPairing`: state reads `refused`, the device token is gone
  (existing assertion), and `RedeemPairingCode` still refuses the code.

Tests — both mounts, mirroring the `AbortPairing` trio already there:
- `internal/relay/pairing_test.go`: confirm requires the agent CN; a foreign
  tenant gets the same 403/404 split as abort; `pair-status` answers with no
  client cert and returns only `state`.
- `internal/server/direct_pairing_test.go`: confirm works under
  `ConstantTenant`; unknown code is 404.
- `internal/relay/multitenant_test.go` unchanged and passing.

Gate: `cd bridge && go build ./... && go vet ./... && go test ./...`

## Commit 2 — agent: confirm, or abandon

`bridge/cmd/cmux-bridge/pair.go`

Add `confirmPairing(client, agentBase, code) error` next to `abortPairing`,
same raw-HTTP shape. In `pairDevice`, after `confirmFingerprint` returns
true:

1. `sessions.AddDevice(...)` — unchanged, still `abandonPairing` on failure.
2. `confirmPairing(...)` — on failure, `abandonPairing` with a message that
   says the pairing could not be confirmed, so the operator knows the phone
   will report a refusal.
3. Only then print `Device paired successfully.`

Tests — `cmd/cmux-bridge/pair_test.go`. `fakePairingRelay` already returns
counters for aborts; add one for confirms:
- a confirmed pairing sends exactly one confirm, after `AddDevice`, and zero
  aborts.
- a refused fingerprint sends zero confirms and one abort (existing test,
  extended).
- a failing `AddDevice` sends zero confirms and one abort.
- a failing confirm sends one abort and returns an error naming the failure.

Gate: as commit 1.

## Commit 3 — phone: wait for the answer

`android/…/data/pairing/PairingClient.kt`,
`android/…/ui/pairing/PairingViewModel.kt`, plus the confirmation screen and
`strings.xml`.

`commitInternal` currently persists inside the `/devices/pair` response
handler. Split it:

1. Derive the secret and build an in-memory result. Persist nothing.
2. Poll `GET /devices/pair-status/{code}` every 2s until `confirmed`,
   `refused`, or `PairingConfirmTTL` elapses. A transport error is retried
   like the agent's own poll loop, not treated as refusal — the deadline is
   what ends the loop.
3. On `confirmed` only, run the four callbacks in today's order, with
   `onCredentialsReplaced` last. It kills live sockets on the slot, and a
   refused pairing must not do that.

`refused` and timeout throw distinct exceptions, so the ViewModel can carry
distinct messages. The two are not interchangeable to a user: one means the
operator said no, the other means nobody was there.

`PairingUiState` gains `AwaitingOperator(fingerprint)`, entered when the POST
returns and left when the poll resolves. It keeps the fingerprint on screen —
that is the window in which the operator is doing the comparison, so hiding
it would be actively unhelpful. `PairingViewModel.onConfirmed` moves through
`Pairing → AwaitingOperator → Success | Error`.

Add the Kotlin mirror of `PairStatusResp` next to the existing private
`@Serializable` DTOs in `PairingClient.kt`, where `DevicePairResponse` and
`PairingCodeInfoResponse` already live.

Tests — `android/app/src/test/…`, against `MockWebServer` as the existing
`PairingClientTest` does:
- status stays `pending` → nothing persisted, no callback fired.
- status `refused` → nothing persisted, refusal exception.
- deadline passes while `pending` → nothing persisted, timeout exception.
- status `confirmed` → all four callbacks, `onCredentialsReplaced` last.
- a transient 5xx mid-poll is retried, not treated as refusal.
- `PairingViewModelTest`: reaches `AwaitingOperator` with the same
  fingerprint as `AwaitingConfirmation`, then `Success`; a refusal reaches
  `Error` with the refusal message, not the generic one.

Gate: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
:app:detekt` (JDK 21+ for `lazysodium-java`). Note `:app:detekt` explicitly —
it is not in CLAUDE.md's build-and-verify command and was found broken by
three commits that skipped it.

## Verification on hardware

The case the issue was filed for, in order:

1. Update the relay (192.168.1.160) first, then the agent, then the app.
2. `cmux-bridge pair-device`, scan on the phone, confirm the fingerprint on
   the phone. Expect the app to sit on `AwaitingOperator` showing the
   fingerprint, not `Paired`.
3. Answer `n` at the agent's `Confirm? [y/N]:`. Expect the app to move to a
   refusal error, the slot to read `Not paired`, and
   `~/.config/cmux-bridge/direct-auth.db` to hold no device row for it.
4. Repeat, answering `y`. Expect `Success` and a working slot.
5. Repeat, and kill the agent at the prompt. Expect the app to time out after
   `PairingConfirmTTL` with the timeout message, not the refusal message.

Step 3's DB check, for the direct slot:

```
sqlite3 ~/.config/cmux-bridge/direct-auth.db \
  "select code, redeemed_at, confirmed_at, refused_at from pairing_codes order by rowid desc limit 3;"
```

## Follow-ups (not in this change)

- Retention/GC for `pairing_codes`; rows now survive an abort.
- cmux-app-af1 closes once this lands and step 3 above passes on hardware.
