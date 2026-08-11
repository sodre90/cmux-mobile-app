# Operator device revocation (cmux-app-vkq)

Status: proposed
Issue: cmux-app-vkq (P1, bug) — related: cmux-app-05w

## Problem

A paired device cannot be un-paired. Not by the user, and not by the
operator.

`auth.Store.Verify` (`bridge/internal/auth/store.go`) applies no expiry — a
device row authenticates until it is deleted or its tenant is revoked. The
only deletion path is `auth.Store.Revoke(token)`, whose sole non-test caller
is `cmux-relay devices revoke <token>`
(`bridge/cmd/cmux-relay/commands.go`). That function takes the **raw bearer
token** and hashes it internally:

```go
DELETE FROM devices WHERE token_hash = ?   // hashToken(token)
```

But `cmux-relay devices list` prints only `d.HashSuffix` — the last six
characters of the SHA-256 hash — and the raw token exists nowhere except on
the phone. There is no lookup from anything the CLI displays back to the
value `Revoke` needs. The operator can enumerate devices and cannot remove
any of them. The only actual remedy today is hand-editing SQLite.

Measured on the live relay, 2026-08-11:

```
cmux-relay devices --config /etc/cmux-relay/config.toml list | wc -l   ->  19
... | grep -c 'fcm=true'                                               ->   7
```

Nineteen standing credentials for one tenant — in practice one phone plus an
emulator — accumulated since 2026-06-29. All nineteen authenticate right now.
Seven carry FCM registration tokens and are therefore live push targets.

Meanwhile the agent's `sessions.db` holds **five** e2e device rows. The two
stores have already drifted by fourteen: those fourteen tokens pass
authentication and get proxied to the agent, which then has no shared secret
for them. They are not usable sessions, but they are valid credentials, and
nothing reports the drift.

The lost-or-sold-phone case has no answer at all. "Forget" lives on the phone
you no longer have, and even that is local-only (see Follow-ups).

## Root cause

Revocation was designed as a token operation and the token is a secret held
by exactly one party — the one being revoked. Every other identifier in the
system is a *hash*: `auth.Device.TokenHash`, `HashSuffix`, the
`X-Device-ID` header, the e2e store's `device_id`. The admin surface reads
the hash world and writes the token world, and nothing bridges them.

The second half is topological. The credential has two halves in two stores
on two machines:

- the bearer token in `auth.Store` — the relay's `store.db`, or
  `direct-auth.db` for the direct listener;
- the shared secret in the agent's `e2e.Store` (`sessions.db` on the Mac).

`e2e.Store` has `AddDevice` and **no removal method at all**. The relay is
deliberately blind and cannot be told to reach into the agent's store. So no
single existing component can complete a revocation.

## Requirements

1. An operator with shell on the Mac can list paired devices and revoke one,
   using an identifier the listing actually shows.
2. Revoking clears **both** halves — the bearer token stops authenticating,
   and the agent no longer holds a shared secret for that device.
3. Works for both slots: relay-paired and direct-paired.
4. The relay stays blind. Revocation must not require it to learn anything it
   does not already hold.
5. Tenant isolation holds: an agent must not be able to revoke another
   tenant's device. `internal/relay/multitenant_test.go` is the enforcement
   point and must gain a case, not merely keep passing.
6. No Android change, and no app↔bridge wire change. This is an
   agent↔server admin surface.

## Options considered

### Option A — `cmux-bridge devices`, agent-side, driving both stores (chosen)

The agent is the only component that can reach both halves: it owns the e2e
store on local disk, and it already holds mTLS credentials for the relay's
`/agent/*` routes. Add two agent-facing routes (list, revoke) mounted
identically on the relay and the direct listener, and one CLI on the Mac that
calls them and then clears the local e2e row.

The identifier is the token hash, which every layer already speaks —
including `e2e.Store.device_id`, which **is** the token hash
(`sessions.AddDevice(tokenHash, …)` in `cmd/cmux-bridge/pair.go`). Nothing
new has to be plumbed to correlate a device between the two stores.

Costs a new HTTP surface. Gives a single command that cannot be left half
done.

### Option B — fix `cmux-relay devices revoke` to accept a hash suffix

Smaller: no new route, one changed argument. But it only ever clears the
auth half, on the relay host, and the operator must then run something else
on the Mac to clear the e2e half. Two machines, two commands, and a partial
revocation that looks complete. It also does nothing for direct-paired
devices, whose auth store is on the Mac, not the relay.

Rejected as the primary mechanism. The suffix-lookup part of it is worth
keeping as a convenience — see Follow-ups.

### Option C — phone-initiated self-revocation from "Forget"

Wire the app's Forget to an authenticated revoke call. Fixes the misleading
UI and stops accumulation, and the phone can authenticate with the very
credential being retired, which is tidy.

It is not the security control, though: it requires the phone. Deferred to
its own change (Follow-ups), sequenced after this one so it can reuse the
routes this adds.

## Chosen design

### Store (`internal/auth`)

Two tenant-scoped methods alongside the existing `List`/`Revoke`:

- `ListByTenant(tenantID string) []Device` — `List` restricted to one
  tenant. `List` stays as-is for the relay operator's cross-tenant view.
- `RevokeByHash(tenantID, tokenHash string) error` — `DELETE FROM devices
  WHERE token_hash = ? AND tenant_id = ?`, `ErrNotFound` when nothing
  matched. Tenant-scoped in the same statement, not by a separate read, so
  there is no window between check and delete.

`Revoke(token)` is untouched; `RevokeByHash` is not a rename of it. They have
different callers and different trust stories.

### Routes (`internal/devices`, new)

A `Mount` mirroring `internal/pairing`'s shape, so the relay and the direct
listener get byte-identical handlers:

```
GET  /agent/devices                       -> wire.AgentDeviceListResp
POST /agent/devices/{tokenHash}/revoke    -> 204, or 404 unknown_device
```

Tenant resolution is injected exactly as `pairing.Mount` does it:
`r.agentOnly` (verified mTLS CN) on the relay, `ConstantTenant` on the direct
listener. The handlers never see how the tenant was established.

The list response carries `name`, `token_hash`, `created_at`, `has_fcm` — all
metadata the relay already stores and already exposes to the operator via
`cmux-relay devices list`. No message content, no key material, so the blind
relay property is unchanged.

### CLI (`cmd/cmux-bridge/devices.go`, new)

```
cmux-bridge devices list
cmux-bridge devices revoke <token-hash-prefix>
```

`list` fetches from whichever servers are configured — the relay over mTLS
when `relay_url` is set, the direct listener over its Tailscale cert when
`direct_listen` is set — and annotates each row with whether the local e2e
store holds a secret for it. That annotation is the drift report: it is how
an operator sees the 19-vs-5 gap without reading SQLite.

`revoke` accepts any unambiguous prefix of a token hash, resolving it against
the same listing, and refuses an ambiguous one rather than guessing. It then:

1. calls the revoke route on whichever server holds that device;
2. calls `e2e.Store.RemoveDevice(deviceID)` on the local store.

Order matters: kill the credential first, then drop the secret. The reverse
leaves a token that authenticates and proxies to an agent that cannot decrypt
it — which is exactly the drift state this change exists to clean up.

Step 2 runs even if step 1 returned `unknown_device`, so a device that has
already lost its auth row can still have its orphaned secret reaped.

### E2E store (`internal/e2e`)

- `RemoveDevice(deviceID string) (bool, error)` — the missing counterpart to
  `AddDevice`. Reports whether a row was actually deleted.

## Security analysis

**Tenant isolation.** `RevokeByHash` is tenant-scoped in SQL, and the route
gets its tenant from the verified mTLS CN via the same `agentOnly` path that
already guards the pairing-code routes. A tenant presenting a valid cert can
enumerate and revoke only its own devices. Requirement 5 adds a
`multitenant_test.go` case asserting a cross-tenant revoke both returns 404
and leaves the row intact — 404 rather than 403, matching how the
pairing-code routes already refuse to confirm the existence of another
tenant's code.

**Direct listener has no per-request auth.** `MountDirectPairing`'s comment
records the standing decision: the access boundary for that listener is
Tailscale's network ACLs, not a per-request identity check, because it binds
only this Mac's Tailscale address. Mounting the device routes there inherits
that posture, so anyone on the tailnet can list and revoke direct-paired
devices. That is a denial-of-service capability, not a disclosure one — the
list carries no secrets, and revocation is the safe direction to fail. It is
also strictly weaker than what the tailnet already grants: the same caller
can mint pairing codes and abort pairings today. Called out here so the
inheritance is deliberate rather than accidental.

**Hash as an identifier.** The token hash is not secret — it is already in
logs (`device=851ade`), in `X-Device-ID`, and in the operator listing.
Accepting it as a revoke argument grants no capability that possessing it did
not already imply, because the route is tenant-gated independently.

**Revocation is not retroactive.** An in-flight WebSocket authenticated
before the revoke is not torn down by deleting the row. That is the same gap
`cmux-app-2zn`/`cmux-app-smu` covered for Forget, and it is out of scope
here; noted in Follow-ups.

## Test plan

`internal/auth` — `RevokeByHash` deletes the right row; returns `ErrNotFound`
for an unknown hash; **refuses a hash belonging to another tenant and leaves
it intact**; `ListByTenant` returns only the caller's devices.

`internal/devices` — route tests against both mount configurations
(injected tenant vs constant tenant): list shape, revoke 204, unknown 404.

`internal/relay/multitenant_test.go` — a cross-tenant revoke attempt, per
requirement 5.

`internal/e2e` — `RemoveDevice` deletes and reports; is a no-op returning
false for an unknown id; does not disturb other rows' counters.

`cmd/cmux-bridge` — prefix resolution: exact match, unique prefix, ambiguous
prefix refused, no match refused. Revoke calls both halves in order, and
still reaps the e2e row when the server reports `unknown_device`.

Mutation check, as on the last two changes: drop the `AND tenant_id = ?` from
`RevokeByHash` and confirm the multitenant test fails. A tenant-scoping test
that passes against an unscoped query is not a test.

## Deployment ordering

Relay first, then agent. The routes must exist before a CLI calls them; an
agent binary that never calls them is harmless on an old relay. This is the
same ordering the last two changes used, and the rollback tag convention
(`localhost/cmux-relay:rollback-<date>`) applies.

No app change, so no phone step and no wire-lockstep obligation.

## Follow-ups (not in this change)

- **Phone-side Forget → best-effort self-revoke** (Option C). Reuses the
  revoke route this adds. Needs a decision on whether a device may revoke
  itself with the token that might be stolen.
- **Retire the previous device row on re-pair.** Per-pairing key separation
  (`cmux-app-1fx`) means every re-pair inserts a new row and orphans the old
  one; that is the mechanism behind the 19. Bounding growth is cheaper than
  revoking after the fact and should probably land first of the follow-ups.
- **Reap the existing 19.** This change gives the operator the tool; actually
  running it is a separate, deliberate act.
- **`cmux-relay devices revoke` by hash suffix** (Option B's useful half), so
  the relay operator's own tool stops being unusable.
- **Tear down live sockets on revoke**, extending `cmux-app-2zn`/`smu`.
- **`cmux-relay devices list` silently opens an empty store** when run in the
  container without `--config`, printing "no paired devices". Reassuring and
  wrong; on the path anyone investigating this would take.
