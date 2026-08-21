# The standby slot has to report its own health

On 2026-08-21 the relay tunnel died at 08:14. The app failed over to DIRECT
exactly as designed and every request returned 401. Both transports were down
at once, and the phone had no way back — re-pairing the direct slot needs
`cmux-bridge pair-device -direct` at the Mac.

The direct credential had been dead since **2026-08-13**. Eight days, and
nothing said so.

That is the defect. The 401 itself is correct: `auth.Require` found no row for
the phone's token, because that row was revoked during the 2026-08-13 session
testing socket teardown (cmux-app-dle). Revocation worked. What failed is that
a destroyed standby credential is indistinguishable, from the phone, from a
credential that is fine — until the moment it is the only one left.

Full evidence in cmux-app-hr1. The short version: `direct-auth.db` holds one
device row, created 2026-08-10, whose secret went idle 2026-08-11;
`pairing_codes` records nine later direct pairings, eight confirmed, and not
one has a surviving row. The last of them, confirmed 2026-08-13T19:18:06Z, is
what the phone was still presenting.

## The fact that decides the design

The obvious shape is a new health-probe endpoint the app polls on the idle
slot. It is unnecessary, and the reason is the whole design.

**The app already probes both slots' credentials, once per app launch.**

`FallbackBridgeClient.registerDevice` deliberately fans out to RELAY *and*
DIRECT rather than stopping at the first success, because the relay and the
agent keep separate device tables and either may be the path a push arrives
through (cmux-app-vex). On the direct slot that request goes through
`auth.Require` before anything else, so a revoked credential answers 401 — the
exact signal we need, on the exact slot we cannot otherwise see.

Then it is thrown away:

```kotlin
for (target in targets) {
    try { target.registerDevice(fcmToken); registered = true }
    catch (e: IOException) { lastFailure = e }
}
if (!registered) throw lastFailure ?: BridgeException(0, "not configured")
```

`BridgeException` is an `IOException`, so the direct slot's 401 lands in
`lastFailure` and is discarded the moment the relay slot sets `registered`.
`MainActivity.registerFcmToken` then wraps the whole call in an empty
`catch (_: Exception)`, so even a total failure is silent.

For eight days, every launch of this app collected proof that the direct
credential was dead and dropped it on the floor. Nothing needs to be measured
that is not already being measured. It needs to be *reported*.

This also explains why no other layer caught it. `grep -rn '401\|unauthorized'
--include='*.kt'` over the whole app returns nothing: there is no concept of an
authentication failure anywhere in the client. Eight commits built out
server-side revocation (88667b2, 78a1bb9, 47b9520, cecc690, 8d8388f, 4d57e39
among them) and none has a client-side counterpart. The
2026-08-13-revocation-teardown plan closed with exactly this follow-up —
"whether that should surface as 'this device was revoked, re-pair' is a UX
question, not part of this". This is that question, answered.

## Decision 1: registerDevice reports per-slot outcomes

It returns what happened on each slot instead of a boolean. Its throw contract
is unchanged — still only when no slot accepted the token — so no existing
caller changes shape.

The three outcomes a slot can produce are not interchangeable and must not be
collapsed:

| outcome | means | what the app does |
|---|---|---|
| accepted | the credential is live | clear any dead mark |
| rejected (401) | the credential no longer exists on that server | mark the slot dead |
| unreachable (IO, 5xx) | says nothing about the credential | leave the mark as it was |

The third row is the one that matters most. A relay outage must never be read
as "the relay credential is gone" — that is the same mistake
`reapStrandedSecrets` guards against server-side when it abandons a whole
round rather than mistake an unanswered server for an empty one. The client
needs the same rule.

## Decision 2: a rejected credential is marked, never cleared

The app records "this slot's credential was rejected" per slot and surfaces it.
It does **not** clear the stored credential.

Auto-clearing is tempting because it makes the state machine tidy, and it is
wrong. A 401 can also come from a server rolled back to an older store, a
restored backup, or the narrow window during a re-pair. Destroying the
credential on one 401 converts every such hiccup into a mandatory trip to the
Mac. Marking is reversible.

Two things clear the mark, and both are needed. The next accepted registration
clears it, which covers the rolled-back-server case. But a **successful re-pair
must clear it immediately**, not at the next launch: `registerDevice` does not
run again until then, so without this the Connections screen would still read
"needs re-pair" on the very screen the user just re-paired from. A fresh
credential's status is unknown, not rejected. This is the twin of the reset
`forgetSlot` already performs, and it belongs at the same point re-pairing
already invalidates the slot's live sockets.

The mark is per slot and durable across attempts, so it does not belong in
`ConnectionMonitor` — that holds a single process-wide "what am I doing right
now" which flickers with every request. This is a different kind of fact and
gets its own per-slot holder.

## Decision 3: it has to be visible while the *other* slot still works

A banner that only appears once you are already locked out is worth nothing —
that is precisely the state this exists to prevent. The report has to land at
the moment of detection, which is while the healthy slot is still serving and
the user can still walk to the Mac.

So: the Connections screen shows the affected slot as needing a re-pair, and a
notification fires when a slot transitions to rejected. The transition, not the
state — a persistent nag every launch would be trained away in a week, which is
how this failure survives a second time.

That distinction cannot be drawn from the in-memory health alone. The health
holder starts at `unknown` in every process, so on a phone whose credential is
already dead, *every* launch is an `unknown → rejected` transition and the
"transition only" rule would fire on every one of them — the exact nag it
exists to avoid. Suppressing the repeat therefore needs one bit that outlives
the process: whether this rejection has already been reported. It is cleared
whenever the slot returns to live or is re-paired, so a credential that dies a
second time notifies again.

## Decision 4: the agent has to say when it rejects someone

`auth.Require` logs nothing on 401. Diagnosing this meant reading SQLite by
hand and correlating `pairing_codes` against reaper log lines.

It logs the rejection at WARN with the route and a **prefix of the presented
token's hash** — never the token, never the full hash (invariant 5). Rate
limited per prefix, because an unauthenticated client that retries is the
normal case and must not be able to flood the log — see cmux-app-5v1 for what
that already costs here.

The prefix is what makes the log actionable: it is the same value
`cmux-bridge devices list` prints, so a rejection can be matched against the
store without guessing.

## What is deliberately not being built

**A new probe endpoint.** It would need mirroring in Kotlin, the agent, and the
relay in one commit (invariant 3) to buy a check `/devices/register` already
performs on both slots without touching cmux.

**A background probe schedule.** App launch plus FCM token refresh is the
natural cadence: it is when you would act on the answer anyway, and a
credential that rots between launches is a credential on a phone that is not
being used. A WorkManager job for this is flexibility nothing is asking for.

Worth naming plainly, because it bounds everything above: **detection rides on
FCM.** `MainActivity.registerFcmToken` only reaches `registerDevice` if
Firebase initialises and the token fetch succeeds; with no
`google-services.json`, or with Play Services misbehaving, the probe never runs
at all and this whole mechanism is silent. On these devices push is
live-verified, so it holds in practice — but "app launch" is the cadence only
for a build that has working push, and a device without it has no probe. If
that ever stops being acceptable, the answer is to detach the probe from the
token fetch, not to add a schedule.

**A server→client revocation channel.** The relay is blind to content by
design and is frequently the very thing that is down. Detection at the client,
from a signal it already collects, needs no such channel.

**Remote re-pair of the direct slot.** Real, and out of scope: it touches the
pairing-confirmation model that exists to defeat relay MITM
(2026-07-10-pairing-mitm-fingerprint), so it needs its own design pass. With
detection in place the recovery window opens days before the outage, which is
what makes deferring it safe.
