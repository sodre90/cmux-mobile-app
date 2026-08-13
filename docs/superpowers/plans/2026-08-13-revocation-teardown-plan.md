# Plan: close a revoked device's live sockets

Design: `docs/superpowers/specs/2026-08-13-revocation-teardown-design.md`.
Issue: cmux-app-dle.

Two commits, one per server. They share no code — the registries hold
different things (a `net.Conn` vs a teardown func) and the predicates read
different stores — so a shared abstraction would be one interface with two
implementations and no second caller. Keep them separate.

## Commit 1 — the relay closes the streams it dialed

`internal/relay/conntrack.go` (new), `proxy.go`, `relay.go`, plus tests.

A registry keyed by token hash holding the yamux streams `newProxy`'s
`DialContext` opened. `DialContext` wraps the conn so it registers on dial
and deregisters on `Close`; nothing else in the request path changes.

The sweep lists the store once per round, builds the set of live hashes, and
closes every tracked conn whose hash is absent or whose tenant is no longer
active. Started from `relay.go` beside the existing background loops.

Tests:
- a tracked conn whose row is revoked gets closed on the next sweep
- a conn whose row is still there survives — including when *another*
  device's row was revoked in the same round
- revoking a tenant closes its devices' conns
- `Close` deregisters, so a conn closed by normal traffic is not double-closed
  and does not leak an entry (the registry must not grow with request count)

`internal/relay/multitenant_test.go` must still pass: the sweep must never
close a conn belonging to a tenant other than the one revoked.

## Commit 2 — the agent cancels the sockets it upgraded

`internal/server/conntrack.go` (new), `terminal.go`, `events.go`, `agent.go`,
plus tests.

Same shape, holding a teardown func — not a `context.CancelFunc` as first
drafted. `/terminal` does exit its loops on cancellation, but `/events`
blocks on its frame channel and would not notice a cancelled context until
the next event arrived, which on an idle stream may be hours. Its teardown
unregisters from the hub (closing the channel, which ends the loop) and
closes the socket.

`/events` registers only when `X-Device-ID` is present: an empty device id is
the relay's own push-monitor subscription, and tearing that down would kill
FCM fan-out for every tenant on the relay.

The predicate is `sessions.SharedSecret(deviceID)`, the same call
`handleTerminal` makes at connect. Skip the sweep entirely when
`s.sessions == nil` — that is the plaintext test configuration, which has no
device identity to key on.

Tests:
- a socket whose shared secret was removed is torn down on the next sweep
- a socket whose secret is intact survives a round
- deregistration on normal close leaves no entry behind
- both of a device's sockets close, and the relay's subscription never does

## Verification

```
cd bridge && go build ./... && go vet ./... && go test ./...
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt
```

Deploy relay first, then agent, tagging rollback images first.

On hardware, with the emulator paired to the relay:

1. Open a terminal on a workspace and confirm it is streaming (type, see echo).
2. From the Mac, `cmux-bridge devices revoke <emulator prefix>` — the
   out-of-process path, which is the whole point.
3. Confirm the socket closes within the sweep interval, and that the app
   reports a disconnect rather than hanging on a dead-but-open socket.
4. Confirm the *other* paired device (the real phone, on its own row) keeps
   its connection through the same sweep — this is the regression that would
   matter most.
5. Repeat 1-3 against the direct listener.

Re-pair the emulator afterwards; it is the only device intentionally revoked.

## Follow-ups

- The phone currently learns of a closed socket as a generic disconnect and
  retries into a 401. Whether that should surface as "this device was
  revoked, re-pair" is a UX question, not part of this.
