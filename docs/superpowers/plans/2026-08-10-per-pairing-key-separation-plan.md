# Per-pairing key separation — implementation plan (cmux-app-1fx)

Design: `docs/superpowers/specs/2026-08-10-per-pairing-key-separation-design.md`
Issue: cmux-app-1fx (P0) — blocks cmux-app-a3g

One logical item per commit, per CLAUDE.md. Commits 1 and 2 are independent
and can land in either order; commit 3 depends on 1.

## Commit 1 — agent: reject a duplicate shared secret in `AddDevice`

`bridge/internal/e2e/store.go`

Inside `AddDevice`'s existing transaction, before the upsert, fail when some
*other* `device_id` already holds this `shared_secret`. Must be inside the
same transaction as the insert, or a concurrent pair races past it.

Re-pairing the same `device_id` stays legal (that is the existing
`ON CONFLICT … DO UPDATE` path, and the row it would replace is by definition
not a *different* device).

Tests — `bridge/internal/e2e/store_test.go`:
- second `device_id`, same secret → error, and the first row's counters are
  unchanged.
- same `device_id`, same secret → still succeeds (re-pair is not a regression).

Then repair the fixtures that this newly rejects. `store_test.go` seeds
`"secret"`/`"secret1"` across several devices, and
`internal/server/direct_test.go`, `internal/server/encryption_test.go` and
`internal/relay/testpush_test.go` each call `AddDevice`. Give each device a
distinct secret; do not relax the check to accommodate a fixture.

Gate: `cd bridge && go build ./... && go vet ./... && go test ./...`, with
`internal/relay/multitenant_test.go` explicitly passing.

## Commit 2 — phone: per-pairing ephemeral keypair

`android/…/data/pairing/PairingClient.kt`, `…/data/e2e/Identity.kt`,
`…/data/AppContainer.kt`

`prepare` currently reads `identity.publicKey` and `commit` reads
`identity.privateKey`/`publicKey`. Replace with a keypair generated in
`prepare` and consumed by the matching `commit`.

The two calls are separated by the fingerprint confirmation screen, so the
pending keypair has to live on the `PairingClient` instance between them.
Constraints on that state:
- `commit` without a preceding `prepare` must not silently pair under a
  stale or absent key — generate on demand in that path, or reject it.
- A second `prepare` (user backs out and rescans) must replace the pending
  keypair, so the fingerprint the user confirms is always the key that gets
  submitted.
- `PairingClient` is per-slot, so relay and direct never share pending state.

Delete `Identity` and `AppContainer.identity` once nothing reads them — its
persistence *is* the defect, and leaving it in place invites reuse.

Check `resolveManualCode`/`prepareInternal` on the manual-entry path: the
design requires it to go through the same ephemeral keypair as the QR path.

Tests — `android/app/src/test/…`:
- two `prepare`/`commit` cycles against one agent pubkey → different
  `device_pubkey`, different derived secret.
- the fingerprint returned by `prepare` matches the key `commit` submits.
- re-`prepare` before `commit` uses the second keypair, not the first.

Gate: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
(needs JDK 21+ for `lazysodium-java`).

## Commit 3 — cross-language regression test for the counter reset

The defect is a *cross-language* property, so assert it where it can actually
regress: a frame sealed under pairing A must fail to open under pairing B,
rather than being accepted and advancing the replay window.

Kotlin side is the cheap one and catches the phone-side half:
`Frame.kt`'s `decryptFrame` must reject a frame from a foreign session with
an AEAD failure — not a window rejection, and above all not a success. This
is the test that would have caught cmux-app-a3g.

Go side already gets its half from commit 1's `AddDevice` guard.

## Verification on hardware

The fix does not migrate existing pairings; recovery is one re-pair per slot.

1. Build and install the debug APK.
2. Re-pair the direct slot. Confirm the new row's secret differs from every
   existing row:
   `sqlite3 ~/.config/cmux-bridge/sessions.db "select substr(hex(shared_secret),1,8), count(*) from devices group by shared_secret order by 2 desc;"`
   — the 18-row group must not grow.
3. Re-pair the relay slot; confirm it differs from the direct slot's secret.
4. Exercise a terminal pane on both slots and confirm no `decrypt_failed`.

Note: `send_counter` on the live row is currently at the manual 300000
stopgap from 2026-08-10 (see cmux-app-a3g). Re-pairing supersedes it with a
fresh, independently-keyed row starting at 0, which is now safe. The stopgap
does not need unwinding.

## Follow-ups (not in this change)

- Stale rows: 18 in `sessions.db`, 5 in `direct-auth.db` for one phone, each a
  live bearer token. Belongs with token revocation; not yet its own issue.
- `f405e0c` (real-socket regression test) is committed locally and unpushed.
