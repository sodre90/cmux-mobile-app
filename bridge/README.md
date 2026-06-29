# cmux-bridge

A small Go daemon that runs on your Mac next to [cmux](https://github.com/manaflow-ai/cmux)
and exposes its sessions, terminals, and agent feed over a simple authenticated
HTTP/WebSocket API — so a remote client (e.g. an Android app) can list sessions,
drive a terminal, and answer agent prompts from anywhere.

It speaks to cmux **only through the documented `cmux` CLI** (`cmux rpc` and
`cmux events`). It stores no cmux socket password and copies no cmux source — it
is an independent work that consumes cmux's IPC contract.

## Architecture

```
Android app
  │  HTTPS / WSS  (presents an mTLS client certificate)
  ▼
nginx on Home Assistant   (terminates mutual TLS; dedicated DNS name)
  │  HTTP / WS  (your LAN)
  ▼
cmux-bridge  (this daemon, a launchd LaunchAgent on the Mac)
  │  cmux rpc / cmux events  (local CLI; socket auto-resolved)
  ▼
cmux.app  (unchanged)
```

Security is layered: **mutual TLS at the nginx edge** + a **per-device bearer
token** checked by the bridge. The bridge binds loopback/LAN only and is never
exposed directly to the internet.

## Build

Requires Go 1.26+.

```bash
cd bridge
go build -o cmux-bridge ./cmd/cmux-bridge
go test ./...        # all tests run with no network and no real cmux
```

## Configure

Copy `config.example.toml` to `~/.config/cmux-bridge/config.toml` and edit. All
fields are optional; defaults shown:

```toml
listen      = "127.0.0.1:8765"   # keep on loopback/LAN; nginx is the public edge
cmux_bin    = "cmux"             # or /Applications/cmux.app/Contents/Resources/bin/cmux
token_store = "~/.config/cmux-bridge/devices.json"
# fcm_project_id  = "your-firebase-project-id"
# fcm_credentials = "~/.config/cmux-bridge/fcm-service-account.json"
```

## Run as a LaunchAgent

The bridge must run **in your GUI login session** to reach the per-user cmux
socket. See `deploy/com.sodre90.cmux-bridge.plist` (edit the two `REPLACE_ME`
paths), then:

```bash
cp deploy/com.sodre90.cmux-bridge.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.sodre90.cmux-bridge.plist
launchctl kickstart -k gui/$(id -u)/com.sodre90.cmux-bridge
tail -f ~/Library/Logs/cmux-bridge.log
```

> **S5 note:** validate that the agent survives logout/login and sleep/wake — it
> should, because it lives in the GUI session and `KeepAlive` restarts it, but
> confirm on your machine after install.

## Pair a device

```bash
cmux-bridge pair --name phone
```

This prints a long-lived **device token**. Paste it into the app once; the app
sends it as `Authorization: Bearer <token>` on every request. List and revoke:

```bash
cmux-bridge devices            # list (tokens redacted)
cmux-bridge devices revoke <token>
```

(A short numeric pairing-code exchange over `POST /pair` is a planned
enhancement; v1 uses direct token paste, which needs no shared state between the
`pair` and `serve` processes.)

## Edge: nginx mutual TLS

See `deploy/nginx-cmux-bridge.conf`. Point a dedicated DNS name at your Home
Assistant nginx, require a client certificate (`ssl_verify_client on`), and
`proxy_pass` to `http://<mac-lan-ip>:8765`. The `map $http_upgrade
$connection_upgrade` block (http context) is required for the terminal/event
WebSockets.

## Push (optional)

To get "agent needs you" notifications:

1. Create a Firebase project and a service-account JSON key.
2. Put the key on the Mac and set `fcm_project_id` + `fcm_credentials` in the
   config.
3. The app registers its FCM token via `POST /devices/register`; when an agent
   raises a blocking prompt the bridge sends a high-priority FCM data message.

Notification text is **redacted in cmux's event stream**, so push is driven off
structured feed items (a pending `permissionRequest` / `question` / `exitPlan`),
not notification bodies.

## API

All routes require `Authorization: Bearer <device-token>`.

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
