# cmux remote (Android)

A native Android client for [`cmux-bridge`](../bridge). It connects to the bridge
**behind your mTLS nginx edge** to list cmux sessions, drive a live terminal, and
answer agent prompts — with optional FCM push when an agent needs your attention.

The app depends only on the bridge's documented HTTP/WebSocket contract
(`GET /sessions`, `WS /events`, `WS /terminal/{id}`, `POST /feed/{id}/reply`,
`POST /devices/register`), never on cmux internals. Every request carries an
`Authorization: Bearer <device-token>` minted at pairing — no client TLS
certificate on the device side (only the Mac agent has one). Once paired,
request/response bodies and terminal frames are also end-to-end encrypted
between the phone and the Mac agent (X25519 ECDH + HKDF derived during
pairing), so the relay can route traffic but not read it.

## Requirements

- Android Studio (Narwhal 2025.1 or newer — the project builds with AGP 8.13)
  with the Android SDK (compileSdk 36, minSdk 26).
- JDK 21+. App code targets JVM 17, but a test-only dependency
  (`lazysodium-java`) ships Java 21 class files, so `testDebugUnitTest` needs a
  21+ runtime.
- A running `cmux-bridge` reachable through your nginx mTLS edge — see
  [`bridge/README.md`](../bridge/README.md). The phone must be able to reach that
  DNS name (over the internet or your LAN).

## Build & run

```bash
# from the repo root
cd android
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:testDebugUnitTest    # run the JVM unit tests
```

Or open the `android/` directory in Android Studio, let it sync, then Run `app`
on a device or emulator.

## First-run setup (Pairing screen)

On first launch — or any time the app has no bridge config yet — it opens the
**Pairing** screen instead of the sessions list. Pairing is entirely
self-service now: nothing to generate or paste by hand.

On the Mac, with the agent running (see
[`bridge/README.md` → Agent](../bridge/README.md#agent-mac)):

```bash
cmux-bridge pair-device --config ~/.config/cmux-bridge/agent.toml
```

This prints a QR code and, alongside it, a short code for manual entry.

### Option 1: scan the QR code

Grant the camera permission when prompted, then point it at the QR code
printed above. The QR payload carries the server URL, a one-time pairing
code, and the agent's public key — the app redeems the code with the relay,
generates its own X25519 keypair (kept in `EncryptedSharedPreferences`,
persisted thereafter), derives a shared secret with the agent, and stores the
bridge's base URL and the bearer token it's issued. No further input needed.

### Option 2: enter the server URL and code manually

No camera handy, or pairing remotely (e.g. over SSH into the Mac)? Tap
**"Enter server URL and code manually"** and fill in:

- **Server URL** — the same `https://` base the QR's `pair_url` uses (e.g.
  `https://cmux.example.com`).
- **Pairing code** — the short code `pair-device` printed next to the QR.

The app resolves the agent's public key via the relay's `GET
/devices/pair-info/{code}` and completes the exact same handshake as the QR
path from there.

### Confirm the fingerprint (both paths)

Before either path completes, the phone shows a short fingerprint and waits
for you to confirm it, while `pair-device` prints the same fingerprint on the
Mac and asks `Confirm? [y/N]:`. **Compare the two and only accept if they
match** — this is what stops the relay from swapping in its own key and
reading your traffic. Anything other than `y` on the Mac aborts the pairing.

Either way, once pairing succeeds the app moves to the sessions list; the
start screen on subsequent launches is the sessions list whenever a bridge
config is already present. A pairing code is single-use and expires (10
minutes) — if it's stale, generate a fresh one with `pair-device` and retry.

### Direct (Tailscale) mode and dual pairing

The Connections screen holds two independent slots — a relay pairing and a
direct (Tailscale) pairing — and you can fill both. When both are paired the
app tries the relay first and transparently fails over to direct, so pairing
both is the recommended setup. See
[`bridge/README.md`](../bridge/README.md) for the agent side.

## Push notifications (optional)

Push is **off by default and the app builds and runs without any Firebase
config.** To enable "an agent needs you" notifications:

1. Create a Firebase project, add an Android app with applicationId
   `com.sodre90.cmuxremote`, and download its `google-services.json`.
2. Place it at `android/app/google-services.json`. The Gradle build applies the
   `com.google.gms.google-services` plugin **only when that file exists**, so
   without it the build is unaffected.
3. Configure the bridge's FCM sender (`fcm_project_id` + `fcm_credentials`) — see
   [`bridge/README.md`](../bridge/README.md).

The app registers its FCM token via `POST /devices/register` on launch and on
token rotation. When the bridge sends a `type=attention` data message, the app
posts a high-priority notification that deep-links into the exact workspace
that needs attention — opening its terminal directly when it has a single
pane, or the sessions list (with that workspace's attention stripe visible)
when it has several.

## Out of scope (for now)

Creating/closing sessions, file-diff viewing, multiple Macs, biometric lock, and
tablet-specific layouts. The bridge only performs read methods, terminal
input/replay, feed replies, and workspace rename (setting a title) — it never
creates, closes, or restores workspaces/terminals.
