# Design: Android client for cmux via a Mac-side bridge

- **Date:** 2026-06-29
- **Status:** Approved design — ready for implementation planning
- **Author:** perdos

## 1. Context

`cmux` (manaflow-ai/cmux, GPLv3, ~23k★) is a native macOS app (Swift + ghostty)
that orchestrates terminal and AI-agent sessions. It already ships a complete
mobile-remote system, but the **official mobile client is iOS-only** and its
remote transport is **Tailscale-only by policy**.

Relevant facts established while exploring the source:

- cmux exposes a documented **local control socket** (v2 RPC). The CLI (`cmux
  rpc <method>`, `cmux events`, `cmux capabilities`) is a thin wrapper over it,
  authenticated with a socket password.
- The mobile RPC surface exists: `mobile.workspace.list`,
  `mobile.terminal.create/input/paste/replay/viewport`,
  `mobile.events.subscribe`, `mobile.host.status`, plus agent-feed methods
  (`feed.list`, `feed.question.reply`, `feed.permission.reply`,
  `feed.exit_plan.reply`) and `notification.*`.
- In cmux's own design the transport is abstracted (`CmxByteTransport`) and the
  **security boundary is the account (Stack Auth) token, not the network** — the
  Tailscale requirement is policy in `remotes add`, not a protocol dependency.

### Goal

A **Kotlin/Android app** that, from anywhere over the internet, lets the user:

1. List their cmux sessions (workspaces/terminals).
2. Interact with a live terminal — see output, type, switch sessions.
3. Monitor and respond to AI-agent prompts — get notified, read the
   question/output, and approve / deny / reply.

### Non-goals (v1)

- iOS client (an official iOS app already exists; the bridge API is
  client-agnostic, so an iOS or web client can be added later).
- Multi-user / team support. This is a single-user, own-devices tool.
- Reimplementing cmux's native mobile protocol, ghostty pixel-perfect
  rendering, Stack Auth, or Tailscale.
- Modifying cmux itself. We only consume its documented control socket.

## 2. Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Architecture | **Mac-side bridge** wrapping cmux's local socket | Smallest, fully controlled; rides cmux's *documented* contract instead of its evolving internal mobile protocol. |
| Transport | **mutual-TLS nginx** (existing Home Assistant) reverse-proxying to the bridge | User already runs internet-reachable mTLS nginx; no Tailscale, no relay, no router changes. |
| Bridge language | **Go** | Single static binary, simple launchd LaunchAgent, strong WS/concurrency, matches cmux's own `cmuxd-remote`. |
| Android stack | **Kotlin + Jetpack Compose** | Native, first-class mTLS + WebSocket + FCM support. |
| Push | **Firebase Cloud Messaging (FCM)** | Most reliable Android background push. Accepted Google/Firebase dependency. |
| v1 use case | **Interactive terminal + agent approvals** | The two highest-value flows for an AI-agent workflow. |

## 3. Architecture

```
Android app
  │  HTTPS / WSS  (presents mTLS client cert)
  ▼
nginx on Home Assistant  (terminates mutual TLS; dedicated DNS name)
  │  HTTP / WS  (LAN)
  ▼
cmux-bridge  (Go daemon on the Mac, launchd LaunchAgent)
  │  Unix domain socket, v2 RPC (socket password)
  ▼
cmux.app  (unchanged)
```

Components:

- **cmux.app** — unchanged. Source of truth for sessions, terminals, agent feed.
- **cmux-bridge** — long-running Go daemon. One persistent authenticated
  connection to cmux's Unix socket; serves a small JSON + WebSocket API on a
  LAN/loopback port. Stateless except for device tokens and FCM registrations.
- **nginx (Home Assistant)** — existing edge. Adds a vhost for a dedicated DNS
  name, requires a client certificate (mTLS), and reverse-proxies to the bridge.
- **Android app** — thin client of the bridge API.

## 4. Security model (layered)

1. **Edge (mTLS):** nginx requires a valid client certificate. Primary gate.
2. **App credential:** the bridge additionally requires a **per-device bearer
   token**. First launch performs a one-time pairing: the bridge shows a
   short code (e.g., via `cmux-bridge pair`), the app submits it once and
   receives a long-lived device token. The bridge never trusts network position
   alone.
3. **Transport:** WSS end-to-end to nginx. The LAN hop (HA ↔ Mac) may be plain
   HTTP on the trusted LAN, or TLS if preferred.
4. **Exposure:** the bridge binds to loopback/LAN only and is never published
   directly to the internet. The cmux socket password stays on the Mac (read
   from cmux Settings / `CMUX_SOCKET_PASSWORD`).
5. **Revocation:** device tokens are listable and revocable (`cmux-bridge
   devices`), so a lost phone can be cut off without rotating the cert.

## 5. The bridge (Go, Mac side)

### 5.1 Process & lifecycle

- Runs as a **user LaunchAgent** (must be in the logged-in user session to reach
  the per-user socket and the running GUI app).
- Config file (`~/.config/cmux-bridge/config.toml`): listen address/port, cmux
  socket path override, FCM service-account key path + project id, allowed device
  tokens store path.
- Subcommands: `serve` (default), `pair` (issue a device token), `devices`
  (list/revoke).

### 5.2 cmux connection

- Connects to cmux's Unix socket and speaks the **v2 RPC** protocol directly,
  authenticating with the socket password. One persistent connection multiplexes
  RPC calls and the event subscription.
- **Spike (S1):** confirm the exact socket framing for a direct client. If
  direct framing is impractical for v1, fall back to spawning `cmux rpc
  <method> <json>` for control calls and one long-lived `cmux events` process
  for the event stream. Direct socket is preferred for keystroke latency.

### 5.3 API surface (bridge → app)

- `POST /pair` — exchange a pairing code for a device token.
- `GET /sessions` — list workspaces/terminals (`mobile.workspace.list`).
- `WS /terminal/{id}` — bidirectional terminal:
  - down: output stream + initial scrollback (`mobile.terminal.replay`),
    resize/viewport acks.
  - up: keystrokes (`mobile.terminal.input`), paste (`mobile.terminal.paste`),
    resize (`mobile.terminal.viewport`).
- `WS /events` — agent feed + notifications (`feed.list` + subscribed events).
- `POST /feed/{id}/reply` — answer an agent question / permission / plan
  (`feed.question.reply`, `feed.permission.reply`, `feed.exit_plan.reply`).
- `POST /devices/register` — register/refresh the app's FCM token.

All endpoints require a valid device token (in addition to the mTLS gate at
nginx).

## 6. The Android app (Kotlin + Compose)

### 6.1 Stack

- Kotlin, Jetpack Compose, Coroutines/Flow.
- OkHttp for HTTPS + WebSocket, configured with a custom `KeyManager`/
  `TrustManager` for the **mTLS client certificate** (user imports a PKCS#12
  bundle into the app's keystore) and the server CA.
- Firebase Messaging SDK (`google-services.json`).

### 6.2 Screens

- **Session list** — workspaces/terminals with status + unread-agent badges.
- **Terminal** — a VT/ANSI terminal widget fed by `WS /terminal/{id}`; on-screen
  input (keyboard, common keys, paste), scrollback, resize reporting.
- **Agent inbox** — pending agent questions/permissions/plans with
  Approve / Deny / Reply actions, deep-linkable from a push notification.

### 6.3 Terminal rendering

- **Spike (S2):** determine whether the bridge stream is **raw PTY bytes** or
  **ghostty cell snapshots** (cmux's `CMUXMobileCore` references "Ghostty
  snapshot models").
  - If raw bytes → embed a proven ANSI/VT emulator (e.g. Termux
    `terminal-view`, GPL — acceptable).
  - If cell snapshots → render a styled cell grid in Compose.
- The bridge normalizes whichever shape into a single documented stream format
  so the app depends only on the bridge, not on cmux internals.

## 7. Push notifications (FCM)

Flow:

1. App obtains an FCM registration token; sends it to the bridge
   (`POST /devices/register`).
2. Bridge stores tokens per device.
3. When cmux emits an "agent needs you" signal (a feed question/permission event
   or relevant `notification.*` event), the bridge sends a **high-priority data
   message** via the **FCM HTTP v1 API**, authenticated with a Google
   service-account key configured on the bridge.
4. App receives the push (foreground or background), posts a system notification;
   tapping deep-links to the agent inbox or the relevant terminal.

Setup cost (documented in the repo README): a Firebase project, a service-account
JSON key on the bridge, and `google-services.json` in the app.

## 8. Key scenarios (data flow)

- **Attach terminal:** app opens `WS /terminal/{id}` → bridge issues
  `mobile.terminal.replay` (scrollback) then streams output events; keystrokes
  flow up as `mobile.terminal.input`.
- **Approve an agent prompt:** agent blocks → cmux feed event → bridge → FCM →
  phone notification → user opens inbox, reads, taps Approve → `POST
  /feed/{id}/reply` → `feed.permission.reply` → agent unblocks.
- **Reconnect:** app re-opens WS; bridge re-subscribes and replays current
  terminal state so the view is consistent after network drops.

## 9. v1 scope vs. later

**v1:** bridge (`serve`/`pair`/`devices`), session list, interactive terminal,
agent inbox with reply, FCM push, mTLS + device-token auth, launchd install +
nginx config docs.

**Later:** iOS/web client on the same bridge API; create/close sessions; file
diff viewing (`cmux diff`); multiple Macs; richer notification rules.

## 10. Risks & spikes

| ID | Risk / unknown | Plan |
|---|---|---|
| S1 | Exact v2 socket framing for a direct Go client | Spike against the local socket; fall back to `cmux rpc`/`cmux events` subprocesses if needed. |
| S2 | Terminal output shape (raw bytes vs ghostty snapshots) | Inspect `mobile.terminal.replay`/event payloads; pick emulator vs grid renderer. |
| S3 | "Agent needs you" event identification | Map which feed/notification events represent a blocking prompt vs. noise. |
| S4 | mTLS client cert handling on Android (PKCS#12 import, OkHttp KeyManager) | Spike a minimal authenticated WSS call early. |
| S5 | LaunchAgent reaching the GUI app's per-user socket reliably across login/sleep | Validate session/persistence behavior. |

## 11. Licensing

The bridge and the Android app are **independent works** that communicate with
cmux over its IPC socket; that does not make them derivative works of GPLv3
cmux. This holds only as long as we **do not copy cmux source** — all bridge/app
code is written fresh. cmux's protocol/CLI contract may be referenced as
documentation.
