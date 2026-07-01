# cmux remote (Android)

A native Android client for [`cmux-bridge`](../bridge). It connects to the bridge
**behind your mTLS nginx edge** to list cmux sessions, drive a live terminal, and
answer agent prompts — with optional FCM push when an agent needs your attention.

The app depends only on the bridge's documented HTTP/WebSocket contract
(`GET /sessions`, `WS /events`, `WS /terminal/{id}`, `POST /feed/{id}/reply`,
`POST /devices/register`), never on cmux internals. Every request carries a
**client TLS certificate** (mTLS, terminated at nginx) and an
`Authorization: Bearer <device-token>`.

## Requirements

- Android Studio (Ladybug or newer) with the Android SDK (compileSdk 35).
- JDK 17 (the Gradle toolchain targets 17).
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

## First-run setup (Settings screen)

On first launch the app opens the **Settings** screen. You need three things:

### 1. Bridge base URL

The HTTPS URL of your nginx edge, e.g. `https://cmux.example.com`. This is the
public DNS name that terminates mutual TLS and proxies to the bridge on your LAN.

### 2. Client certificate (`.p12`)

nginx is configured with `ssl_verify_client on`, so the app must present a client
certificate signed by the CA nginx trusts (`ssl_client_certificate` — the same CA
you set up for the bridge edge). Generate a client cert and bundle it into a
password-protected PKCS#12:

```bash
# 1) client private key + CSR
openssl req -newkey rsa:2048 -nodes -keyout phone.key -out phone.csr -subj "/CN=phone"

# 2) sign it with your nginx client-auth CA (ca.crt / ca.key)
openssl x509 -req -in phone.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out phone.crt -days 825 -sha256

# 3) bundle key + cert into a .p12 (you'll be prompted for an export password)
openssl pkcs12 -export -inkey phone.key -in phone.crt -certfile ca.crt \
  -out phone.p12
```

Transfer `phone.p12` to the device (e.g. via a USB/file transfer or a private
channel — it contains a private key, keep it secret), then in Settings tap
**Import .p12**, pick the file, and enter the export password. The bytes are
stored in EncryptedSharedPreferences and never logged.

### 3. Device token

On the **home server** (the relay owns the device token store), find the
tenant this phone belongs to (the Mac agent printed its tenant ID when it
self-registered — see [`bridge/README.md`](../bridge/README.md#agent-client-certificate)):

```bash
cmux-relay tenants list
```

Then pair the device to mint a long-lived bearer token for that tenant:

```bash
cmux-relay pair --tenant <id> --name phone
```

Copy the printed token and paste it into the **Device token** field. Revoke later
with `cmux-relay devices revoke <token>`.

### Optional: server CA

Leave **Server CA (PEM)** blank if nginx presents a publicly-trusted server
certificate (e.g. Let's Encrypt). If nginx uses a private/self-signed **server**
certificate, paste that CA's PEM here so the app trusts the connection.

Tap **Save & connect**. The app moves to the sessions list; the start screen on
subsequent launches is the sessions list whenever a bridge config is present.

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

## Known gaps / assumptions

These were the points that could not be confirmed against a live cmux prompt while
building; verify them once connected and adjust if needed:

- **Feed reply `request_id`.** The bridge reads `request_id` from the reply body.
  cmux's exact reply param names are unconfirmed, so the inbox currently sends the
  feed id as `request_id` and params `{"decision":"approve"|"deny"}` for
  permission/exit-plan prompts and `{"answer": <text>}` for questions.
- **Terminal surface id.** A session's `id` from `GET /sessions` is used directly
  as the `{id}` in `WS /terminal/{id}`. Confirm sessions expose the surface id the
  terminal socket expects.
- **Render-grid colors.** The `cmux.render-grid.v1` style colors are decoded as
  `#rrggbb` / `#aarrggbb` hex strings. If cmux encodes colors differently (e.g.
  palette indices), the renderer's color parsing needs updating.

## Out of scope (for now)

Creating/closing sessions, file-diff viewing, multiple Macs, biometric lock, and
tablet-specific layouts. The bridge only ever performs read methods, terminal
input/replay, and feed replies — it never mutates session lifecycle.
