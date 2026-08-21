# Plan: report a rejected standby credential

Design: `docs/superpowers/specs/2026-08-21-standby-credential-health-design.md`.
Issue: cmux-app-hr1.

Four commits. The first three are the app, smallest first, each independently
useful; the fourth is the agent and shares nothing with them.

Commits 2 and 3 are separable but not independently shippable in a useful
sense — commit 2 records a fact nothing reads yet. Keep them separate anyway:
the storage decision and the UX decision fail in different ways and should be
reviewable apart.

## Commit 1 — registerDevice reports what each slot said

`data/FallbackBridgeClient.kt`, plus tests.

Return a per-slot outcome list instead of discarding everything but a boolean.
Three cases, per the design's table: accepted, rejected (`BridgeException` with
code 401), unreachable (any other `IOException`, including 5xx).

The throw contract does not change: still only when no slot accepted. Both
existing callers ignore the return value, so they compile untouched.

Do not fold 403 in with 401. The servers do not currently issue 403 on these
routes, and quietly treating a future one as "credential destroyed" would be a
guess.

Tests:
- a 401 from one slot is reported as rejected while the other is accepted
  (this is the exact case that was silently dropped for eight days — assert the
  outcome list, not just that the call returned)
- an `IOException` from one slot is reported as unreachable, not rejected
- a 500 from one slot is unreachable, not rejected
- both slots rejected still throws, and still reports both
- the existing four `registerDevice*` tests keep passing unchanged

## Commit 2 — a per-slot record of a rejected credential

`data/SlotCredentialHealth.kt` (new), `data/AppContainer.kt`,
`data/BridgeGateway.kt`, plus tests.

A per-slot `StateFlow` of live / rejected / unknown. `unknown` is the start
state and is not the same as `live`: nothing has been checked yet this
process, and reporting "fine" on no evidence is how this got missed.

Only two transitions write: accepted → live, rejected → rejected. Unreachable
writes nothing at all — that is the guard from Decision 1 and the single most
important line in this commit, because getting it wrong turns every relay
outage into a false "re-pair your relay" alarm.

Shared from `AppContainer` for the same reason `sharedRelayHealth` and
`sharedSlotCredentials` are: one process, two transports, one answer.

`forgetSlot` resets its slot to unknown — the credential is gone by intent, not
rejected, and the two must not read the same.

A completed re-pair resets it the same way, in `PairingClient`'s
`onCredentialsReplaced` beside the existing `slotCredentials.invalidate(slot)`.
Without this the mark survives until the next launch probe, so the Connections
screen would still say "needs re-pair" on the screen the user just re-paired
from. A fresh credential is unknown, not rejected.

Wire the writes at the one place that produces the outcomes: whoever calls
`registerDevice`. Put that in `AppContainer` rather than in `MainActivity`, so
`CmuxMessagingService`'s call is covered by the same code — and while there,
`MainActivity.registerFcmToken`'s empty `catch (_: Exception)` gets a log; it
is the second of the two swallows the design names.

Tests (plain JVM — this class must stay constructible without Android, unlike
`AppContainer`):
- rejected then accepted returns to live
- unreachable after rejected leaves it rejected (reverting the guard fails it)
- unreachable after live leaves it live
- forget resets to unknown
- a completed re-pair resets to unknown
- each slot is independent

## Commit 3 — surface it before the outage

`ui/pairing/ConnectionSettingsScreen.kt`,
`ui/pairing/ConnectionSettingsViewModel.kt`, notification plumbing, plus tests.

The Connections screen shows the affected slot as needing a re-pair, naming the
recovery command rather than a generic error — `cmux-bridge pair-device
-direct` for DIRECT, and the relay's own pairing flow for RELAY.

A notification fires on the transition into rejected, and only once per
rejection. The in-memory health cannot express that on its own: it starts at
`unknown` every process, so on a phone whose credential is already dead every
launch looks like a fresh `unknown → rejected` transition and the notification
would fire every time — the nag this is meant to avoid.

So the suppression bit is persisted in `Settings`, per slot: "this rejection
has been reported". Cleared when the slot returns to live, is forgotten, or is
re-paired, so a credential that dies again does notify again.

Reuse the existing push notification channel rather than adding one.

Tests:
- the ViewModel exposes rejected per slot
- the notification fires once on transition and not again within the process
- **and not again in a fresh process while the rejection persists** — this is
  the case the in-memory state gets wrong; assert it against a `Settings` that
  survives, not a re-created holder
- returning to live and being rejected again notifies a second time
- `:app:detekt` (import ordering and declaration spacing are only caught here)

## Commit 4 — the agent says when it rejects

`internal/auth/middleware.go`, plus tests.

`auth.Require` logs the 401 at WARN with the route and a prefix of the
presented token's hash. Never the token; never the full hash — invariant 5.

Reuse `deviceLogID` if its truncation already matches what
`cmux-bridge devices list` prints (`displayedHashLen`, 12); otherwise take the
same prefix width so an operator can match a log line to a listing row without
converting anything.

Rate limit per prefix. A rejected client retries — that is normal, not an
attack — and cmux-app-5v1 is already a live example of what unbounded
per-attempt logging does to this file. `internal/ratelimit` exists; use it if
it fits, and if it does not, a small last-logged-at map keyed by prefix is
enough. Do not add a dependency for this.

Tests:
- a 401 logs once, carrying the prefix and not the raw token
- repeats inside the window do not log again
- a store failure still logs its existing `slog.Error` and still 500s — an
  infra error must not become an auth rejection
- the two existing `middleware_test.go` cases pass unchanged

## Verification

```
cd bridge && go build ./... && go vet ./... && go test ./...
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt
```

`internal/relay/multitenant_test.go` must pass (invariant 2). Nothing here
touches wire format, so invariant 3 has nothing to mirror.

On hardware, with the relay **up** — the whole point is detecting this before
an outage:

1. Pair both slots. Confirm both report live after a launch.
2. `cmux-bridge devices revoke <direct prefix>` on the Mac.
3. Relaunch the app. The relay still serves everything; confirm DIRECT is
   reported rejected, the notification fires once, and the Connections screen
   names the re-pair command. **This is the regression that was missed on
   2026-08-13** — before this change, step 3 looks completely normal.

   "The relay still serves everything" holds because RELAY is the primary today.
   After cmux-app-ml1 stage 1 flips the preference it holds only if ml1's 401
   carve-out is in place; without it this step goes from passing to total
   failure. Re-run this step after ml1 lands.
4. Relaunch again. Confirm the notification does not fire a second time.
5. Stop the relay. Confirm the app reports the direct slot's problem rather
   than a generic failure. Note what this does and does not prove: the report
   comes from the mark laid down at step 3's launch probe, not from the failing
   calls themselves. The error path is not smarter than before — unless the
   open decision below is taken.
6. `cmux-bridge pair-device -direct`, re-pair, relaunch. Confirm DIRECT
   returns to live and the mark clears.
7. With DIRECT live, stop the relay again. Confirm RELAY is reported
   unreachable and **not** rejected — the false-alarm case.
8. Confirm `cmux-bridge.log` carries one rejection line per revoked device,
   with a 12-char prefix and no token material.

## Decisions taken during implementation

**Should the normal call path also mark a slot rejected, not just the launch
probe? — TAKEN, as a fifth commit.** Marking only: a 401 still propagates
without failing over and still sets no `RelayHealth` penalty, so `call`'s
write-safety rule is untouched. cmux-app-ml1's 401-*failover* carve-out is a
separate change and did not land, because ml1 is parked. The original argument
follows.

A 401 is unambiguous per slot wherever it appears — on DIRECT it is
the agent's `auth.Require`, on RELAY it is the relay's own auth — so
`FallbackBridgeClient.call` could write `rejected` on any per-slot 401, with no
schedule and no new endpoint. That closes the gap the launch probe leaves: a
credential revoked *after* the last launch, discovered only when the other
transport dies.

It was not folded into commit 1 because it widens that commit from "report what
registerDevice already learned" to "classify every 401 the app ever sees",
which is a larger blast radius across every read and write call and wants its
own tests. It got them.

**cmux-app-ml1 would have forced the answer to yes anyway.** Once DIRECT is preferred rather than
held in reserve, `FallbackBridgeClient`'s "a 4xx from the primary never fails
over" rule turns a dead direct credential into a total outage while RELAY sits
healthy behind it. The fix there is to fail over on 401 specifically — safe
because both servers reject before the handler runs, so no write was applied —
and marking the slot rejected is the other half of the same change. So this is
optional only for as long as hr1 ships alone. If ml1 is going ahead, take it
here rather than bolting it on there.

## Follow-ups

- Remote re-pair of the direct slot, so recovery does not need physical access
  to the Mac. Deferred in the design; needs its own pass against the
  pairing-fingerprint model.
- cmux-app-8d3: `direct_last_served_at` is stamped by the reaper's own probe,
  so `cmux-bridge status` cannot corroborate any of this from the Mac side.
  Independent of these four commits.
- cmux-app-5v1: reconnect log flood. Commit 4's rate limiting is a local fix
  for one new line, not a fix for that.
