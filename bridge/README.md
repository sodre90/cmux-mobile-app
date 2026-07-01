# cmux-bridge

Remote access to your Mac's [cmux](https://github.com/manaflow-ai/cmux) sessions
from anywhere, via two small Go binaries:

- **`cmux-relay`** — a rendezvous daemon on your home server, behind nginx mTLS
  on a public DNS name. It owns device auth, pairing, and FCM push.
- **`cmux-bridge agent`** — runs on your Mac next to cmux. It **dials out** to
  the relay (so the Mac needs no inbound ports / port-forwarding) and serves the
  same HTTP/WebSocket API over the tunnel.

Both speak to cmux **only through the documented `cmux` CLI** (`cmux rpc` and
`cmux events`). They store no cmux socket password and copy no cmux source — an
independent work that consumes cmux's IPC contract.

## Architecture (v2 relay topology)

```
    ┌────────────────────────────┐
    │        Android app         │
    └────────────────────────────┘
                   │  HTTPS / WSS — device client cert
                   ▼
    ┌────────────────────────────┐
    │  nginx (mutual TLS edge)   │
    │      public DNS name       │
    └────────────────────────────┘
                   │  HTTP / WS — loopback only
                   ▼
    ┌────────────────────────────┐
    │         cmux-relay         │
    │       (home server)        │
    └────────────────────────────┘
                   │  yamux stream — routed by client-cert CN
                   ▲  (the Mac dials OUT — agent:<tenant-id> client cert)
    ┌────────────────────────────┐
    │  cmux-bridge agent (Mac)   │
    └────────────────────────────┘
                   │  cmux rpc / cmux events
                   ▼
                   cmux.app  (unchanged)
```

The relay multiplexes every app request as a fresh [yamux](https://github.com/hashicorp/yamux)
stream over the single agent tunnel (a WebSocket, so it traverses nginx on 443).
The agent serves its existing handler verbatim. When the Mac is not connected,
the relay returns `503 {"error":"agent_offline"}`.

Security is layered: **mutual TLS at the nginx edge** (both the agent and devices
present client certs signed by one CA) + **client-cert CN routing** at the relay
+ a **per-device bearer token** checked by the relay + an `X-Relay-Token` shared
secret the relay injects so the agent only honors relay-originated requests. The
relay binds loopback only; the agent has no listening port at all.

## Build

Requires Go 1.26+.

```bash
cd bridge
go build -o cmux-relay  ./cmd/cmux-relay     # for the home server
go build -o cmux-bridge ./cmd/cmux-bridge    # for the Mac (agent mode)
go test ./...        # all tests run with no network and no real cmux
```

## Relay (home server)

1. Copy the binary to `/usr/local/bin/cmux-relay`.
2. Copy `deploy/relay.example.toml` to `/etc/cmux-relay/config.toml` and set
   `relay_token` (a long random secret) and optionally the FCM fields. On
   first run the relay generates its own CA (`ca_cert`/`ca_key`, default
   `~/.config/cmux-relay/ca.crt` / `ca.key`) and signs every agent and device
   cert against it — there's no separate hand-rolled CA to create any more.
3. Install the systemd unit and nginx vhost:

   ```bash
   cp deploy/cmux-relay.service /etc/systemd/system/
   systemctl enable --now cmux-relay
   cp deploy/nginx-cmux-relay.conf /etc/nginx/sites-available/cmux
   # enable the site + add the `map $http_upgrade $connection_upgrade` block, reload nginx
   ```

   If a new Mac agent will self-register (see [Agent client
   certificate](#agent-client-certificate) below), also install the no-mTLS
   bootstrap vhost — `deploy/nginx-cmux-relay-bootstrap.conf` proxies only
   `POST /tenants/register`, on a separate port (8444 in the example). The
   main vhost above keeps `ssl_verify_client on`, unchanged, for the agent
   tunnel and all device traffic.

The relay binds `127.0.0.1:8765`; nginx is the only public surface. nginx must
**set** `X-Client-Cert-CN $ssl_client_s_dn` (never trust an inbound value) so
the relay can route by CN.

### Run in a container (podman)

`docker-compose.yml` + `deploy/Containerfile` build and run the relay in a
rootless container. Copy this tree to the host, drop a `config.toml` beside the
compose file (from `deploy/relay.example.toml`, with a real `relay_token` and
`token_store = "/data/store.db"`), then:

```bash
podman-compose up -d --build
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8765/healthz   # 200
```

The image builds on the host (don't ship it across architectures). The port is
published on loopback only; the device store persists in the `relay-data`
volume. Pair devices with `podman exec cmux-relay cmux-relay pair --config
/etc/cmux-relay/config.toml --tenant <id> --name phone` (tenant IDs from
`cmux-relay tenants list`).

## Agent (Mac)

The agent must run **in your GUI login session** to reach the per-user cmux
socket. Configure and install:

1. Copy `deploy/agent.example.toml` to `~/.config/cmux-bridge/agent.toml`; set
   `relay_url` (`wss://<your-domain>/agent/tunnel`), the client-cert paths, the
   server CA, and the same `relay_token` as the relay.
2. Install the LaunchAgent (it runs `cmux-bridge agent`):

   ```bash
   cp deploy/com.sodre90.cmux-bridge.plist ~/Library/LaunchAgents/   # edit REPLACE_ME paths
   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.sodre90.cmux-bridge.plist
   launchctl kickstart -k gui/$(id -u)/com.sodre90.cmux-bridge
   tail -f ~/Library/Logs/cmux-bridge.log
   ```

The agent reconnects automatically (exponential backoff, capped at 30s) if the
relay or network drops.

> **S5 note:** validate that the agent survives logout/login and sleep/wake — it
> lives in the GUI session and `KeepAlive` restarts it, but confirm on your
> machine after install.

## Agent client certificate

The Mac no longer needs a hand-rolled client cert. The relay generates its own
CA the first time it starts, and a new agent registers itself against it the
first time *it* starts:

1. Point `bootstrap_url` in `agent.toml` at the relay's no-mTLS bootstrap
   vhost, e.g. `https://cmux.example.com:8444/tenants/register` — that's
   `deploy/nginx-cmux-relay-bootstrap.conf`, which proxies only that one path.
   A brand-new agent has no client cert yet, so it can't reach the main mTLS
   vhost at all; this separate surface is how it gets one.
2. On first run — only while `client_cert` doesn't exist on disk yet — the
   agent generates a keypair, sends a CSR to `bootstrap_url`, and the relay
   mints a fresh tenant and signs the cert with CN `agent:<tenant-id>`
   against its own CA. The agent writes the returned cert and key to the
   paths `client_cert` / `client_key` point at, and prints the tenant ID it
   was assigned, for example:

   ```
   agent: registered as tenant 9f3a2c1e4b7d0a6f... (cert written to /Users/you/.config/cmux-bridge/agent.crt)
   ```

   Self-registration never touches `ca_cert` — that setting is unrelated: it
   pins the CA that signed nginx's own *server* certificate, not the relay's
   internal agent/device-signing CA. Leave it empty (the default) if nginx
   presents a publicly-trusted server cert (e.g. Let's Encrypt), which is the
   common case. Only set it if you've deliberately given nginx a self-signed
   or private-CA server cert.

3. Every run after that skips registration — the cert is already on disk. Hand
   the printed tenant ID to whoever will pair phones for this agent (see
   [Pair a device](#pair-a-device) below).

## Pair a device

Pairing is still operator-driven — run this on the **home server**, where the
relay owns the device token store. Every device belongs to exactly one
tenant, so first find the tenant ID (the Mac agent printed its own when it
self-registered; see [Agent client certificate](#agent-client-certificate)):

```bash
cmux-relay tenants list
```

Then mint a token for that tenant:

```bash
cmux-relay pair --tenant <id> --name phone
```

This prints a long-lived **device token**. Paste it into the app once; the app
sends it as `Authorization: Bearer <token>` on every request. The device's own
client cert (`.p12`) is still hand-rolled with `openssl` exactly as before
(see [android/README.md](../android/README.md#2-client-certificate-p12)) —
just sign it with the relay's own CA files (`~/.config/cmux-relay/ca.crt` /
`ca.key` by default) instead of a separately hand-rolled CA. List/revoke
devices and tenants:

```bash
cmux-relay devices                # list devices (tokens redacted)
cmux-relay devices revoke <token>
cmux-relay tenants list            # created/revoked per tenant
cmux-relay tenants revoke <id>     # devices stop authenticating immediately;
                                   # the agent is refused on its next reconnect
```

Self-service phone pairing (a QR code instead of hand-run `openssl`/`.p12`)
and end-to-end content encryption are tracked in a follow-up design, not yet
implemented — see
[`docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md`](../docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md).

## Edge: nginx mutual TLS

See `deploy/nginx-cmux-relay.conf`. Point your home-server DNS name at nginx,
require a client certificate (`ssl_verify_client on`), and `proxy_pass` to
`http://127.0.0.1:8765`. The `map $http_upgrade $connection_upgrade` block (http
context) is required for the agent tunnel and the terminal/event WebSockets.

## Push (optional)

To get "agent needs you" notifications:

1. Create a Firebase project and a service-account JSON key.
2. Put the key on the **home server** and set `fcm_project_id` +
   `fcm_credentials` in the relay config.
3. The app registers its FCM token via `POST /devices/register`. The relay opens
   its own `/events` subscription over the agent tunnel; when an agent raises a
   blocking prompt it sends a high-priority FCM data message to every paired
   device.

cmux redacts the actual prompt text in its event stream, so push triggers on
the Claude Code hook name (`Notification` covers permission prompts and idle
"waiting for input"; `AskUserQuestion` is an explicit blocking choice) rather
than on structured feed content. The notification body is enriched with the
workspace's live title + status preview (e.g. "Check ticket CB-33546: Claude
is waiting for your input") — the richest context cmux exposes for a prompt
whose text it hides. Tapping the notification deep-links to that workspace's
terminal (its one pane directly, or the sessions list when it has several) —
cmux never reports which pane raised the prompt, so pane-exact linking isn't
possible.

## API

The app's base URL is the relay's public domain (`https://<your-domain>`). All
routes require `Authorization: Bearer <device-token>`. A `503 {"error":
"agent_offline"}` means the Mac agent is not currently connected to the relay.

| Method | Path | Purpose |
|---|---|---|
| GET  | `/sessions` | list workspaces/terminals (normalized) |
| GET  | `/events` (WS) | agent feed + notifications; `needs_attention` flags blocking prompts |
| GET  | `/terminal/{id}` (WS) | replay + live output (down); input/paste/resize (up) |
| POST | `/feed/{id}/reply` | answer a prompt: `{kind, request_id, params}` |
| POST | `/devices/register` | store this device's FCM token: `{fcm_token}` |

Terminal frames carry cmux's `render_grid` (`format: "cmux.render-grid.v1"`)
verbatim; the client renders it as a styled cell grid.

> The exact `feed.*.reply` param names beyond `request_id` should be confirmed
> against a live prompt; the client sends cmux-native params under `params`.

## Safety

The bridge calls **only** read methods, terminal input/replay, and feed replies.
It never creates, closes, or restores workspaces/terminals. Tests use a fake
`cmux` binary and never touch the real socket.

## Licensing

The bridge is an independent work that communicates with cmux over its IPC/CLI.
It contains no cmux source. cmux is GPLv3; consuming its documented protocol over
IPC does not make this a derivative work.
