# cmux Relay (v2 rendezvous topology) — Design

**Status:** Approved design, pre-plan. Supersedes the "bridge runs on the Mac and
is reverse-proxied directly" topology from
`2026-06-29-cmux-android-bridge-design.md` for *remote* access.

**Goal:** Let the Android app reach the user's Mac cmux sessions from anywhere
(e.g. the office) by rendezvousing through an always-on **relay** on the home
server `192.168.1.160`, reachable at a registered domain behind nginx mutual TLS.
The Mac sits behind home NAT and **dials out** a single persistent tunnel to the
relay; the app connects **inbound** over the domain. Neither endpoint needs an
inbound public port except the relay's nginx.

**Why:** The Mac is behind NAT and not publicly reachable; an always-on relay the
Mac dials out to is reachable from outside the home network.

---

## Global Constraints

- **The app's HTTP/WS contract is preserved at the relay's app edge.** The relay
  re-exposes the identical routes (`GET /sessions`, `WS /events`,
  `WS /terminal/{id}`, `POST /feed/{id}/reply`, `POST /devices/register`). The
  Android app changes **only its base URL** (already a Settings field) — no app
  code changes are required for connectivity.
- **One edge, one CA.** Both the Mac agent and app devices connect to the relay
  through the **same nginx mTLS endpoint** on the home server's public DNS name,
  port 443. Both present client certificates signed by the user's existing
  client CA. The Mac connects *as a remote client over the domain* — it never
  assumes it is on the LAN.
- **Single Mac (v1).** The relay holds exactly one agent session; a new agent
  tunnel replaces the previous one. Multi-Mac routing is out of scope.
- **Relay replaces the Mac-direct remote path.** The old `nginx → Mac:8765`
  vhost is retired for remote access. (Running cmux locally on the Mac is
  unaffected; this design only concerns remote reach.)
- **No new heavyweight dependencies.** One small, mature multiplexing library
  (`github.com/hashicorp/yamux`) is permitted; everything else reuses the
  existing stack (`gorilla/websocket`, `BurntSushi/toml`, `golang.org/x/oauth2`).
- **Single Go module.** Everything lives in `github.com/sodre90/cmux-bridge`
  (the existing `bridge/` tree), shipping two binaries.
- **Test discipline unchanged:** no real network egress, no real cmux (fake
  cmux binary), no real FCM (interface/fake). Matches the existing 43-test suite.
- **Mac-offline (v1) = clear offline state.** No Wake-on-LAN, no request
  queueing. The user keeps the Mac awake (macOS power settings). The relay
  detects tunnel loss via yamux keepalive and returns `503 agent_offline`.
- Commits authored solely by the human (`sodre90 <erdos.peter.bme@gmail.com>`);
  no AI co-author trailers.

---

## Architecture

```
                         home server 192.168.1.160
                    ┌─────────────────────────────────────────┐
  Android app ─────►│ nginx :443  (mTLS, your domain)         │
  (device cert      │   /agent/tunnel  ─► relay agent acceptor│
   + Bearer token)  │   /*             ─► relay app API       │
                    │                                         │
                    │  cmux-relay  (NEW, binds loopback)      │
                    │   • device auth: Bearer vs token store  │
                    │   • pairing (pair/devices) + token store│
                    │   • ONE yamux session to the Mac        │
                    │   • reverse-proxy each app req → stream │
                    │   • FCM push (own /events monitor)      │
                    │   • 503 agent_offline when no session   │
                    └─────────────────────────────────────────┘
            ▲
            │ one persistent WSS  →  yamux session  (keepalive)
            ▼
   Mac agent  (today's cmux-bridge handlers, unchanged)
     dial-out loop → tunnel → yamux server →
       http.Serve(yamuxListener, server.Handler())  →  cmux rpc / cmux events
```

The pivotal property: a **yamux stream satisfies `net.Conn`** and a **yamux
session satisfies `net.Listener`**. So the Mac agent serves its *existing*
`server.Handler()` over the tunnel with `http.Serve(session, handler)`, and the
relay is an `httputil.ReverseProxy` whose `Transport.DialContext` opens a yamux
stream. The `/events` and `/terminal` gorilla WebSockets work unchanged: gorilla's
upgrade only needs `http.Hijacker`, which `http.Serve` provides; and Go's
`ReverseProxy` passes `Upgrade` requests through.

---

## Components

### Mac agent — `cmd/cmux-bridge` (existing binary, gains tunnel mode)

- **Keeps:** the cmux CLI client (`internal/cmux`) and the entire
  `internal/server` handler (`/sessions`, `/events`, `/terminal/{id}`,
  `/feed/{id}/reply`) plus `RunEvents(ctx)` sourcing events from cmux.
- **Gains:** an `agent` mode (`cmux-bridge agent`) that:
  1. Dials `wss://<domain>/agent/tunnel`, presenting the agent client cert
     (`CN=mac-agent`) and trusting the configured CA.
  2. Wraps the WSS connection as a `net.Conn` and starts a **yamux server**
     session on it.
  3. Serves the existing handler in **trusted mode** via
     `http.Serve(yamuxListener, handler)`.
  4. Reconnects with capped exponential backoff if the tunnel drops.
- **Trusted mode:** the handler skips the device-`Bearer` check (the relay is
  the gatekeeper) but **requires** a static `X-Relay-Token: <shared-secret>`
  header on every request — defense-in-depth so a misconfigured/standalone agent
  cannot serve an unauthenticated peer.
- **Drops:** the device token store, `pair`/`devices` subcommands, the FCM
  pusher, and the public TCP listener (a local `serve` mode may remain for dev).

### Relay — `cmd/cmux-relay` (new binary, on the home server)

- **Agent acceptor** (`GET /agent/tunnel`, WSS upgrade): accepts only when the
  forwarded `X-Client-Cert-CN` equals the configured `agent_cn`. On accept,
  hijacks to a `net.Conn`, starts a **yamux client** session, and registers it as
  *the* current agent session (replacing and closing any prior one).
- **App API** (all other routes): middleware verifies `Authorization: Bearer
  <device-token>` against the token store (nginx already enforced the device's
  mTLS). If no agent session is registered → `503 {"error":"agent_offline"}`.
  Otherwise reverse-proxy the request over a fresh yamux stream, injecting
  `X-Relay-Token`. WebSocket upgrades pass through.
- **`POST /devices/register`:** stores the device's FCM token in the relay's
  store (keyed to the device token); not forwarded to the agent.
- **Push monitor:** on agent connect, opens the relay's **own** `/events`
  subscription (a gorilla WS client dialed over a yamux stream) and watches the
  feed independently of any app connection. On a blocking prompt
  (`needs_attention` feed item, kind ∈ {permissionRequest, question, exitPlan}),
  sends a high-priority FCM **data** message (`type=attention`, plus kind/title)
  to every registered device token. Reconnects when the agent returns.
- **Pairing:** `cmux-relay pair --name <label>`, `cmux-relay devices [list|revoke]`
  (the `auth.Store` and its commands move here).
- Binds **loopback** only; nginx is the sole public surface.

### nginx (home server)

A new vhost on the public DNS name, port 443:
- Server cert for the domain; `ssl_verify_client on` against the client CA.
- `map $http_upgrade $connection_upgrade` block (http context) for WS.
- `location /agent/tunnel` and `location /` both `proxy_pass` to the relay's
  loopback port, set the `Upgrade`/`Connection` headers, forward
  `X-Client-Cert-CN $ssl_client_s_dn`, and use 1h read/send timeouts.
- Replaces `bridge/deploy/nginx-cmux-bridge.conf`'s Mac-direct `proxy_pass`.

### Android app

No connectivity code changes — point the base URL at the home domain. One small
UX follow-up: render the relay's `503 agent_offline` as a clear "⚠ Mac offline"
state rather than a generic error.

---

## Data flow

**Open a terminal:**
```
App  WS /terminal/{id}   (device cert + Bearer)
 → nginx (mTLS, device CN)         → relay
 → relay: Verify(Bearer); agent session present?
 → relay: session.Open() stream    → proxy WS upgrade (+ X-Relay-Token) over it
 → Mac agent /terminal handler (on yamux listener) → cmux
 → render-grid frames stream back over the same stream → relay → app
```

**Reply / sessions / register:** identical, minus the upgrade — a plain HTTP
request/response proxied over one yamux stream (register is handled at the relay
and not forwarded).

**Offline:**
```
Mac asleep → yamux keepalive fails (seconds) → relay drops & closes the session
App request → relay: no session → 503 {"error":"agent_offline"} → app shows offline
```

---

## Security (layered)

1. **Network:** nginx mTLS — no client cert signed by the CA, no connection.
2. **Identity:** nginx forwards the client-cert CN; the relay routes `CN=mac-agent`
   + `/agent/tunnel` to the tunnel acceptor and rejects the agent CN on API paths
   (and vice-versa).
3. **Device authz:** API requests must carry a valid, revocable `Bearer`
   device-token checked against the relay's store.
4. **Agent authz:** only the agent CN may open the tunnel; the relay keeps a
   single session.
5. **Tunnel integrity:** the relay injects `X-Relay-Token` on every proxied
   request; the agent rejects requests lacking it.
6. **Surface minimization:** the relay binds loopback; the Mac agent has no
   inbound public port — it only dials out.

---

## Code structure

```
bridge/
  cmd/
    cmux-bridge/          # Mac AGENT — gains `agent` mode
      main.go             #   subcommands: agent | serve (local dev) | version
      agent.go            #   NEW: dial loop → tunnel → yamux server → http.Serve
                          #   pair/devices REMOVED
    cmux-relay/           # NEW binary, home server
      main.go             #   subcommands: serve | pair | devices | version
      serve.go            #   relay HTTP (loopback) + agent acceptor + push monitor
      commands.go         #   pair/devices (token store) — MOVED here
  internal/
    tunnel/               # NEW shared: WSS ⇄ net.Conn ⇄ yamux glue + keepalive
                          #   Dial(ctx, url, tlsCfg) (agent) / Accept(w, r) (relay)
    relay/                # NEW: session registry (single, replace-on-new),
                          #   reverse proxy (Transport.DialContext = session.Open),
                          #   CN routing, Bearer check, X-Relay-Token inject,
                          #   offline 503, /events push monitor
    server/               # EXISTING handler reused by agent; gains "trusted mode"
                          #   (skip Bearer, REQUIRE X-Relay-Token)
    auth/                 # EXISTING token store — now wired into the relay
    push/                 # EXISTING FCM sender — now wired into the relay
    cmux/                 # EXISTING cmux CLI client — stays on the agent
    config/               # split into agent vs relay config structs
```

**Config split:**
- *Agent (`config.AgentConfig`):* `relay_url` (wss://…/agent/tunnel),
  `client_cert`, `client_key`, `ca_cert`, `relay_token`, `cmux_bin`.
- *Relay (`config.RelayConfig`):* `listen` (loopback), `agent_cn`, `relay_token`,
  `token_store`, `fcm_project_id`, `fcm_credentials`.

---

## Testing strategy

All offline; reuse the existing fakes (fake cmux binary; a `Pusher`/sender fake).

- **`internal/tunnel`:** in-memory `httptest` WSS + yamux on both ends — open a
  stream, serve a trivial `http.Handler` on the agent side, hit it from the relay
  side, assert the round-trip body. Assert WS upgrade survives a stream.
- **`internal/relay`:**
  - *proxy:* with a fake agent session (yamux over `net.Pipe`), an app request is
    forwarded and the response returns; `X-Relay-Token` is injected.
  - *auth:* missing/invalid Bearer → 401; valid → forwarded; agent CN on an API
    path → reject; device CN on `/agent/tunnel` → reject.
  - *offline:* no session → 503 `agent_offline`.
  - *registry:* a new tunnel replaces and closes the old session.
  - *push monitor:* a `needs_attention` events frame from a fake agent → the
    sender is called with `type=attention`; a non-attention frame → no send.
- **`cmd/cmux-bridge` agent:** dials a fake relay (`httptest` WSS), establishes a
  yamux session, serves the handler, and reconnects with backoff after a drop.
- **`server` trusted mode:** skips Bearer but rejects a request missing
  `X-Relay-Token`.

---

## Deploy

- **Relay** on `192.168.1.160` (assumed Linux/systemd — confirm): a
  `cmux-relay.service` systemd unit binding loopback, plus a new home-server
  nginx vhost (above). Both shipped under `bridge/deploy/`.
- **Agent** on the Mac: the existing launchd plist, reconfigured to run
  `cmux-bridge agent` with the relay URL + agent cert + `relay_token`.
- **Certs:** one new **agent client cert** (`CN=mac-agent`) signed by the
  existing client CA; device certs unchanged. openssl steps documented in the
  deploy README.

---

## Out of scope (later)

Multiple Macs, Wake-on-LAN / request queueing, request-level authorization on
the agent beyond `X-Relay-Token`, a numeric pairing-code exchange, and any
change to cmux session lifecycle (the agent still only reads, drives terminal
I/O, and replies to feeds — never creates/closes/restores).

---

## Confirm-against-live items (carried from the bridge work)

These remain unverified and are unchanged by this topology; flagged in
`android/README.md`: exact `feed.*.reply` param names beyond `request_id`,
whether a session `id` doubles as the terminal surface id, and the
`render-grid` color encoding.
