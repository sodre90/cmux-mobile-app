# Phone-side self-revocation — design (cmux-app-f5y)

Follows `2026-08-11-device-revocation-design.md`, which deliberately scoped
itself to *operator* revocation and left the phone's own Forget purely local.
This is the other half.

## The problem

`AppContainer.forgetSlot` clears the slot config, the e2e session, the
cached clients and the shared credentials — all on the device. It makes no
network call. So after Forget:

- the bearer token still verifies at the relay, indefinitely (`auth.Store`
  has no expiry);
- the agent still holds the shared secret for that `device_id`.

Both halves of a credential the user believes they destroyed stay alive. The
same holds for re-pairing a slot: per-pairing key separation (cmux-app-1fx)
mints a fresh key each time, so a re-pair *inserts* rather than replaces, and
the previous pair of rows is stranded (cmux-app-bys).

Measured on 2026-08-11 with `cmux-bridge devices list`: 19 relay tokens and
3 direct tokens against 5 shared secrets.

## The awkward part, and why the obvious design is wrong

The obvious shape is one route that destroys both halves. It doesn't work,
for a reason worth writing down.

`encryptionMiddleware` (internal/server/encryption.go) encrypts the response
*after* the handler returns, in `rec.flush()`, keyed by the same shared
secret. A handler that deletes the shared secret therefore destroys its own
ability to answer: `EncryptBody` fails and the caller gets a plaintext 500
`encrypt_failed` for an operation that in fact succeeded. Every fix for that
is surgery on the middleware — a post-flush hook, or encrypting eagerly on
first Write — and the middleware is the one piece of this system that must
stay boring.

## Decision: split the two halves by who owns them

**The token dies synchronously, on request. The stranded secret is reaped by
the agent on its own schedule.**

That falls out of where the two halves actually live:

| | relay mode | direct mode |
|---|---|---|
| bearer token | relay's `auth.Store` | agent's `direct-auth.db` |
| shared secret | — | agent's `sessions.db` |

The token is the thing that grants access, and it is the thing whichever
server terminates the request can delete immediately. The shared secret
grants nothing on its own: once the token is gone, no request carrying it can
authenticate, so the secret is inert. Reaping it promptly is hygiene, not
security — which is exactly the sort of work that belongs on a timer rather
than on the critical path of a user action.

Two consequences make this more than a workaround:

1. **The self-revoke route carries no cmux content**, so it does not belong
   behind the e2e layer at all. The relay already terminates two
   device-authenticated, non-e2e routes this way (`POST /devices/register`,
   `POST /devices/test-push`); this is a third. No middleware change.
2. **The reconciler fixes operator revocation too.** Demonstrated live on
   2026-08-11: revoking through `cmux-relay devices revoke` leaves the
   agent's secret behind as a `local` row. Today only a human running
   `cmux-bridge devices revoke` clears it. With a reconciler that becomes
   self-healing, and *every* path that removes a token converges.

### The route

```
POST /devices/self-revoke      (device bearer auth; no e2e envelope)
```

The caller names nothing. The device is taken from `auth.DeviceFromContext`,
which is populated by `auth.Require` from the bearer token — so a device can
only ever revoke itself, and there is no identifier in the request for
anyone to tamper with. Idempotent: revoking an already-gone token is 200, not
404, because the caller's goal ("this credential must not work") is satisfied
either way and Forget must not fail on a retry.

Mounted on both servers, with the same handler:

- relay: `r.notAgent(auth.Require(r.store, ...))`, beside `/devices/register`
- direct: `auth.Require(s.store, ...)` — deliberately *not* the full `wrap`,
  so it stays outside `encryptionMiddleware` and matches relay mode

### The reconciler

On the agent, on a timer: list every configured server, and remove any local
shared secret no server has a row for.

The guard that makes it safe is the one `collectDevices` already applies — a
server that failed to answer means its devices are unknown, not absent, so
nothing is reaped that round. Without that a transient relay outage would
unpair every device on the Mac.

This is a new trust concession only in appearance: the relay already decides
which tokens verify, so it can already deny service to any device. It cannot
use this to read anything.

### The phone

`forgetSlot` calls the route best-effort, then clears locally exactly as it
does now. **The local clear must happen whether or not the call succeeds** —
a phone in a tunnel that could not forget a slot would be a worse bug than
the one being fixed. A failed self-revoke leaves the token alive until an
operator or a re-pair removes it, which is the status quo, so the fallback is
never worse than today.

## What this leaves for cmux-app-bys

Re-pairing an already-paired slot should self-revoke the outgoing credential
before pairing the new one. That is the same call from a second call site,
which is why bys is not designed here: it becomes small once this exists.

Note the design constraint it dodges. The alternative — having the phone name
the token hash it replaces in the *pairing* request — cannot work, because
pairing is pre-auth: an unauthenticated `replaces` field is a revocation
oracle for any device that can reach the pairing endpoint. Self-revoke is
authenticated by the very credential being destroyed, so it has no such hole.

## Non-goals

- Tearing down live sockets on revoke (cmux-app-dle). A revoked device is
  refused at the next request; an already-open WebSocket survives until it
  drops. Same gap operator revocation has, tracked separately.
- Reaping the existing 22 rows. The reconciler only removes *local secrets
  with no server row*; there are none today (the drift runs the other way).
  Retiring the 17 stale relay tokens stays a deliberate manual act.
