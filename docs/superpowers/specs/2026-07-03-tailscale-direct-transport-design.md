# Tailscale-direct transport (alternative to the relay)

## Context

Today `cmux-bridge agent` has exactly one way to reach the outside world: it
dials out to `cmux-relay` over a yamux-over-WSS tunnel, and the relay is the
only thing the phone ever talks to. This mirrors the official cmux iOS app's
own policy of a Tailscale-only remote transport, minus the Tailscale part —
this repo deliberately chose relay+mTLS instead (see
`2026-06-29-cmux-android-bridge-design.md`) specifically to avoid requiring
Tailscale, a home server, or router changes.

The user now runs (or is willing to run) Tailscale on both the Mac and the
phone, and wants a **second, optional** transport: the phone talks straight to
the Mac agent over the tailnet, with no relay and no home server in the
path, for the common case of "phone and Mac are both reachable on the same
tailnet right now." This is additive — the relay stays exactly as it is,
including for push notifications and for reachability when Tailscale isn't
available (e.g. a network that blocks it).

## Current architecture (confirmed from code)

- `bridge/cmd/cmux-bridge/agent.go`: the agent process has **zero**
  `net.Listen`/`http.ListenAndServe` calls. Its only network I/O is
  `ensureRegistered` (one-shot HTTPS POST to the relay's bootstrap endpoint)
  and `dialAndServe`, which dials the relay and does `http.Serve(sess,
  handler)` where `sess` is a `yamux.Session`, not a real socket. A local
  "serve" mode existed once and was deliberately deleted in `e55075e` because
  it was dead code (never wired to a store or pusher) — not because a local
  listener is unsafe.
- `bridge/internal/config/agent.go`'s `AgentConfig` has no listen-address
  field at all today; every field is relay-shaped (`RelayURL`, `ClientCert`,
  `ClientKey`, `CACert`, `RelayToken`, `BootstrapURL`) plus local state paths
  (`IdentityKey`, `SessionStore`, `YoloStore`).
- `bridge/internal/auth/store.go`'s `Store` (SQLite) is **fully standalone
  and relay-agnostic** — `sessions_test.go`'s `newTestServer` proves this: it
  opens a bare `auth.Store`, creates a tenant, issues a device token, and
  serves `Server.Handler()` via `httptest.NewServer` with zero relay
  involved. It already has everything pairing needs: `Issue`,
  `NewPairingCode`, `RedeemPairingCode`, `PairingCodeStatus`,
  `PairingCodeInfo`, `Verify`. Production `runAgent` simply never uses this
  path — it constructs `server.New(config.Config{}, cmuxClient, nil)` (a
  `nil` store) and serves `srv.TrustedHandler(cfg.RelayToken)` instead, which
  gates on a single static shared secret (`RequireRelayToken`,
  `internal/server/trusted.go`), not per-device bearer tokens.
- `internal/server/encryption.go`'s `encryptionMiddleware` decrypts/encrypts
  HTTP bodies keyed by an `X-Device-ID` header. Today only
  `internal/relay/proxy.go`'s Director sets that header
  (`req.Header.Set("X-Device-ID", dev.TokenHash)`), after the relay's own
  `auth.Store.Verify` resolves the bearer token. The e2e layer
  (`internal/e2e/`) itself has no relay/tunnel/TLS dependency at all — it
  only needs a `deviceID` string as a map key.
- `internal/auth/middleware.go`'s `Require(store, next)` already resolves a
  bearer token to a `Device` (with a `TokenHash` field — the exact value the
  relay injects as `X-Device-ID`) and attaches it to the request context via
  `DeviceFromContext`. Nothing today chains `Require` into
  `encryptionMiddleware`; they're two independent, currently-mutually-
  exclusive auth paths (`Handler()` vs. `TrustedHandler()`).
- Push (`internal/relay/pushmon.go`) runs **inside the relay process only**,
  holding no equivalent on the agent side; `internal/push` (FCM/Firebase) is
  imported only by `cmd/cmux-relay`. `Server.routes()` deliberately never
  registers `/devices/register` — that's relay-only by design
  (`server.go`'s own doc comment).
- Android's `BridgeClient.kt`, `PairingClient.kt`, `Mtls.kt`, and
  `Settings.kt` are **scheme- and relay-agnostic**: no hardcoded `https://`,
  no client-certificate mTLS on the phone side (that lives purely at the
  nginx edge, invisible to the client), and pairing persists exactly
  `{baseUrl, deviceToken, e2eSharedSecret}` with no relay-specific field.
  `CmuxNavHost.kt` already has a `Routes.SETTINGS` destination that reopens
  `PairingScreen` at any time (wired from the Sessions screen's Settings
  icon) — re-pairing against a different server URL is already possible with
  no new navigation code.
- Android's default Network Security Config (API 28+, `targetSdk = 35` here)
  blocks cleartext (`http://`/`ws://`) traffic app-wide unless explicitly
  allowed via `network_security_config.xml` — this app has no such override
  today.

## Decisions made (confirmed with user)

- **Relationship to the relay:** additive. The relay is untouched; direct
  mode is a second listener the agent can also run. Nothing about the
  existing relay path (dial loop, push, away-from-home access) changes or is
  removed.
- **Tailscale isn't set up yet** — this project includes the one-time manual
  setup steps (Tailscale account, Mac install, phone install, admin-console
  config), not just the code.
- **Use the OS-level Tailscale app on both ends**, not an embedded `tsnet`
  node. The bridge talks to the Mac's already-running `tailscaled` over its
  local API (`tailscale.com/client/tailscale.LocalClient`); the phone uses
  the official Tailscale Android app. This keeps the Go dependency to the
  `LocalClient` surface (no `tsnet`, no bundled WireGuard stack) and needs no
  Android-side networking library.
- **TLS via `tailscale cert`, not self-signed:** Tailscale (with MagicDNS +
  "HTTPS Certificates" enabled in the admin console) issues each node a real,
  publicly-trusted Let's Encrypt certificate for its `<node>.<tailnet>.ts.net`
  name. `tailscale.LocalClient.GetCertificate` implements
  `tls.Config.GetCertificate` directly, so the Go listener gets automatic
  cert provisioning/renewal with no cron job and no manual file handling.
  This is also what makes the Android side a no-op: it's ordinary
  publicly-trusted HTTPS, not a custom trust anchor.
- **Android app code: no changes required.** Confirmed above — `BridgeClient`
  et al. already work with any HTTPS base URL, and the existing Settings →
  re-pair flow is enough to switch between relay and direct mode. This is a
  conscious v1 trade-off: switching is a manual re-pair (a few taps), not an
  automatic reachability-based fallback. Only one `{baseUrl, token, secret}`
  triple is stored at a time.
- **Push notifications stay relay-only.** The agent holds no FCM
  credentials and this project doesn't add any. If the relay is also
  configured, push keeps working regardless of which transport is currently
  paired for foreground requests, since push is server (relay)-initiated and
  independent of the phone's active base URL.

## Design

### Bridge (Go)

**Config** (`bridge/internal/config/agent.go`, `AgentConfig`):
- `DirectListen string` (toml `direct_listen`) — e.g. `":8443"`. Empty
  (default) disables direct mode entirely; nothing about `runAgent` changes
  when unset.
- `DirectAuthStore string` (toml `direct_auth_store`) — path to direct
  mode's own local SQLite store, default
  `~/.config/cmux-bridge/direct-auth.db`. Deliberately a separate database
  from anything relay-shaped: direct mode has exactly one implicit tenant
  (this Mac), auto-created on first use if the store has none yet.

**New file `bridge/internal/server/direct.go`:**
- `Server.DirectHandler(store *auth.Store) http.Handler` — composes, from the
  outermost wrap in:
  1. `auth.Require(store, ...)` — verifies the per-device bearer token,
     attaches `Device` to the request context.
  2. A small new adapter, `injectDeviceID`, that reads the `Device` back out
     of context (`auth.DeviceFromContext`) and sets
     `r.Header.Set("X-Device-ID", dev.TokenHash)` — **overwriting** any
     client-supplied value, so a real device can't spoof another device's
     ID. This is the one piece of new plumbing: it lets the existing,
     unmodified `encryptionMiddleware` (which expects `X-Device-ID` to
     already be trustworthy, as it is today via the relay's proxy) work
     when there's no relay to have set it.
  3. `s.encryptionMiddleware(...)` — unmodified; decrypts the body, encrypts
     the response, exactly as today.
  4. `s.routes(...)` — the same route table `Handler()`/`TrustedHandler()`
     already share (`/sessions`, `/events`, `/terminal/{id}`,
     `/feed/pending`, `/feed/{id}/reply`, rename, YOLO mode).
- Also in this file: four pairing-code HTTP handlers, mounted only on the
  direct listener's own mux (not on `routes()`, and not behind
  `auth.Require` — pairing must be reachable before a device has a token).
  Direct mode's local `auth.Store` has exactly one tenant (this Mac, auto-
  created on first use — see `runAgent` wiring below), loaded once at
  `runAgent` startup and closed over by these handlers, so — unlike the
  relay's equivalents — **none of the four need `agentOnly`'s mTLS-CN tenant
  resolution**; there's no second tenant to disambiguate from, and the real
  access-control boundary for this whole listener is Tailscale's own network
  ACLs, not a per-request identity check. Each is a near-line-for-line port
  of an existing relay handler with that one simplification, backed by
  `auth.Store` instead of the relay's copy of the same type:
  - `POST /agent/pairing-code` — ports `relay.go`'s `handleNewPairingCode`
    minus the `agentOnly` gate: reads `{agent_pubkey}` from the body, calls
    `store.NewPairingCode(tenantID, pubkey, ttl)`, returns
    `{code, expires_at, tenant_id}`.
  - `GET /agent/pairing-code/{code}` — ports `handlePairingCodeStatus` minus
    the gate: calls `store.PairingCodeStatus(tenantID, code)`, returns
    `{redeemed, device_pubkey, token_hash}`.
  - `GET /devices/pair-info/{code}` — ports `handlePairingCodeInfo`
    unchanged (it already takes no tenant ID — "not tenant-scoped" by
    design, since the phone doesn't know its tenant yet): calls
    `store.PairingCodeInfo(code)`, returns
    `{agent_pubkey, expires_at, tenant_id}`. This is the endpoint Android's
    `PairingClient.resolvePairingCode` actually calls for manual entry.
  - `POST /devices/pair` — ports `handleDevicePair` unchanged: reads
    `{code, name, device_pubkey}`, calls
    `store.RedeemPairingCode(code, name, devicePubkey)`, returns
    `{token, tenant_id}`. The agent-side shared-secret derivation
    (`e2e.DeriveSharedSecret` + `sessions.AddDevice`) stays exactly where it
    is today — inside `cmd/cmux-bridge/pair.go`'s existing `pairDevice()`
    helper, unchanged, since that function is already fully generic over
    `agentBase`/`devicePairURL` and needs no direct-mode-specific logic (see
    CLI section below). It writes to the **same** `e2e.Store`
    (`SessionStore`) direct mode and relay mode share, since a device is a
    device regardless of which transport paired it.

**`runAgent` (`bridge/cmd/cmux-bridge/agent.go`) wiring:**
- When `cfg.DirectListen != ""`: open `auth.Open(cfg.DirectAuthStore)`,
  auto-`CreateTenant()` if the store has no tenant yet (persist nothing extra
  — `ListTenants()` on startup tells us if one already exists), then spawn a
  goroutine that:
  1. `tcpLn, err := net.Listen("tcp", cfg.DirectListen)`
  2. `lc := &tailscale.LocalClient{}` (zero-value; auto-discovers the local
     `tailscaled` socket)
  3. `tlsLn := tls.NewListener(tcpLn, &tls.Config{GetCertificate:
     lc.GetCertificate})`
  4. `http.Serve(tlsLn, directMux)` where `directMux` routes `/agent/…` and
     `/devices/pair` to the new pairing handlers and everything else to
     `srv.DirectHandler(directStore)`.
  This goroutine runs **alongside**, not instead of, the existing relay
  dial-with-backoff loop — both are always-on when configured.

**CLI (`bridge/cmd/cmux-bridge/pair.go`):** add a `--direct` flag to
`pair-device`. `pairDevice(client, agentBase, devicePairURL, identity,
sessions, out, pollPeriod, deadline)` (pair.go:108) is already fully generic
over its `client`/`agentBase`/`devicePairURL` parameters and needs **no
changes** — only `runPairDevice`'s setup branches:
- `agentBase`: instead of `httpsBaseFromRelayURL(cfg.RelayURL)`, call
  `(&tailscale.LocalClient{}).Status(ctx)`, read `Self.DNSName`, and build
  `https://<dnsname>:<port from cfg.DirectListen>` (trimming DNSName's
  trailing dot).
- `client`: a plain `&http.Client{}` — no `loadTLS`/client-cert setup at
  all, since the direct listener's cert is a real publicly-trusted Let's
  Encrypt cert validated by the system's default root CAs (unlike the
  relay path's mTLS, which requires `cfg.ClientCert`/`ClientKey`/`CACert`).
The QR payload and manual-entry code format are unchanged — only how
`agentBase`/`client` are built differs.

**Deploy:** `bridge/deploy/agent.example.toml` gets two new commented-out
example lines (`# direct_listen = ":8443"`,
`# direct_auth_store = "~/.config/cmux-bridge/direct-auth.db"`), and
`bridge/README.md` gets a new "Direct (Tailscale) mode" section alongside
the existing Relay/Agent/Pairing sections, covering the manual setup below.

### Android

No app code changes. Re-pairing against a direct-mode URL uses the exact
same "Enter server URL and code manually" flow already in `PairingScreen`,
because:
- the base URL is ordinary `https://` with a publicly-trusted cert (no
  `network_security_config.xml` change needed — this is precisely why
  `tailscale cert` was chosen over a self-signed cert), and
- `BridgeClient`/`PairingClient`/`Settings` never assumed a relay was
  present.

### Setup (operational, one-time, not code)

1. Create/use a Tailscale account; install Tailscale on the Mac (Mac App
   Store, or `brew install --cask tailscale`) and run `tailscale up`.
2. In the Tailscale admin console: enable **MagicDNS** (DNS page) and
   **HTTPS Certificates** (same page's HTTPS Certificates section) — a
   one-time acknowledgement that the tailnet's device names become visible
   on a public CT log.
3. Install the official **Tailscale** app from the Play Store on the phone;
   sign in to the same tailnet.
4. On the Mac, confirm cert issuance works before wiring up the Go side:
   `sudo tailscale cert $(tailscale status --json | jq -r .Self.DNSName)`.
5. Add `direct_listen = ":8443"` (or any free port) to `agent.toml`, restart
   the `cmux-bridge` LaunchAgent.
6. Run `cmux-bridge pair-device --config ~/.config/cmux-bridge/agent.toml
   --direct`, get the manual code, and pair the phone against the printed
   `https://<mac>.<tailnet>.ts.net:8443` URL from the app's Settings screen.

## Data flow

### Pairing (direct mode)

1. `pair-device --direct` reads the Mac's own tailnet DNS name via
   `LocalClient.Status`, POSTs its e2e pubkey to its own
   `/agent/pairing-code` (served by the same process, over the loopback-
   reachable direct listener), gets back a short code, renders it
   (QR + manual code, unchanged from today).
2. Phone (manual entry) → `GET /devices/pair-info/{code}`
   (`PairingClient.resolvePairingCode`, unchanged client code) →
   `POST /devices/pair` on the direct listener → `auth.Store.RedeemPairingCode`
   mints a bearer token scoped to the direct-mode tenant.
3. Both sides derive the shared secret via X25519 ECDH (unchanged
   `e2e.DeriveSharedSecret`); the agent persists it in the existing
   `e2e.Store` keyed by the new token's hash; the phone persists
   `{baseUrl, token, secret}` in `Settings` exactly as it does for a relay
   pairing today.

### Steady-state request

1. Phone (Tailscale VPN active) resolves `<mac>.<tailnet>.ts.net` via
   MagicDNS, dials straight to the Mac's `direct_listen` port over the
   tailnet — WireGuard-encrypted at the network layer, TLS-encrypted at the
   transport layer (Let's Encrypt cert), e2e-encrypted at the body layer
   (unchanged AEAD envelope), bearer-token-authenticated (unchanged
   `Authorization: Bearer …`).
2. `auth.Require` verifies the token against the direct-mode store, attaches
   `Device` to context.
3. `injectDeviceID` copies `dev.TokenHash` into `X-Device-ID`.
4. `encryptionMiddleware` decrypts the body using that ID, exactly as the
   relay path does today.
5. The same route handlers as today run unmodified.

## Error handling / edge cases

- **Tailscale VPN off on the phone**, or the Mac unreachable on the tailnet:
  the request times out / fails to resolve, surfaced through the app's
  existing `UiState.Error`/`_actionError` paths — no new error handling
  needed, this is the same "bridge unreachable" shape the app already
  handles for a relay outage.
- **Direct listener up but no device paired yet**: `auth.Require` returns
  401 `unauthorized` for `/sessions` etc.; the two pairing endpoints remain
  reachable regardless (they're not behind `auth.Require`).
- **Cert not yet issued on first request**: `GetCertificate` performs the
  ACME DNS-01 round trip synchronously on the first TLS handshake that needs
  it; that request is slow (seconds) but succeeds — no special handling
  needed, this is exactly Tailscale's documented behavior. Step 4 of the
  setup list above is what confirms this works before it's ever hit
  live.
- **`direct_auth_store` has no tenant yet on startup**: `runAgent` calls
  `ListTenants()`; if empty, `CreateTenant()` once. If one already exists,
  reuse it — this makes `direct_listen` safe to toggle on/off across
  restarts without minting a fresh tenant (and thus orphaning previously
  paired devices) every time.
- **A device paired via the relay tries hitting the direct listener (or vice
  versa) with its existing token**: fails closed with 401 — the two
  `auth.Store`s are entirely separate databases, so a token from one is
  simply unknown to the other. This is intentional, not a bug to fix: each
  transport's pairing is independent, matching the "manual re-pair to
  switch" trade-off above.

## Testing

- Go: `auth.Store`'s existing pairing-code methods are already covered
  (`store_test.go`); new tests needed are (1) the three new HTTP handlers
  (mirroring `relay_test.go`'s equivalent handler tests, but against a bare
  `auth.Store` with no relay), (2) `DirectHandler`'s middleware composition —
  a request with a valid bearer token but a forged `X-Device-ID` header must
  have that header overwritten, not honored (adversarial test, mirroring the
  intent of `multitenant_test.go`), and (3) `injectDeviceID` with no `Device`
  in context (shouldn't be reachable past `auth.Require`, but assert it
  doesn't crash / doesn't set a bogus header).
- No Android tests needed — no Android code changes.
- Manual end-to-end (both devices real, not emulated): pair the phone
  against the direct URL, turn off the Mac's relay connectivity (e.g. stop
  `cmux-relay` or disconnect the Mac from the internet while keeping
  Tailscale up) and confirm sessions list / terminal / feed replies still
  work purely over the tailnet; separately confirm push still arrives via
  the relay while the phone is paired in direct mode (proves push's
  independence from the active transport).

## Explicit non-goals (this project)

- Embedding Tailscale (`tsnet`) in the bridge or the Android app — using the
  OS-level apps on both ends, per the decision above.
- Automatic transport selection/fallback between relay and direct mode —
  switching is a manual re-pair in v1.
- Push notifications over direct mode (agent-side FCM) — push remains
  relay-only.
- Any change to the relay's own code, config, or deployment.
- Tailscale ACL/access-control authoring beyond the minimum (enabling
  MagicDNS + HTTPS Certificates) — the user's own tailnet policy is out of
  scope here.
