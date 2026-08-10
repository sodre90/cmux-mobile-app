# Pairing confirmation signal (cmux-app-gmo)

Status: proposed
Issue: cmux-app-gmo (P2, bug) — blocks cmux-app-af1

## Problem

The phone declares a slot `Paired` before anyone has agreed to the pairing.

`PairingViewModel.onConfirmed` calls `PairingClient.commit`, and
`commitInternal` (`android/…/data/pairing/PairingClient.kt`) persists the
whole pairing the moment `POST /devices/pair` returns 200 — shared secret,
base URL, bearer token — then moves the screen to `Success`. Only *after*
that does the agent's poll observe the redemption, print its half of the SAS
fingerprint, and ask the operator `Confirm? [y/N]:`
(`bridge/cmd/cmux-bridge/pair.go`).

So the two ends of the fingerprint comparison are not symmetric. The phone
shows its fingerprint and commits; the agent shows its fingerprint and
decides. Nothing on the wire carries the decision back.

Commit `893d51a` (cmux-app-af1's other half) made a refusal destroy the
bearer token redemption had already minted, so the phone is no longer left
holding a live credential. It is still left holding a *dead* one, on a screen
that says `Paired`, until the user notices every request 401s and manually
Forgets the slot. The failure is loud now, but it is attributed to the wrong
thing.

## Root cause

Redemption and confirmation are the same event as far as the protocol is
concerned. `RedeemPairingCode` (`bridge/internal/auth/store.go`) mints the
token, inserts the device row and stamps `redeemed_at` in one transaction,
and `PairingCodeStatus` reports exactly one bit — `redeemed` — which the
agent reads as "the phone is here, go ask the human". There is no state
between "redeemed" and "finished", and no route by which an answer could
travel back to the phone.

## Requirements

1. Refusing at the agent's prompt leaves the phone on an error state with the
   slot still `Not paired`, and nothing persisted for that slot — no token,
   no base URL, no e2e secret.
2. Confirming leaves the phone `Paired`, as today.
3. A pairing whose operator never answers fails on the phone with a message
   distinct from a refusal, rather than a silent success.
4. Wire-format lockstep (CLAUDE.md invariant 3) across Kotlin, Go server, and
   the relay DTO copy.
5. Additive-only on the existing wire surface, per
   `2026-07-10-api-versioning-policy-design.md` — an existing field must not
   change meaning under the same JSON key.
6. The relay stays blind: it brokers the confirmation bit, it never sees key
   material (invariant 4).
7. `internal/relay/multitenant_test.go` keeps passing; every new route is
   tenant-scoped on the agent-facing side.

## Options considered

### Option A — carry the outcome back, token still minted at redemption (chosen)

Add a third state to the pairing code (`pending → confirmed | refused`), an
agent-facing route to set it, and a device-facing route to read it. The phone
completes `POST /devices/pair` exactly as today, holds the result **in
memory**, and persists nothing until it reads `confirmed`.

- Additive: `DevicePairResp` is untouched, one nullable column and two new
  routes are added. Nothing already on the wire changes meaning.
- The window in which an unconfirmed live token exists is unchanged from
  today, and `893d51a` already closes it on refusal.
- Costs a poll loop on the phone and a new UI state.

### Option B — mint the bearer token only on confirmation

`POST /devices/pair` records `device_pubkey` and returns no token; the
operator's confirmation mints it; the phone collects it on a poll. This kills
af1 at the root rather than compensating with a revoke.

Rejected on two counts.

*Wire.* `DevicePairResp.token` would become empty on a response that has
always carried a token — the precise "repurposing a field under the same JSON
key" the versioning note names as breaking. An older app build would store
`""` and fail silently later, which is worse than the bug being fixed.

*Security, and this is the decisive one.* The token has to reach the phone
somehow, so the poll response would carry it. The pairing code must therefore
stay redeemable-for-token for as long as the operator takes to answer, and
anyone who knows the code can collect the token first. Today the code is
consumed by the redeeming POST and the token is returned once, to the party
that presented `device_pubkey`. Option B widens "knows the code" into "owns
the pairing" for minutes. Closing that again means binding the poll to a
claim secret the phone proved at redemption — more machinery than Option A,
to reach a weaker place.

### Option C — phone probes an authenticated endpoint after pairing

Already rejected on the issue: a generic authenticated route cannot separate
"the operator has not answered yet" from "the operator refused". Worth
distinguishing from Option A, which is not this — Option A's route reports
the outcome explicitly and treats `pending` as a first-class answer.

## Chosen design

### Server (`internal/auth`, `internal/pairing`, `internal/wire`)

`pairing_codes` gains `confirmed_at TEXT` and `refused_at TEXT`, both
nullable, as additive `ALTER TABLE` migrations alongside the existing ones.
The derived state is:

| `redeemed_at` | `confirmed_at` | `refused_at` | state       |
|---------------|----------------|--------------|-------------|
| null          | —              | —            | `pending`   |
| set           | null           | null         | `pending`   |
| set           | set            | null         | `confirmed` |
| —             | —              | set          | `refused`   |

`AbortPairing` stops deleting the `pairing_codes` row and stamps `refused_at`
instead, keeping its existing device-token deletion unchanged. Deletion was
only ever there to prevent a second redemption, and `redeemed_at` already
does that — `RedeemPairingCode` rejects any row with `redeemed_at` set. The
row has to survive for the phone to have anything to read.

**Fail-closed timeout.** A `pending` row whose `redeemed_at` is older than
`wire.PairingConfirmTTL` reads as `refused`, computed on read rather than
written by a sweeper. This answers the issue's open question about an agent
that dies mid-prompt: the pairing resolves to refused on its own, with no
process needing to survive to make that happen. `PairingConfirmTTL` is
proposed at 5 minutes — long enough for a human to walk to the Mac, short
enough that a phone left waiting gives a real answer.

Two new routes, both in the shared `pairing` package so the relay and direct
mode serve them byte-identically:

- `POST /agent/pairing-code/{code}/confirm` — agent-facing, tenant-scoped
  exactly like `pairingCodeStatus` and `abortPairing`. Stamps `confirmed_at`.
  Rejects with 409 if the row is already refused, or if it is past
  `PairingConfirmTTL`, so an agent that comes back from the dead late is told
  its confirmation did not take rather than silently disagreeing with a phone
  that already gave up.
- `GET /devices/pair-status/{code}` — device-facing, returns
  `{"state": "pending"|"confirmed"|"refused"}` and nothing else. 404 for an
  unknown code.

**The device-facing route is deliberately unauthenticated**, matching
`/devices/pair` and `/devices/pair-info` on the same vhost. Two reasons.
The response carries no token, no key material and no tenant id — one enum
value whose only reader is a phone that already knows the code. And requiring
the bearer token would actively break the design: `893d51a` destroys that
token on refusal, so the refused case would answer 401, which is
indistinguishable from a misconfigured or expired credential. An
unauthenticated route can say `refused` out loud.

### Agent CLI (`cmd/cmux-bridge/pair.go`)

After `confirmFingerprint` returns true, `pairDevice` persists the e2e
session locally (`sessions.AddDevice`, unchanged) and then POSTs the confirm
route. Confirmation is the last step and the single commit point.

Every failure path stays `abandonPairing`, which already revokes the token
and now also stamps `refused_at`. If `AddDevice` fails, the phone sees
`refused` instead of hanging until its timeout. If the confirm POST fails,
the local e2e row survives but is unreachable — it is keyed by a token hash
whose device row `abandonPairing` just deleted, so nothing can authenticate
as it.

### Phone (`data/pairing/PairingClient.kt`, `ui/pairing/PairingViewModel.kt`)

`commitInternal` splits: derive the shared secret and hold
`{token, baseUrl, secret, agentPublicKey}` in memory, poll
`/devices/pair-status/{code}` every 2s (mirroring the agent's own poll
period) until the state resolves or `PairingConfirmTTL` elapses, and only
then run the four persist callbacks.

The ordering of those callbacks matters and gets stricter, not just later.
`onCredentialsReplaced` tears down sockets running on the slot's previous
credentials (cmux-app-smu). Firing it for a pairing that is subsequently
refused would kill a *working* connection on behalf of a pairing that never
happened. It must stay last, after `confirmed`.

`PairingUiState` gains `AwaitingOperator(fingerprint)` between `Pairing` and
`Success` — it keeps showing the fingerprint, because that is exactly the
comparison the operator is making at the same moment. `refused` and timeout
both land on `Error` with distinct messages: a refusal is an answer, a
timeout is the absence of one, and the user's next action differs (re-pair
deliberately vs. go find out whether the agent is running).

## Security analysis

- The relay brokers one enum value per pairing code. It could lie: claim
  `confirmed` for a pairing the operator refused, or `refused` for one they
  accepted. Neither gains it anything. A forged `confirmed` makes the phone
  persist a token the relay already destroyed, reproducing today's dead-token
  symptom and nothing worse; the AEAD session behind it does not exist on the
  agent, so no traffic flows. A forged `refused` is a denial of service the
  relay can already perform by dropping the pairing POST. The SAS fingerprint
  remains the anti-MITM mechanism (`2026-07-10-pairing-mitm-fingerprint-design.md`);
  this change does not add to what the relay is trusted for.
- The unauthenticated status route leaks one bit to a caller who guesses an
  8-character code inside its TTL. A caller who guesses a code *before*
  redemption can steal the pairing outright, which is a strictly larger
  capability and is what the existing rate limiter on `/devices/pair` and the
  single-use semantics defend. No new rate limiting is proposed for a
  read-only endpoint that returns one enum.
- Rows now linger where `AbortPairing` used to delete them. Redeemed rows
  already linger for the same reason (the agent's poll needs them), so this
  changes the volume, not the property. No retention policy exists today for
  either; noted as a follow-up rather than smuggled into this change.

## Test plan

Go (`bridge`):
- `internal/auth`: confirm stamps and is tenant-scoped; confirm on a refused
  row fails; confirm past `PairingConfirmTTL` fails; a pending row past the
  TTL reads as refused; `AbortPairing` still revokes the token and now leaves
  a row that reads `refused`; a refused code is still unredeemable.
- `internal/pairing` via both mounts (`internal/relay/pairing_test.go`,
  `internal/server/direct_pairing_test.go`): the confirm route requires the
  agent CN / rejects a foreign tenant with the same 403/404 split
  `abortPairing` uses; the status route is reachable with no credential and
  returns only `state`.
- `cmd/cmux-bridge`: a confirmed pairing POSTs confirm exactly once after
  `AddDevice`; a failed `AddDevice` aborts and does not confirm; a failed
  confirm POST aborts.
- `internal/relay/multitenant_test.go` unchanged and passing.

Kotlin (`android`):
- `commitInternal` persists nothing while the status stays `pending`.
- `refused` persists nothing and surfaces a refusal message distinct from the
  timeout message.
- Timeout persists nothing.
- `confirmed` persists all four, with `onCredentialsReplaced` last.
- `PairingViewModel` reaches `AwaitingOperator` before `Success` and carries
  the same fingerprint through both.

Manual, on hardware — the case that motivated the issue: start
`cmux-bridge pair-device`, scan, confirm the fingerprint on the phone,
answer `n` at the agent prompt, and check the app shows an error with the
slot still `Not paired` and no credential stored.

## Deployment ordering

Relay first, then agent, then app. An agent on the new binary POSTing confirm
to an old relay gets 404 and abandons every pairing; an app polling an old
relay gets 404 and times out. Both fail closed, both are noisy, and both are
avoided by updating the relay first — the same constraint `893d51a` carries.

## Follow-ups (not in this change)

- Retention/GC for `pairing_codes`. Rows accumulate today and accumulate
  slightly faster after this.
- Nothing here helps a pairing the operator confirms while the phone is
  killed: the agent holds an e2e session the phone cannot use, recoverable
  only by re-pairing. Worth an issue if it is ever observed.
