# Plan: make the two transports peers

Design: `docs/superpowers/specs/2026-08-21-parallel-transports-design.md`.
Issue: cmux-app-ml1. Depends on cmux-app-hr1 (see the design's ordering note).

Stage 1 is three app-only commits. Stage 2 is two more and touches the wire —
take it only if the reconnect gap proves to matter after stage 1 is live.

## Stage 1 — the slots become peers

### Commit 1 — RelayHealth becomes per-slot transport health

`data/RelayHealth.kt` → `data/TransportHealth.kt`, `data/AppContainer.kt`,
plus tests.

Same mechanism — penalty window, `recoveries` counter, shared instance — keyed
by `ConnectionSlot` instead of hardcoded to RELAY. Pure refactor: no behaviour
change in this commit, so the existing `RelayHealth` tests port across and must
still pass with only the slot argument added.

Keep `recoveries` a counter, for the reason its own doc gives: a subscriber
reads it before committing to a slot and waits for it to differ, so a recovery
landing mid-connect cannot be missed.

Do this first and alone. It touches every call site, and mixing it with the
preference flip would make the diff that changes behaviour unreviewable.

### Commit 2 — preference is computed, and DIRECT is tried first

`data/FallbackBridgeClient.kt`, `data/AppContainer.kt`, plus tests.

Preference is derived from both per-slot holders: prefer DIRECT unless it is
inside its penalty window, unconfigured, or **known rejected** (cmux-app-hr1's
`SlotCredentialHealth`), else RELAY. `primary`/`fallback` stop being constructor
roles and become a computed pair.

`call()` gains the one carve-out the design requires: **a 401 from the preferred
slot marks that slot rejected and retries the other slot.** Every other 4xx
keeps propagating without failover — that guarantee is what stops a
non-idempotent write from running twice, and 401 is exempt only because both
servers reject before the handler runs, so nothing was applied. Write this
narrowly: match the status, not a range.

Without this, stage 1 makes cmux-app-hr1 catastrophic instead of latent — DIRECT
preferred with a dead credential means every call fails while RELAY sits healthy
behind it. This commit must not land without it.

`httpClient`'s connect timeouts swap with the roles: the slot being tried first
gets the 3s leash, the one behind it keeps OkHttp's default. Derive this from
the preference rather than from `slot == RELAY`, or it silently goes back to
being wrong the first time preference flips.

Bound the away-from-home stall, per the design — escalating penalty on repeated
connect failure, and/or gating DIRECT-first on `ConnectivityManager` reporting
an active VPN transport. Take at least one; see Open decisions.

`registerDevice` is untouched — it already fans out to both slots
unconditionally and must keep doing so.

Tests:
- DIRECT is tried first when neither slot is penalized
- a penalized DIRECT sends traffic to RELAY, and the reverse
- neither slot penalized and DIRECT unconfigured → RELAY, no wasted attempt
- **a 401 from the preferred slot fails over to the other slot and succeeds**
  (the cmux-app-hr1 scenario; write this one first)
- **a 401 from the preferred slot marks that slot rejected, and the next call
  prefers the other slot without retrying the dead one**
- a 400/403/404/409 from the preferred slot still propagates and does **not**
  fail over (the existing guarantee; writes must never re-run on the other slot)
- a 401 from both slots throws rather than looping
- `BothTransportsFailedException` still names both causes with the right slot
  attribution now that either can be the one that was skipped
- the existing `FallbackBridgeClientTest` suite passes with only preference
  expectations updated

### Commit 3 — the reconnect loop stops being relay-shaped

`data/SocketReconnector.kt`, plus tests.

`parkedOnDirect` becomes "parked on the non-preferred slot"; the recovery
watcher watches the preferred slot's health rather than `relayHealth`
specifically. `RELAY_STABLE_MS` and `RELAY_DROP_THRESHOLD` are renamed and
applied to whichever slot is primary for that attempt.

The `?: other()` fallback, the credential-generation watchers and the backoff
are unchanged.

Tests:
- a socket parked on RELAY ends itself when DIRECT recovers (the new direction;
  the existing test covers the old one and must still pass)
- consecutive framing failures penalize whichever slot was primary
- a socket on its preferred slot is not disturbed by the other slot's recovery
- `:app:detekt`

## Stage 2 — both events sockets open at once

Do not start this until stage 1 has been live long enough to know whether the
reconnect gap is a real annoyance. The design says why it is optional.

### Commit 4 — EventFrame gains a stable id (wire-format lockstep)

`bridge/internal/wire/events.go`, `bridge/internal/server/events.go`,
`bridge/internal/relay/relay.go` if it mirrors the frame,
`android/.../model/Dtos.kt`, plus tests **on every side touched** — invariant 3,
all in one commit.

The id is minted in `server.classify`, where one cmux event becomes one
`wire.EventFrame`, so both transports carry the same id for the same event.
It must be stable per event and not derived from arrival time, or the two
transports would disagree.

Check first whether the raw cmux frame already carries something unique that
can be adopted. cmux is a black box (invariant 1) and is read only through
`cmux rpc` / `cmux events`, so this is an observation about its output, not a
request for it to change. Minting locally is the fallback, not the default.

### Commit 5 — the app runs both sockets and de-duplicates

`data/EventsSocket.kt` call sites, the events subscription, plus tests.

Both slots' events sockets open concurrently; frames merge into one flow
filtered through a **bounded** LRU of seen ids. Terminal and all writes stay on
the preferred slot — this commit must not touch `TerminalSocket`.

Push de-duplication by the same id lands here too, per the design.

Tests:
- the same event arriving on both slots is delivered once
- two distinct events that would collide under a composite key are both
  delivered (this is the test that justifies the wire change; write it first)
- one slot dying leaves the stream unbroken with no reconnect
- the LRU is bounded — a long stream does not grow it without limit
- both e2e sessions advance independently and neither reuses a counter

## Verification

```
cd bridge && go build ./... && go vet ./... && go test ./...
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt
```

`internal/relay/multitenant_test.go` must pass (invariant 2).

On hardware, after stage 1, with both slots paired and the relay up:

1. On Tailscale at home: confirm traffic goes over DIRECT, not RELAY — this is
   the change, and `ConnectionStatusStrip` should say so.
2. Drop Tailscale on the phone. Confirm it falls through to RELAY quickly (the
   3s leash now applies to DIRECT) and keeps working.
3. Restore Tailscale. Confirm live sockets come back to DIRECT rather than
   staying pinned to RELAY.
4. Stop the relay. Confirm DIRECT carries everything with no user-visible
   change — the 2026-08-21 scenario, which should now be a non-event.
5. With the relay **up**, revoke the direct credential
   (`cmux-bridge devices revoke <direct prefix>`) and use the app. Confirm it
   fails over to RELAY on the 401 and keeps working — **this is the case stage 1
   breaks if the carve-out is missing**, and it is the same hardware step as
   cmux-app-hr1's step 3, whose expected result changes once this ships.
6. Reply to a feed item, rename a workspace and toggle YOLO while a slot is
   flapping, and again against a slot whose credential is revoked. Confirm each
   applied **exactly once** — the non-idempotent writes are the thing most at
   risk from a preference that can change mid-call, and the 401 carve-out is a
   deliberate hole in the rule that protects them.
7. Confirm a terminal session stays on one slot for its lifetime and does not
   double-echo input.
8. Off Tailscale entirely (cellular, VPN down): confirm the app is not stalling
   on a doomed DIRECT connect on a fixed cycle — whichever bound from commit 2
   was taken, this is what it exists to prevent.

## Open decisions

**How is the away-from-home stall bounded?** Preferring DIRECT unconditionally
is right at home and a regression away from it: off Tailscale the phone dials a
100.x address on every penalty expiry and stalls up to the leash, where today it
never dials at all. Escalating penalty makes that decay; gating DIRECT-first on
`ConnectivityManager`'s active-VPN transport mostly removes it. They compose.
Not open is whether to take one — commit 2 must not ship with neither.

**Should preference consider latency, not just reachability?** Stage 1 prefers
DIRECT whenever it is not penalized, which is a guess on a network where both
work and DIRECT is the slower one
(phone on cellular, Tailscale relaying through DERP rather than connecting
peer-to-peer). Measuring would mean recording per-slot round-trip times and
choosing on them — more mechanism, and it can be added later on top of
`TransportHealth` without redoing anything here. Left out on purpose; raise it
if DERP-routed Tailscale turns out to be common in practice.
