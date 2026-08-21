# Using both transports, instead of keeping one in reserve

Today the two slots are not peers. `ConnectionSlot` states it outright —
"Relay is always primary, direct is always the fallback" — and
`FallbackBridgeClient` only touches DIRECT once RELAY has already failed.

That has two costs, and 2026-08-21 collected both at once.

**The standby rots.** A slot nothing exercises is a slot whose credential can
be dead for eight days without anyone knowing (cmux-app-hr1). Detection fixes
the symptom; using the slot removes the condition.

**The good path is never taken.** From home, RELAY is
phone → internet → DDNS → nginx → home-server → yamux tunnel → Mac. DIRECT is
phone → Tailscale → Mac. The app currently prefers the first one
unconditionally, including when both are up and the second is plainly better.

The fixed-role decision was made in
`2026-07-04-dual-pairing-automatic-fallback-design.md` on the grounds that
"relay works from any network; direct only works when Tailscale is up on the
phone", and was flagged in that document as proposed-but-unconfirmed because
the question timed out. Two of that spec's other non-goals — push over direct,
a visible active-slot indicator — have since been overturned by later work.
This one is now overturned too.

## The fact that decides the design

"Use both in parallel" cannot mean "send everything down both paths", and the
reason is not cost — it is correctness.

- **Writes must stay single-path.** `replyFeed`, `renameWorkspace` and
  `setYoloMode` are not idempotent. `FallbackBridgeClient` already refuses to
  re-run them across slots after a 4xx for exactly this reason; duplicating
  them deliberately would be worse.
- **A terminal must stay single-path.** Two sockets on one surface means input
  delivered twice and output rendered twice.
- **Events could go down both — but cannot be de-duplicated today.**

That last point is the constraint that shapes everything. `wire.EventFrame` and
its Kotlin mirror carry `type`, `name`, `needs_attention`, `feed_id`,
`workspace_id`, `surface_id`, `title`, `kind` — and **no unique event id**. A
composite key built from those fields is not sound: two genuinely distinct
attention events on the same surface are indistinguishable under it, and
de-duplicating them would silently drop a real notification.

Giving the frame a real id is possible but is a wire-format change, which under
invariant 3 lands in Kotlin, the agent and the relay in a single commit with
tests on every side.

So the work splits along that line, and the split is real rather than
administrative: everything achievable without touching the wire is worth doing
on its own, and is worth having before the part that does.

## Stage 1: the slots become peers (no wire change)

**Decision: preference is computed, not fixed. DIRECT is tried first; RELAY
serves whenever DIRECT is penalized or unconfigured.**

This is the whole of "use both" in ordinary life. At home the phone is on
Tailscale and DIRECT serves — the short path, and the credential is proven
continuously. Away from home DIRECT fails its connect, takes a penalty, and
RELAY serves every call for the window. Both slots are exercised by normal use,
on whatever schedule the user's own movement provides. Nothing needs a probe or
a timer.

It also needs no new mechanism: `RelayHealth` already implements exactly this
(penalty window, recovery signal, shared instance). It stops being
relay-specific and becomes per-slot health, with the preference derived from
the pair.

Three consequences follow and none is optional:

**The connect timeouts swap.** `AppContainer.httpClient` gives RELAY a 3s
connect timeout and leaves DIRECT on OkHttp's default, reasoning that "Direct
has no second fallback to race against". Once DIRECT is tried first that
reasoning inverts: DIRECT needs the short leash so an off-Tailscale phone falls
through quickly, and RELAY — now the one with nothing behind it — gets the
longer one.

**`SocketReconnector`'s recovery logic generalizes.** Its `parkedOnDirect` flag
and the `relayHealth.recoveries` watcher encode "came back to RELAY once it
recovers". The same behaviour is needed in both directions: a socket that
settled for the non-preferred slot ends itself when the preferred one is proven
healthy again. Same mechanism, no longer named after one slot.

**`RELAY_STABLE_MS` / `RELAY_DROP_THRESHOLD` apply to whichever slot is
primary.** The consecutive-framing-failure escalation is a property of "the
slot we keep choosing", not of the relay.

### The cost, stated: away from home gets slower

The 2026-07-04 rationale — "relay works from any network; direct only works when
Tailscale is up on the phone" — was unconfirmed as a *decision*, but its content
is not wrong, and stage 1 does regress the case it describes.

Today, off Tailscale, there are zero wasted connect attempts: RELAY is primary
and DIRECT is never dialled. After stage 1 the phone attempts DIRECT every time
the penalty window expires, stalling up to the connect leash before falling
through, for as long as the user is away. And the failure is not always a clean
refusal: `ts.net` names resolve publicly to the 100.x address, and on cellular
100.64/10 is carrier CGNAT space — so the connect can hang the full leash, or
reach an unrelated host. TLS certificate verification protects correctness
there; it does not give back the seconds.

Two ways to bound it, both cheap:

- **Escalating penalty.** A slot that keeps failing its connect gets a longer
  window each time, capped. The stall frequency then decays instead of
  recurring on a fixed period, and it needs nothing outside `TransportHealth`.
- **Gate DIRECT-first on an active VPN transport.** `ConnectivityManager`
  reports whether a VPN transport is up, which is a free local signal — no new
  dependency, no permission, and unlike the Tailscale API rejected below it is
  not asking a third party for its opinion. It is coarse (any VPN, not
  specifically Tailscale), so it gates the *first attempt*, never the outcome.

These compose, and the second one removes most of the stalls the first one
merely decays. Which to take is left to review; what is not left open is that
"prefer DIRECT unconditionally" must not ship with neither.

**Not changed:** writes and terminal remain single-path, on the currently
preferred slot. Stage 1 changes *which* slot that is and *how* the choice is
made, never *how many* slots a given call touches.

### The one 4xx that must fail over

`FallbackBridgeClient` propagates a 4xx from the primary immediately and never
retries the other slot, so a non-idempotent write cannot run twice. That rule
is currently harmless because the primary is RELAY. Flip the preference and it
becomes a trap: with DIRECT preferred and its credential revoked — the exact
2026-08-21 state — **every call fails at home while RELAY sits healthy behind
it.** Stage 1 would convert a latent problem into a total outage.

So one status is carved out: **401, and only 401, fails over.** It is safe
where the other 4xx are not, because both servers reject before the handler
runs — `auth.Require` on the agent, the relay's own auth on the relay — so the
request was never applied and there is nothing to double-execute. A 400, 403,
404 or 409 keeps propagating exactly as today.

A 401 therefore does two things at once: it marks that slot's credential
rejected (cmux-app-hr1's health record) and it retries the other slot. And the
preference computation reads that mark — a slot known to be revoked is never
preferred, so the app settles onto the working transport instead of rediscovering
the failure on every call.

This is what forces the answer to hr1's open decision about whether the normal
call path should classify 401s. On its own that was optional. Under stage 1 it
is load-bearing, and stage 1 must not land without it.

## Stage 2: the events stream runs on both at once (wire change)

**Decision: `EventFrame` gains a stable id; the app keeps an events socket open
on both slots and de-duplicates by that id.**

This is the literal reading of parallel, and what it buys is specific: failover
with no reconnect gap, because the other socket is already open, authenticated
and streaming — and continuous proof of both credentials rather than
proof-when-you-happen-to-move.

The id is minted where the event is classified (`server.classify`), so one
cmux event fans out to both transports carrying the same id. The app holds a
bounded LRU of recently seen ids and drops repeats. Bounded, not unbounded: an
events stream is long-lived and a set that only grows is a leak.

Costs, stated rather than discovered later:

- A second permanent WebSocket with 20s keepalive pings, on a phone.
- Both slots' e2e sessions advance their replay counters continuously. That is
  correct — they are independent sessions with independent secrets — but it
  doubles counter churn and so doubles the durability writes that
  cmux-app-android-e2e-durability-fix made mandatory.
- Duplicate push becomes materially more likely. Both slots are already
  registered for FCM and direct-mode push is live; the same event id is what
  would let the phone drop a push it has already shown, which is why this
  stage is where that gets fixed rather than earlier.

Stage 2 is genuinely optional. If stage 1 lands and the reconnect gap turns out
not to bother anyone, the remaining benefit is continuous credential proof —
which cmux-app-hr1's detection already covers at launch, more cheaply.

## Relationship to cmux-app-hr1

This builds on it and does not replace it.

Parallel use makes a dead credential surface within minutes instead of within
eight days, which is a large improvement. It does not make the app *understand*
what it saw: without hr1, a revoked slot still reads as a generic failure, gets
retried forever, and prompts nobody to re-pair. And the agent still logs
nothing when it rejects.

The ordering that makes sense is hr1 first, because stage 1's preference cannot
be computed without hr1's record: the carve-out above requires that a slot known
to be rejected is never preferred, and that fact lives in hr1's
`SlotCredentialHealth`.

Two per-slot holders result, and they stay separate on purpose:
`TransportHealth` answers "can I reach this slot right now" and is
self-clearing; `SlotCredentialHealth` answers "does my credential still exist
there" and clears only on an accepted registration or a re-pair. Different
lifetimes, different failure modes, and collapsing them is exactly the mistake
in decision 1 — an unreachable server must never read as a destroyed
credential. Preference reads both.

## Rejected

**Racing every read on both slots, first success wins.** Doubles read traffic
and agent load permanently to save a fallback round-trip that only matters
while one slot is failing. Stage 1's penalty window already skips a slot known
to be down, so the race would be won by the same slot almost every time.

**A manual switcher.** The original design rejected it and was right: it is a
toggle you have to remember to flip, and the case where it matters is the case
where you are not there to flip it.

**Deriving preference from a Tailscale reachability API.** Attempting DIRECT
and seeing what happens is the same signal with no new dependency, no
permission, and no way to disagree with reality.
