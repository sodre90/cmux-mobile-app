# Self-service device pairing & end-to-end content encryption — Go side (sub-project of the multi-tenant relay design)

## Context

The multi-tenant relay migration
([`2026-07-01-multi-tenant-relay-design.md`](2026-07-01-multi-tenant-relay-design.md))
implemented Layer 1 (per-tenant transport isolation) and designed, but
deliberately deferred, Layer 2 (end-to-end content encryption) and
self-service phone pairing (that spec's non-goal #1: "phone-pairing UX
details, headless-agent QR display"). Today, pairing a device is fully
operator-driven: `openssl` by hand to make a CSR/`.p12`, `cmux-relay pair`
to mint a bearer token, and manual out-of-band transfer to the phone
(documented in `android/README.md` and `bridge/README.md`).

This spec implements both together, on the **Go side only** (relay +
Mac agent). The Android/Kotlin side — camera QR scanner, Kotlin
crypto package, wiring into the app's HTTP/WS client — is an explicitly
separate follow-up spec, not covered here, so this spec's Go implementation
has no consumer to test end-to-end against; verification is unit/integration
tests plus manual exercise of the new CLI flow against a real device
sending raw HTTP (see Testing).

## Decisions made (confirmed with user)

- **Scope:** both self-service pairing and E2E content encryption together,
  not pairing alone — per the parent spec's already-designed Layer 2
  architecture (X25519 ECDH, HKDF, XChaCha20-Poly1305, per-device-pair
  independent secrets, QR-based proximity trust).
- **Decomposition:** two separate specs/plans — this one (Go side: relay +
  Mac agent) now, Android/Kotlin (camera scanner, Kotlin crypto, app wiring)
  as its own later cycle.
- **QR display:** a new interactive CLI subcommand on the Mac agent renders
  the pairing QR as ASCII art directly in the terminal (à la
  `tailscale`/`wg`), not a browser-based flow.

## Architecture & data flow

New pieces on the Go side:

- **Relay**: two new endpoints — `POST /agent/pairing-code` (agent-CN-
  authenticated; wraps the already-existing but currently-unwired
  `Store.NewPairingCode`) and `POST /devices/pair` (unauthenticated — the
  phone has no token yet; wraps the already-existing `Store.RedeemPairingCode`,
  plus stores the phone's submitted X25519 public key). The proxy
  (`proxy.go`) gains one line: forward the authenticated device's token hash
  as `X-Device-ID`, so the agent can select the right shared secret.
- **Mac agent**: a persistent X25519 identity keypair
  (`~/.config/cmux-bridge/identity.key`), a new local store of paired
  devices' public keys and derived shared secrets, and a new interactive CLI
  subcommand (`cmux-bridge pair-device`) that requests a pairing code from
  the relay, renders it as an ASCII QR, then polls until the phone redeems
  it and submits its public key — at which point the agent derives the
  shared secret via ECDH and persists it.
- **New `internal/e2e` Go package**: X25519/HKDF/XChaCha20-Poly1305 wrapper
  plus the envelope format, used both by the pairing flow (to derive the
  shared secret) and by request middleware (to encrypt/decrypt bodies and
  terminal frames).

Data flow for pairing: agent requests a code → shows QR (tenant ID, code,
agent's public key) → phone scans, generates its own keypair, POSTs
`{code, name, device_pubkey}` to `/devices/pair` → relay redeems the code,
mints a bearer token, stores the device row (now including its public key)
→ agent's polling command sees the redemption, computes the shared secret,
persists it, done. Both sides now hold `ECDH(agentPriv, devicePub) ==
ECDH(devicePriv, agentPub)`, independently derived — never transmitted.

## Data model & new relay endpoints

`bridge/internal/auth/store.go` changes:

- `pairing_codes` table gains a `device_pubkey TEXT` column (nullable — set
  only once the phone redeems it). `RedeemPairingCode` changes to accept and
  store the phone's public key alongside minting the token.
- `devices` table gains a `device_pubkey TEXT NOT NULL` column (the phone's
  long-term X25519 public key — safe for the relay to hold per the parent
  spec's "public keys only" rule).
- New helper the agent's pairing command needs: `Store.PairingCodeStatus(tenantID,
  code) (pubkey string, redeemed bool)` so it can poll "has this been
  claimed yet."

`bridge/internal/relay/relay.go` new routes:

- `POST /agent/pairing-code` — gated by a new `agentOnly` middleware
  (mirrors the existing `notAgent`, inverted: requires a valid, active agent
  CN, resolves `tenantID` from it). Body: `{}` (TTL is fixed server-side,
  e.g. 10 minutes). Calls `Store.NewPairingCode(tenantID, ttl)`, returns
  `{code}`.
- `GET /agent/pairing-code/{code}` — same `agentOnly` gating, scoped so an
  agent can only poll its own tenant's codes. Returns `{redeemed: bool,
  device_pubkey: string}` (empty until claimed).
- `POST /devices/pair` — unauthenticated by bearer token (mirrors today's
  `/tenants/register` bootstrap pattern: reachable without prior
  credentials, by design). Body: `{code, name, device_pubkey}`. Calls
  `Store.RedeemPairingCode(code, name, devicePubkey)`, returns `{token,
  tenant_id, agent_pubkey}` — the agent's public key is included here too so
  the phone can compute its side of the ECDH immediately, without a second
  round trip.

`bridge/internal/relay/proxy.go`: `Director` gains
`req.Header.Set("X-Device-ID", dev.TokenHash)`. This requires threading the
full token hash through `auth.DeviceFromContext` — today `Device` only
exposes `HashSuffix` (the last 6 hex chars, for display). A new `TokenHash`
field carries the full SHA-256 hex, used only for this internal routing
purpose, never displayed or logged.

`POST /devices/register` (existing, FCM-token registration of an
already-paired device) is unchanged — it stays a distinct endpoint from the
new `POST /devices/pair`, avoiding overloading its semantics.

## Crypto primitives & wire format

New package `bridge/internal/e2e`:

- **Identity keys**: X25519 keypairs, generated once and persisted as raw
  32-byte files (`0o600`) — the agent's at
  `~/.config/cmux-bridge/identity.key`. (The phone's equivalent is out of
  scope for this Go-side spec.)
- **Shared secret derivation**: `sharedSecret = HKDF-SHA256(ECDH(myPriv,
  theirPub), salt=nil, info="cmux-bridge e2e v1|"+sortedPubKeyA+"|"+sortedPubKeyB)`
  — the two public keys are sorted lexicographically before concatenation
  into `info` so both sides compute an identical string regardless of which
  side is "me." Output: a 32-byte key, held in memory (agent side).
- **HTTP body envelope** (request and response bodies alike): JSON
  `{"v":1,"n":<uint64>,"ct":"<base64>"}` where `ct` is
  `XChaCha20-Poly1305(key, nonce, plaintext)` with the 24-byte nonce built
  as `[16 zero bytes][8-byte big-endian n]`. `n` is the sender's own
  monotonic counter for that direction — starts at 0, increments per
  message, never reused. The receiver rejects `n <= last_seen_n` for that
  direction (replay + reorder protection; cmux's request/response model is
  strictly sequential per connection, so strict monotonic increase is safe
  with no reordering tolerance needed).
- **Terminal WS frame envelope**: binary WS message `[8-byte big-endian
  n][ciphertext+tag]` — same nonce construction, same counter rule, one
  counter per direction per WS connection (reset to 0 on reconnect, since
  it's scoped to that connection's lifetime, not global).
- Two independent counters per device pair — `agent→device` and
  `device→agent` — each party tracks its own send counter and the peer's
  last-received counter (4 numbers total per active session), held in
  memory only. Not persisted: a restart drops in-flight counters, which
  just means the next message after reconnect starts a fresh WS connection
  with both counters at 0 — safe since it's scoped per connection.
- **What crosses the relay unencrypted**: HTTP method/path/headers (needed
  for routing) and the WS upgrade — only bodies and terminal frame payloads
  are ciphertext. This matches the parent spec's "blind relay" framing
  exactly.

## Error handling

- **Pairing code expiry/reuse**: `RedeemPairingCode` today returns a single
  `ok bool` — `false` for not-found, expired, and already-redeemed alike (it
  doesn't distinguish the reason). The new `/devices/pair` handler maps any
  `ok=false` to `410 {"error":"pairing_code_invalid"}`, matching that
  existing granularity rather than inventing a finer-grained error the store
  doesn't actually produce.
- **Agent offline during pairing**: irrelevant to the pairing flow itself
  (the relay mints the code and stores the redemption independent of tunnel
  state) — but the agent's polling command (`cmux-bridge pair-device`)
  simply can't see the redemption until it reconnects; the CLI keeps
  polling with a "waiting for agent..." message, no special relay-side
  handling needed.
- **Decryption failure** (wrong/missing shared secret, corrupted
  ciphertext, replayed/stale counter): the receiving side returns `400
  {"error":"decrypt_failed"}` and does **not** forward the request to the
  underlying handler — a failed AEAD open must short-circuit before
  touching cmux at all.
- **Missing `X-Device-ID` header** (shouldn't happen given the proxy always
  sets it, but defensively): agent-side middleware treats it as `401
  {"error":"unknown_device"}` rather than a panic/nil-deref.
- **Unknown device public key** (agent has no shared secret on file for
  that device — e.g. its local state was wiped): `409 {"error":"not_paired"}`,
  prompting the phone app to guide the user back through pairing.
- **CSR/key-format errors**: out of scope here — no new key formats are
  introduced beyond what `ca.go` already handles.

## Testing

- `internal/e2e` package: pure unit tests, no network — round-trip
  encrypt/decrypt, wrong-key rejection, replayed-counter rejection,
  out-of-order-counter rejection, and **fixed test vectors** (hardcoded
  public keys, shared secret, plaintext, and expected ciphertext) that the
  future Kotlin implementation must reproduce byte-for-byte — this is the
  cross-language interop contract, so these vectors are committed as a
  small Go fixture now even though only the Go side consumes them this
  cycle.
- `internal/auth/store.go`: extend existing pairing-code tests to cover the
  new `device_pubkey` column round-trip (stored on redeem, retrievable by
  the polling endpoint).
- `internal/relay`: HTTP-level tests for the three new/changed endpoints
  (`POST /agent/pairing-code`, `GET /agent/pairing-code/{code}`, `POST
  /devices/pair`) covering auth gating (agent-CN-only vs. unauthenticated),
  success, and the error cases above. Existing `notAgent`/`auth.Require`
  test patterns extend directly.
- `internal/relay/proxy.go`: a focused test asserting `X-Device-ID` is set
  to the correct token hash on forwarded requests (extending whatever test
  currently covers `X-Relay-Token` injection).
- Agent-side (`cmd/cmux-bridge`, new pairing command + new decrypt/encrypt
  middleware): tests using the existing fake-cmux-binary pattern, plus a
  fake relay endpoint for the pairing command's polling loop — verifying it
  stops polling and persists the shared secret on redemption, and
  backs off/retries sanely if the relay is briefly unreachable.
- No end-to-end test against real hardware/relay is planned (matches how
  Layer 1 was tested) — manual verification against the live home-server
  relay, using raw `curl`/`openssl` to simulate the phone side (since no
  Android client exists yet), happens after merge, same pattern as the
  multi-tenant cutover.

## Explicit non-goals (this spec)

- The Android/Kotlin side (camera QR scanner, Kotlin `e2e` package, wiring
  encryption into the app's HTTP/WS client) — a separate, later spec.
- Forward secrecy / key ratcheting beyond the initial per-pairing shared
  secret — noted in the parent spec as a possible future hardening, not
  required here either.
- Rate limiting / abuse resistance on the new unauthenticated
  `POST /devices/pair` endpoint — same deferral as the parent spec's
  sub-project 3 (abuse & resource controls).
- Revoking/rotating a device's individual shared secret independent of
  revoking its bearer token — today's `Store.Revoke` (token-level) remains
  the only revocation mechanism; killing the token is sufficient since a
  revoked device can no longer reach the proxy at all.
