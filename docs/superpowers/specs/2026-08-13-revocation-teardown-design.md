# Revocation should close the sockets it revokes

`cmux-bridge devices revoke` and `POST /devices/self-revoke` delete the token
row and the shared secret, so the next *request* from that device 401s
(verified live 2026-08-11). An already-open terminal or events WebSocket is
untouched: both servers authenticate at connect time only, so a revoked
device keeps streaming until the socket happens to drop — which, for a
healthy connection, is never.

For the phone's own Forget that window is harmless; the app is tearing itself
down anyway. For the case revocation actually exists to serve — a lost or
stolen phone, revoked by the operator — leaving the live session running
means the revocation did not do the one thing it was for.

## The fact that decides the design

The obvious shape is a hook: every revocation path also closes that device's
sockets. It cannot work, and the reason is worth writing down.

`cmux-relay devices revoke` (cmd/cmux-relay/commands.go) and
`cmux-bridge devices revoke` (cmd/cmux-bridge/devices.go) are **separate
processes** from the running daemon. They open the store file directly and
delete a row. They have no handle on the daemon's memory, and the daemon
gets no notification. A hook at the revocation call site would cover the two
HTTP paths and miss the operator CLI — the exact case that matters most.

Any fix that makes the CLI reach into the daemon (a control socket, a signal,
an admin endpoint the CLI calls) buys back that one case by adding a second
way for the two to talk, and still leaves anything that edits the store
directly uncovered.

## Decision: re-run the connect-time check on a timer

**Each server keeps a registry of live sockets keyed by device id, and
periodically re-evaluates the same predicate the connect path used, tearing
down every socket that no longer satisfies it.**

Nothing is wired into any revocation path. One mechanism covers all of them —
the two HTTP routes, both CLIs, a tenant revocation, and a hand-edited
database — because it does not care *how* the row went away, only that it is
gone.

This is the pattern the shared-secret reaper (cmux-app-f5y) already
established in this codebase: revocation is authoritative and immediate at
the store; everything downstream of it reconciles.

The predicate is per server, and in each case is literally the check that
handler already makes at connect:

| | connect-time check | what the sweep re-runs |
|---|---|---|
| relay | `auth.Require` resolved a device, tenant active | device hash still in `store.List()`, `TenantActive` |
| agent | `sessions.SharedSecret(deviceID)` exists | same call |

The agent's predicate covers both of its modes without a special case:
in relay mode the agent has no device token at all, and "still paired" is
exactly "still has a shared secret"; in direct mode revocation removes the
token and the secret together.

## What gets closed, and what that costs

**Relay.** Every proxied request dials a fresh yamux stream to the agent
(`newProxy`'s `DialContext`). For a WebSocket that stream lives as long as
the socket. Wrapping the returned `net.Conn` so it registers under the
device's token hash and deregisters on `Close` gives the registry for free,
with no change to the proxy's request path. Closing the stream ends
`ReverseProxy`'s bidirectional copy, which closes the hijacked client
connection too.

**Agent.** `handleTerminal` and `handleEvents` already hold a `deviceID`, so
registering a teardown func under it and calling that on sweep is the whole
change. The two handlers stop differently: `/terminal` exits its loops on
context cancellation, while `/events` blocks on its frame channel and has to
be unblocked by unregistering from the hub, so the registry holds a func
rather than a `context.CancelFunc`. `/events` also serves the relay's own
push-monitor subscription, which carries no device id and must never be
registered.

The cost is latency: a revoked socket survives up to one sweep interval.
30s is the proposed bound — small against "until the connection drops on its
own", and long enough that the sweep is free at any realistic device count.

Not in scope: making the *phone* notice. A device whose socket is closed
sees a normal disconnect and will retry, get a 401, and surface that through
the existing unauthenticated path. That is already the behaviour for a
revoked device that reconnects.

## Why not shorten the window instead

Re-checking auth on every frame would close the window entirely, and is
wrong: it puts a store read on the hot path of every keystroke and every
render update, to protect against a window that a 30s sweep already bounds.
The frames are also the one path that must stay cheap — the terminal is
interactive.
