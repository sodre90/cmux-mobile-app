# Multi-tenant relay: tenant isolation & E2E encryption (sub-project 1 of 4)

## Context

cmux-remote today is single-tenant: one relay serves exactly one Mac agent and
that person's phones. The user wants to run a **single, publicly-registrable
relay** so other people don't have to stand up their own home-server relay.
The intended audience is **open sign-up, strangers** — not a handful of known
friends — which rules out "trust the operator" as an acceptable security
model. This is the hardest version of the problem and sets the bar for
everything below.

This spec covers **only** the tenant-isolation and encryption architecture.
Three related sub-projects are explicitly out of scope here and will get
their own specs later:

1. Self-service onboarding (account creation, Sybil resistance / abuse
   prevention on registration, phone-pairing UX details, headless-agent QR
   display).
2. Abuse & resource controls (rate limiting, per-tenant quotas, DoS
   protection) — necessary given open sign-up, but independent of the
   isolation architecture itself.
3. Operational concerns — audit logging policy, data retention/deletion,
   incident response, ToS/legal. Mostly non-code.

## Current architecture (confirmed from code)

- `bridge/internal/relay/registry.go`: `Registry` holds **one** active
  `*yamux.Session` ("v1: one Mac"). A second agent dialing in with the same
  client-cert CN replaces and closes the first.
- `bridge/internal/relay/relay.go`: `agentCN` is a single global constant CN
  string. Any client cert bearing that CN may open the tunnel. There is no
  concept of tenant identity.
- `bridge/internal/auth/store.go`: `Device` records (bearer tokens) have no
  owner/tenant field — one flat map, one JSON file, tokens stored in
  plaintext.
- `bridge/internal/relay/proxy.go`: the reverse proxy always dials
  `reg.Current()` — "whichever session is active" — and forwards a real HTTP
  request (method/path/headers/body fully visible to the relay process) with
  an injected `X-Relay-Token`.
- Pairing is a manual CLI (`cmux-relay pair`) run by the operator on the
  relay host; device certs (`.p12`) are generated locally and copied to the
  phone out-of-band (AirDrop/USB) — so today, private key material never
  crosses the network at all.

The last point matters: today's design gets its "relay can't see keys"
property for free, by construction, because everything happens on one
person's LAN. Splitting the Mac agent and the phone across the public
internet, for a stranger, removes that free property — this spec's job is to
re-establish it deliberately.

## Decisions made (confirmed with user)

- **Audience:** open sign-up, strangers. Strongest threat model applies.
- **Relay trust:** the relay must be a **blind relay** — end-to-end encrypted
  between phone and Mac agent, such that a fully compromised relay host (or a
  malicious operator) cannot read tenant content.
- **Key bootstrap:** **QR code, scanned in person.** The Mac agent displays a
  QR code (its public key + a one-time pairing token); the phone scans it
  with its camera. This is proximity-based, so a compromised relay cannot
  MITM the key exchange (same trust model as Signal/WhatsApp Web linked
  devices).
- **Blindness depth:** **encrypt payload, metadata stays visible.** The relay
  keeps seeing HTTP method/path/timing/size (routing/ops visibility, same
  reverse-proxy plumbing as today) but request/response **bodies** and
  terminal frames are AEAD-encrypted end-to-end. Chosen over a fully opaque
  byte-pipe design (which would additionally hide metadata like which
  endpoint was hit) because it's a much smaller, lower-risk rewrite that
  already defeats the headline risk — a relay operator or attacker reading
  someone's source code, secrets, or agent conversation — and traffic-shape
  analysis is a lesser, deferrable threat.

## Architecture

Two independent trust layers:

### Layer 1 — Transport/routing (relay is allowed to see this)

- nginx mTLS edge is unchanged in spirit, but the relay becomes its own CA.
- Each Mac agent gets a **unique per-tenant client cert** minted at
  registration (CN embeds a random, unguessable tenant ID, e.g.
  `CN=agent:9f3a2c...`), replacing the single shared `agentCN` constant. The
  agent generates its own keypair and submits a CSR — its private key never
  crosses the wire.
- Each phone's bearer-token record gains a `TenantID` field.
- `Registry` becomes `map[tenantID]*yamux.Session` (with its own mutex),
  replacing the single `sess` field. A new tenant's agent tunnel can never
  evict another tenant's.
- The proxy resolves the destination session strictly from the
  **authenticated device's** `TenantID` (looked up via its bearer token) —
  never from "whatever's current." A device can structurally only ever reach
  its own tenant's agent.
- Tunnel handler validates the presented CN against an active/non-revoked
  entry in the cert table, not a hardcoded string.

### Layer 2 — Content (relay is blind to this)

- Mac agent generates a long-term X25519 identity keypair on first run,
  persisted locally (e.g. `~/.config/cmux-bridge/identity.key`, `0600`).
- Pairing a device: the agent creates a one-time pairing token via the
  relay, and renders a QR code containing: relay base URL, tenant ID, the
  one-time token, the agent's X25519 public key, and a fingerprint. The
  phone scans it, generates its own X25519 keypair, and both sides derive a
  shared key via ECDH + HKDF. The relay only ever stores/forwards **public**
  keys — never private keys or the derived secret.
- Every request/response body and terminal-stream frame carrying actual
  content (cell-grid data, keystrokes, feed replies, workspace names) is
  AEAD-encrypted (XChaCha20-Poly1305) with the per-device-pair shared key
  before it leaves a device, decrypted only by the other end. The relay
  keeps proxying HTTP exactly as today, just forwarding ciphertext blobs
  instead of plaintext JSON.
- Each paired device has its **own independent** shared secret with the
  agent (not one tenant-wide key) — so revoking one device never requires
  re-keying the others.

## Components & data model changes

- **`auth/store.go`**
  - `Device` gains `TenantID string`.
  - New `Tenant` record: `{ID, CreatedAt, Status}`.
  - New `AgentCert` record: `{Serial, TenantID, IssuedAt, RevokedAt}`.
  - Move off the flat JSON file to **SQLite** — still embedded, no new ops
    burden, but indexed and safe under concurrent writes across many
    tenants.
  - **Hash bearer tokens at rest** (stored in plaintext today). A DB leak
    then yields no directly usable credentials, same reasoning as password
    hashing.
- **`relay/registry.go`** — `map[tenantID]*yamux.Session`; `Get(tenantID)`
  replaces `Current()`; `Set`/`Clear` take a tenant ID.
- **`relay/relay.go`** — relay acts as its own CA; new tenant registration
  endpoint mints a cert from a CSR; `clientCN` check validates against the
  active-cert table.
- **New `internal/e2e` package (Go) + Kotlin equivalent** — X25519 keypair
  handling, QR payload encode/decode, ECDH+HKDF derivation, AEAD framing.
  Both sides must produce byte-identical results from the same inputs; this
  needs its own small interop spec (nonce handling, framing layout, key
  rotation policy) so the two codebases can't silently drift.
- **Android app** — camera-based QR scanner (new dependency), local keypair
  storage, an encrypt/decrypt wrapper in front of the existing HTTP/WS
  client.
- **Mac agent local state** — alongside its own long-term identity keypair,
  the agent must persist each paired device's public key and derived shared
  secret (keyed by device/bearer-token identity) so pairing survives agent
  restarts without re-scanning a QR code. Lost on `.p12`-equivalent deletion
  only.

## Data flow

**New tenant (Mac agent) registration**
1. Agent has no identity → generates a keypair, submits a CSR to the relay's
   tenant-registration endpoint.
2. Relay mints a per-tenant cert + tenant ID, returns it. (Registration-abuse
   resistance — CAPTCHA/email verification/rate limits — is explicitly
   deferred to the onboarding sub-project.)
3. Agent generates its long-term X25519 identity keypair, persists it
   locally.
4. Agent opens its mTLS tunnel using the new cert → routes into
   `registry[tenantID]`.

**Pairing a device**
1. Agent requests a one-time pairing token from the relay and renders a QR
   (tenant ID + token + its X25519 pubkey). *How* a possibly-headless agent
   displays this (small GUI window vs. CLI-rendered ASCII QR, à la
   `tailscale`/`wg`) is left to the implementation plan, not fixed here.
2. Phone scans the QR, generates its own X25519 keypair, calls
   `/devices/register` with the one-time token, and registers its public
   key.
3. Relay mints a tenant-scoped bearer token, returns it to the phone.
4. Phone derives the shared secret via ECDH; ready to encrypt.

**Steady-state request**
1. App encrypts the request body (if any) with the shared key, sends it
   through the nginx mTLS edge with its bearer token.
2. Relay authenticates the token → resolves `TenantID` → looks up
   `registry[tenantID]` → `503 agent_offline` if absent (unchanged) →
   forwards method/path/headers + encrypted body over the tenant's yamux
   stream, untouched.
3. Agent decrypts using the shared key for that specific device, processes,
   encrypts the response.
4. Relay proxies the (still-encrypted) response back; phone decrypts.

**Terminal WebSocket stream** — same principle, per-frame AEAD instead of
per-request (nonce is a per-direction counter; must never repeat under a
given key).

## Error handling

- Unrecognized/revoked agent cert on `/agent/tunnel` → `403` (checked
  against the cert table, not a constant).
- Valid device token, tenant's agent not connected → `503 agent_offline`
  (unchanged behavior).
- Decryption/tamper failure (bad key, corrupted ciphertext, replay) → **fail
  closed** — drop the connection/request, never fall back to plaintext.
  Surface a generic "re-pair this device" to the user rather than a specific
  reason, to avoid giving an attacker a decryption oracle.
- Lost/stolen phone → owner revokes that device's bearer token (existing
  `Store.Revoke`, unchanged). Because each device has its own independent
  shared secret with the agent, revocation doesn't require rotating the
  agent's key or re-pairing other devices.
- Relay host fully compromised / DB exfiltrated → attacker obtains: tenant
  IDs, hashed bearer tokens (not directly usable), device/agent public keys
  (harmless by design), agent cert serials. **No private keys, no shared
  secrets, no plaintext content are ever at rest on the relay.** This is the
  core guarantee of the design.

## Testing

- Adversarial multi-tenant test: two tenants' agents connected concurrently;
  assert tenant A's device token can never route to tenant B's session
  (explicit cross-token attempt must fail, not merely "usually not hit").
- Crypto round-trip tests: encrypt on one side, decrypt on the other;
  tamper tests (flip a ciphertext byte, must fail closed, not silently
  succeed).
- Nonce-reuse / counter-wraparound tests for the AEAD framing.
- Cross-language interop test: fixed test vectors shared between the Go and
  Kotlin implementations, asserting byte-identical ciphertext from identical
  inputs — this is the test most likely to catch silent drift between the
  two codebases.

## Explicit non-goals (this spec)

- Self-service registration abuse-resistance (CAPTCHA, email verification,
  Sybil resistance) — sub-project 2.
- Rate limiting / per-tenant quotas / DoS protection — sub-project 3.
- Audit logging policy, data retention/deletion, incident response, legal
  ToS — sub-project 4.
- Fully opaque (metadata-hiding) relay transport — considered and explicitly
  deferred; see "Blindness depth" above.
- Forward secrecy / key ratcheting beyond the initial per-pairing shared
  secret — noted as a possible future hardening, not required for v1.
