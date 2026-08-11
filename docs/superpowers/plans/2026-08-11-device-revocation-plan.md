# Operator device revocation — implementation plan (cmux-app-vkq)

Design: `docs/superpowers/specs/2026-08-11-device-revocation-design.md`
Issue: cmux-app-vkq (P1)

One logical item per commit, per CLAUDE.md. **Order is load-bearing**: 1 and
2 both precede 3, which is the only commit that calls anything the other two
add. 1 and 2 are independent of each other.

Go-only throughout (agent↔server admin surface), so the wire-lockstep
invariant does not apply — there is no Kotlin mirror to keep in step. Say so
in commit 1's message so the omission reads as deliberate.

## Commit 1 — server: tenant-scoped device admin routes

`bridge/internal/auth/store.go`, `bridge/internal/wire/devices.go` (new),
`bridge/internal/devices/devices.go` (new), `bridge/internal/relay/relay.go`,
`bridge/internal/server/direct_pairing.go`

Store API, next to the existing `List`/`Revoke`:

```go
func (s *Store) ListByTenant(tenantID string) []Device
func (s *Store) RevokeByHash(tenantID, tokenHash string) error
```

`RevokeByHash` scopes in the statement itself — one write, no read-then-write
window:

```sql
DELETE FROM devices WHERE token_hash = ? AND tenant_id = ?
```

`ErrNotFound` when `RowsAffected() == 0`. Leave `Revoke(token)` alone: it has
a different caller and a different trust story, and collapsing them would
make the raw-token path look like the hash path.

Wire DTOs (`internal/wire/devices.go` — new file, not appended to
`pairing.go`, since these are not pairing):

```go
type AgentDevice struct {
    Name      string `json:"name"`
    TokenHash string `json:"token_hash"`
    CreatedAt string `json:"created_at"`
    HasFCM    bool   `json:"has_fcm"`
}

type AgentDeviceListResp struct {
    Devices []AgentDevice `json:"devices"`
}
```

`HasFCM` is a bool, not the token — the agent has no use for the FCM
registration value and it should not travel.

Routes (`internal/devices`), mirroring `internal/pairing`'s `Mount` shape so
both servers get identical handlers:

```go
func Mount(mux *http.ServeMux, store *auth.Store, resolveTenant TenantResolver)

mux.Handle("GET /agent/devices", ...)
mux.Handle("POST /agent/devices/{tokenHash}/revoke", ...)
```

Reuse `pairing.TenantResolver`/`ConstantTenant` rather than declaring a
second copy — import them, or lift the type to a shared spot if that reads
better once written. Do not duplicate the resolution logic.

Handler behaviour: unresolved tenant → 403 (the caller presented no verified
agent identity); resolved tenant but no matching device → 404
`unknown_device`; success → 204 with no body.

Mounting:

- relay (`relay.go`, beside the existing `pairing.Mount` call):
  `devices.Mount(mux, r.store, r.agentOnly)`
- direct (`direct_pairing.go`):
  `devices.Mount(mux, store, pairing.ConstantTenant(tenantID))`

Extend `MountDirectPairing`'s doc comment — it currently says "the four
pre-auth pairing routes", which stops being true.

Tests:

- `internal/auth/store_test.go` — `RevokeByHash` deletes the right row;
  `ErrNotFound` on unknown hash; **cross-tenant hash is refused and the row
  survives**; `ListByTenant` returns only the caller's devices.
- `internal/devices/devices_test.go` — both mount configurations; list shape;
  revoke 204; unknown 404; unresolved tenant 403.
- `internal/relay/multitenant_test.go` — tenant B, holding a valid agent
  cert, cannot revoke tenant A's device: 404, and A's device still verifies
  afterwards.

**Mutation check before moving on**: delete `AND tenant_id = ?` from
`RevokeByHash` and confirm the multitenant test fails. If it still passes,
the test is asserting nothing and needs rewriting first.

## Commit 2 — e2e store: the missing RemoveDevice

`bridge/internal/e2e/store.go`

```go
func (s *Store) RemoveDevice(deviceID string) (removed bool, err error)
```

`DELETE FROM devices WHERE device_id = ?` under the existing `s.mu`, matching
`AddDevice`'s locking. Returns whether a row went away, so the caller can
distinguish "reaped an orphan" from "nothing there".

Tests (`internal/e2e/store_test.go`): deletes and reports true; unknown id
returns false with no error; **removing one device leaves another device's
`send_counter`/`recv_highest` untouched** — the counters are the durability
guarantee this store exists to protect, so prove the delete is surgical.

Small and unused until commit 3. That is fine and matches commit 1; do not
merge it into the CLI commit just to avoid an unused method.

## Commit 3 — agent CLI: `cmux-bridge devices`

`bridge/cmd/cmux-bridge/devices.go` (new),
`bridge/cmd/cmux-bridge/main.go`

```
cmux-bridge devices list
cmux-bridge devices revoke <token-hash-prefix>
```

Register `case "devices":` in `main`'s switch and add it to `usage()`.

Client construction is the awkward part and already has a worked example:
`runPairDevice` builds either an mTLS client for the relay or a plain client
for the direct listener, including the `tailscaleSelfStatus` lookup for the
Mac's DNS name. Factor that selection out of `runPairDevice` rather than
copy-pasting it — a second divergent copy of the relay-vs-direct client setup
is exactly the kind of drift that bites later.

`list` queries every configured server (`relay_url` set → relay;
`direct_listen` set → direct), tags each row with which one it came from, and
annotates whether the local `e2e.Store` holds a secret for that
`device_id` — remembering that `device_id` **is** the token hash, so the join
is direct. That annotation is the drift report:

```
SOURCE  DEVICE   NAME      CREATED               SECRET
relay   0ab04e   phone-1   2026-06-29T19:20:42Z  yes
relay   91a56c   samsung   2026-06-30T13:37:00Z  no
direct  851ade   phone     2026-08-11T11:24:03Z  yes
```

`revoke <prefix>` resolves the prefix against that same listing: exact match
wins; a unique prefix resolves; an ambiguous prefix is an error listing the
candidates; no match is an error. Never guess. Then, in order:

1. `POST /agent/devices/{tokenHash}/revoke` on the server that owns it;
2. `e2e.Store.RemoveDevice(deviceID)` locally.

Kill the credential first, drop the secret second. The reverse order leaves a
token that authenticates into an agent that cannot decrypt for it — the exact
drift state this change exists to clean up.

Run step 2 even when step 1 returns 404 `unknown_device`, so an already-
orphaned secret can still be reaped, and report the two outcomes separately:

```
revoked 91a56c: relay token removed, no local secret held
revoked 851ade: relay token removed, local secret removed
```

Tests (`cmd/cmux-bridge/devices_test.go`), against a `fakeRelay` in the shape
`pair_test.go` already established: prefix resolution (exact / unique /
ambiguous / none); revoke calls both halves in order; e2e row still reaped on
a 404; a server error does **not** reap the local secret (a failed revocation
must not silently half-complete).

## Verification

Gates, per CLAUDE.md — the Android leg is a no-op here but run it anyway,
since `detekt` has caught unrelated breakage before:

```
cd bridge && go build ./... && go vet ./... && go test ./...
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt
```

Deploy relay first, then agent (design § Deployment ordering). Tag the
rollback image before building, as on 2026-08-11:

```
podman tag localhost/cmux-relay:latest localhost/cmux-relay:rollback-<date>
```

On hardware, against the live relay:

1. `cmux-bridge devices list` — expect 19 relay rows, 5 with `SECRET yes`.
   That is the drift this change makes visible; it is the first time the two
   stores have been shown side by side.
2. Pair a throwaway device (the scripted-phone approach from the cmux-app-gmo
   verification), confirm it appears with `SECRET yes`, and confirm its token
   authenticates.
3. `cmux-bridge devices revoke <prefix>` on it. Confirm: the row leaves the
   listing, the token now 401s, and the agent's `sessions.db` count drops by
   exactly one.
4. Ambiguous-prefix and unknown-prefix paths both refuse cleanly.
5. Repeat 2–3 against the direct listener to cover the `ConstantTenant`
   mount.

Do **not** reap the existing 19 as part of this work. The tool is the
deliverable; using it is a separate, deliberate act (design § Follow-ups) and
some of those rows may correspond to devices worth identifying first.

## Follow-ups

Carried from the design, to be filed as issues rather than absorbed here:
phone-side Forget → self-revoke; retire the previous row on re-pair;
`cmux-relay devices revoke` by suffix; socket teardown on revoke; the
silent empty-store `--config` trap.
