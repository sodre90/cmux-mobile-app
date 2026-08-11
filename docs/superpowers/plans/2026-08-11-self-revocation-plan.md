# Phone-side self-revocation — implementation plan (cmux-app-f5y)

Design: `docs/superpowers/specs/2026-08-11-self-revocation-design.md`
Issue: cmux-app-f5y (P1)

One logical item per commit. 1 and 2 are independent; 3 needs 1.

## Commit 1 — the self-revoke route, both servers

`bridge/internal/devices/devices.go`, `bridge/internal/relay/relay.go`,
`bridge/internal/server/direct.go`

Add to `internal/devices` a handler that revokes *the caller*:

```go
func MountSelfRevoke(mux *http.ServeMux, store *auth.Store, wrap func(http.Handler) http.Handler)
mux.Handle("POST /devices/self-revoke", wrap(http.HandlerFunc(h.selfRevoke)))
```

`wrap` rather than a `TenantResolver`: the two servers authenticate the
device differently (relay `notAgent(auth.Require(...))`, direct
`auth.Require(...)`), but both leave the device in the request context, which
is all the handler reads.

Handler: `auth.DeviceFromContext` → no device is 401 `unauthorized` (defence
in depth; `auth.Require` should already have refused). Otherwise
`store.RevokeByHash(dev.TenantID, dev.TokenHash)`, treating `ErrNotFound` as
success — idempotent, per the design. 200 `{}`.

The handler must never read a device identifier from the request. A test
asserts a body naming another device's hash changes nothing.

Mounting:
- relay, beside `/devices/register`: `devices.MountSelfRevoke(mux, r.store, func(h http.Handler) http.Handler { return r.notAgent(auth.Require(r.store, h)) })`
- direct, in `DirectHandler`: `devices.MountSelfRevoke(mux, s.store, func(h http.Handler) http.Handler { return auth.Require(s.store, h) })`

Direct mode deliberately does **not** use that file's existing `wrap`, which
adds `encryptionMiddleware`. Say why in a comment or the next reader will
"fix" it — the reason is Commit 3's: Forget clears the shared secret without
waiting for this call, so an envelope would lose a race it can never win.

Tests:
- `internal/devices/devices_test.go` — revokes the caller; a second call is
  still 200; a body naming another device is ignored and that device survives.
- `internal/relay/multitenant_test.go` — tenant B's device self-revoking
  leaves tenant A's device verifying.
- `internal/server/direct_test.go` — the route is reachable without an e2e
  envelope, which is the whole point of mounting it outside the middleware.

## Commit 2 — the agent's orphan reaper

`bridge/cmd/cmux-bridge/devices.go`, `bridge/cmd/cmux-bridge/agent.go`

`collectDevices` already computes exactly what this needs: rows with
`server.kind == localSource` are local secrets no server knows about, and it
already refuses to produce them when any server failed to answer.

```go
func reapStrandedSecrets(servers []agentServer, sessions *e2e.Store) (reaped int, err error)
```

Refuse the whole round on `problems` — a server that did not answer means its
devices are unknown, not absent. Log what was removed at INFO (device id
only, never key material).

Wire into `runAgent` on a ticker; first run shortly after startup, not at
`t=0` (the tunnel may not be up yet). One hour is fine — nothing here is
urgent, and a shorter period only multiplies the requests.

Tests (`devices_test.go`): reaps a stranded secret; leaves a secret a server
still lists; **reaps nothing at all when any server errors**, which is the
guard that stops a relay outage unpairing the Mac.

## Commit 3 — the phone calls it on Forget

`android/.../data/AppContainer.kt`, the bridge API client, plus tests

Add the call to the existing authenticated client (same bearer/base-URL path
as `/devices/test-push`; no e2e envelope, so it must not go through the
encrypting body path).

`E2eInterceptor` needs a second exemption list beside
`RELAY_TERMINATED_PATHS`, ungated by `isRelaySlot`: this path is plaintext on
both slots. Gating it on the relay slot would leave DIRECT encrypting a body
whose secret Forget has already cleared — the failure the Go mount above
exists to avoid.

`forgetSlot` fires it best-effort and clears locally **regardless of the
outcome**. Do not make `forgetSlot` suspend and do not let a failure
propagate: a phone that cannot reach the relay must still be able to forget.

Tests: Forget still clears everything when the call fails/times out; Forget
issues the call when it can.

No new DTOs — empty request, empty response — so the wire-lockstep invariant
has nothing to mirror. Say so in the commit message so the omission reads as
deliberate.

## Verification

```
cd bridge && go build ./... && go vet ./... && go test ./...
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt
```

Deploy relay first, then agent, tagging the rollback image first.

On hardware:

1. Pair a throwaway through the relay; confirm its token authenticates.
2. Call self-revoke with that token; confirm 200, then 401 on the next
   request, and that `cmux-bridge devices list` shows the secret as `local`.
3. Call it again with the same (dead) token — expect 401 from `auth.Require`,
   which is the correct outer refusal, not a handler concern.
4. Wait for (or trigger) a reaper round; confirm the `local` row disappears
   and no other secret does.
5. Repeat 1–2 against the direct listener.
6. On the phone: Forget a slot, confirm the row leaves the server listing.
   Then Forget with the network off and confirm the slot still clears.

## Follow-ups

- cmux-app-bys — call self-revoke from the re-pair path too.
- cmux-app-dle — socket teardown, still open and unaffected by this.
