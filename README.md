# cmux remote

Reach your Mac's [cmux](https://github.com/manaflow-ai/cmux) agent sessions from
your phone — list workspaces, drive a live terminal, and answer agent prompts
from anywhere, **without opening a single inbound port on the Mac**.

The Mac *dials out* to a small relay on your home server; your phone talks to the
relay behind an mTLS edge. Everything speaks to cmux **only through its
documented CLI** (`cmux rpc` / `cmux events`) — no cmux source is copied and no
cmux socket password is stored.

```
 ┌─────────────┐      HTTPS / WSS        ┌────────────────────┐     WSS dial-out
 │  Android app │ ───  (device cert)  ──▶ │  nginx  (mutual TLS │ ◀── (mac-agent  ─┐
 │  (Compose)   │ ◀──  Bearer token   ─── │  public DNS name)   │      client cert) │
 └─────────────┘                         └─────────┬──────────┘                   │
                                          HTTP / WS │ (loopback)                   │
                                                    ▼                              │
                                              ┌───────────┐   one persistent       │
                                              │ cmux-relay │   yamux-over-WSS  ─────┘
                                              │ (home srv) │   tunnel
                                              └─────┬─────┘
                                                    │ routes by client-cert CN
                                                    ▼
                                       ┌──────────────────────────┐   cmux rpc /
                                       │  cmux-bridge agent (Mac)  │ ─ cmux events ─▶ cmux.app
                                       └──────────────────────────┘                  (unchanged)
```

When the Mac is offline the relay returns `503 {"error":"agent_offline"}`; when it
reconnects (automatic, backoff-capped), everything resumes.

## Components

| Directory | What it is | Stack | Guide |
|---|---|---|---|
| [`android/`](android/) | The phone client — sessions list, live terminal, agent inbox, optional push | Kotlin · Jetpack Compose · `com.sodre90.cmuxremote` | [android/README.md](android/README.md) |
| [`bridge/`](bridge/) | Two Go binaries: **`cmux-relay`** (home-server rendezvous, auth, push) and **`cmux-bridge agent`** (runs on the Mac, dials the relay) | Go 1.26 | [bridge/README.md](bridge/README.md) |

Both bridge binaries live in `bridge/cmd/`; deployment templates (systemd unit,
launchd plist, nginx vhosts, container files, example configs) are in
`bridge/deploy/`.

## How it fits together

1. **`cmux-relay`** runs on your home server behind nginx with `ssl_verify_client
   on`. It owns device pairing/tokens, routes requests by client-cert CN, and
   (optionally) sends FCM push. It binds loopback only — nginx is the sole public
   surface.
2. **`cmux-bridge agent`** runs in your Mac's GUI login session next to cmux. It
   opens **one outbound WSS tunnel** to the relay and serves the bridge HTTP/WS
   API over it. No port-forwarding, no inbound exposure on the Mac.
3. **The Android app** connects to the relay's public DNS name, presenting a
   client TLS certificate (mTLS) plus a per-device bearer token, and renders
   cmux's `render_grid` cell grid live.

## Quick start

Set it up in this order — each step links to the detailed guide:

1. **Relay + nginx edge** on the home server → [bridge/README.md → Relay](bridge/README.md#relay-home-server)
2. **Agent** on the Mac (LaunchAgent, dials the relay) → [bridge/README.md → Agent](bridge/README.md#agent-mac)
3. **Pair the phone** (mint a device token on the relay) → [bridge/README.md → Pair a device](bridge/README.md#pair-a-device)
4. **Install & configure the app** (base URL, client `.p12`, device token) → [android/README.md → First-run setup](android/README.md#first-run-setup-settings-screen)
5. **Push notifications** (optional, Firebase) → both READMEs' *Push* sections

### Build

```bash
# Bridge (Go 1.26+): from bridge/
go build -o cmux-relay  ./cmd/cmux-relay     # home server
go build -o cmux-bridge ./cmd/cmux-bridge    # Mac (agent mode)
go test ./...                                # no network, no real cmux

# Android app: from android/
./gradlew :app:assembleDebug                 # debug APK
./gradlew :app:testDebugUnitTest             # JVM unit tests
```

## Security model

Defense in depth, all the way to the cmux socket:

- **Mutual TLS at the nginx edge** — both the Mac agent and every device present
  client certificates signed by one CA.
- **Client-cert CN routing** in the relay — `CN=mac-agent` may open the tunnel;
  device CNs are reverse-proxied as API calls.
- **Per-device bearer token** — minted by `cmux-relay pair`, revocable, sent as
  `Authorization: Bearer …` on every request.
- **`X-Relay-Token` shared secret** — injected by the relay so the agent only
  honors relay-originated requests.
- The relay binds loopback only; **the Mac agent has no listening port at all.**

## What the app does

- **Sessions list** — workspaces and their terminal panes, normalized from cmux.
- **Live terminal** — renders cmux's `cmux.render-grid.v1` grid with styles,
  colors, cursor, and scrollback; fit-to-width sizing, pinch-to-zoom, a word-wrap
  toggle, text selection, a compact `←↑↓→` D-pad, an Enter key, and DECCKM-aware
  cursor keys. Input/paste/resize go upstream; replay + live output come down.
- **Agent inbox** — answer blocking prompts (permission requests, plan approvals,
  questions) via `POST /feed/{id}/reply`.
- **Optional push** — FCM "an agent needs you" notifications, off by default and
  requiring no Firebase config to build.

The bridge performs **only** read methods, terminal input/replay, and feed
replies — it never creates, closes, or restores workspaces or terminals.

## Repository layout

```
android/              Jetpack Compose client (com.sodre90.cmuxremote)
bridge/               Go module: github.com/sodre90/cmux-bridge
  cmd/cmux-relay/       home-server rendezvous daemon
  cmd/cmux-bridge/      Mac agent (dials the relay)
  internal/             server, cmux CLI client, relay, tunnel, auth, push, …
  deploy/               systemd, launchd, nginx, container, example configs
docs/                 design specs and implementation plans
THIRD_PARTY_LICENSES/ bundled-asset licenses (e.g. JetBrains Mono)
```

## Relationship to cmux & licensing

This project is an **independent work** that communicates with
[cmux](https://github.com/manaflow-ai/cmux) over its documented IPC/CLI contract.
It contains no cmux source. cmux is GPLv3; consuming its documented protocol over
IPC does not make this a derivative work.
