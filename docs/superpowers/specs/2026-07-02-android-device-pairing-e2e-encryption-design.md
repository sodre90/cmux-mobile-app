# Android device pairing & end-to-end content encryption client

## Context

The Go side of self-service device pairing + E2E content encryption
([`2026-07-01-device-pairing-e2e-encryption-design.md`](2026-07-01-device-pairing-e2e-encryption-design.md),
implemented in `2026-07-02-device-pairing-e2e-encryption.md`) is fully merged
into the `worktree-multi-tenant-relay-transport` branch: the relay exposes
`POST /agent/pairing-code`, `GET /agent/pairing-code/{code}`, and
`POST /devices/pair`; `cmux-bridge agent` encrypts HTTP bodies and terminal/
events WebSocket frames whenever a paired device's shared secret is on file;
`cmux-bridge pair-device` renders the pairing QR. That spec explicitly
deferred "the Android/Kotlin side — camera QR scanner, Kotlin crypto
package, wiring into the app's HTTP/WS client" as "a separate, later spec."
This is that spec.

**This is the last piece needed to make the Go-side work usable.** Per the Go
plan's "Accepted lockout" constraint, any phone still using the old
`cmux-relay pair` + manually-imported `.p12` flow loses relay access the
moment the Go side ships — it cannot regain access until this Android work
also ships.

## Decisions made (confirmed with user)

- **Setup flow: scan-to-pair only.** The existing manual base-URL/token/.p12/
  server-CA `SettingsScreen` is replaced outright by a QR-scanning
  `PairingScreen`, not kept as a fallback. This matches the Go plan's "full
  enforcement, no coexistence" stance.
- **Migration: wipe old settings and force re-pair, once, on upgrade.** The
  new `Settings` class drops the `clientP12`/`p12Password` Kotlin properties
  entirely, but an upgrading install's underlying `EncryptedSharedPreferences`
  file still has the old raw keys on disk. On app start, `Settings` checks
  for the presence of the legacy `client_p12_b64` key directly (via
  `prefs.contains(...)`, independent of any Kotlin property); if found, it
  clears the entire preferences file once and the user lands on
  `PairingScreen`. This check is self-terminating: once cleared, the legacy
  key is never written again by any code path, so it does not fire on
  subsequent launches and does not touch a freshly-paired `Session`. No
  best-effort migration of the old token — it's invalid against the new Go
  side regardless (device rows now require a `device_pubkey`).
- **Scope: one spec, full rollout.** Pairing, HTTP body encryption, terminal
  WS encryption, and events WS encryption all ship together, matching how
  the Go side shipped them together. A partial client (e.g. encrypted HTTP
  but plaintext terminal) would be a confusing intermediate state with mixed
  security guarantees.
- **QR scanning: ML Kit Barcode Scanning + CameraX.** `com.google.mlkit:
  barcode-scanning` (on-device, no Play Services account/network dependency
  for the model) bound to a `CameraX` `ImageAnalysis` use case, restricted to
  `QR_CODE` format.
- **Crypto library: BouncyCastle, not Tink.** Tink was the first candidate
  considered and rejected after checking its docs: Tink's public `Aead`
  interface generates/manages its own nonces internally and prepends its own
  framing to ciphertext — there is no supported way to hand it a
  caller-constructed nonce. This plan's wire format requires exactly that
  (the direction+counter nonce is constructed by the caller and its counter
  component is transmitted separately from the ciphertext). BouncyCastle's
  lightweight API directly supports `draft-irtf-cfrg-xchacha-03`
  XChaCha20-Poly1305 with explicit 24-byte nonces, plus raw X25519 ECDH
  (`X25519Agreement`) and HKDF-SHA256 (`HKDFBytesGenerator`) — covering all
  three primitives this spec needs with full byte-level control, which is
  required to reproduce the Go side's fixed test vectors exactly.
- **Wiring point: OkHttp `Interceptor` + thin WS wrapper.** An
  `E2eInterceptor` transparently encrypts/decrypts `BridgeClient` request/
  response bodies (mirrors the existing `BearerInterceptor` pattern already
  in `Mtls.kt`). `TerminalSocket`/`EventsSocket` gain an encode/decode step
  around their existing `send()`/`onMessage()`. `BridgeClient`,
  `TerminalSocket`, `EventsSocket`, and their ViewModels are otherwise
  unchanged — encryption is invisible above the interceptor/socket-wrapper
  layer.
- **Server TLS trust: system trust store only (assumption).** The
  now-deleted `serverCaPem` manual field is not replaced by anything (no
  CA-pinning, no CA field added to the QR payload). This assumes the relay
  presents a publicly-trusted certificate (e.g. Let's Encrypt), matching the
  current home-server deployment. **This is a recorded assumption, not a
  confirmed fact** — if the relay is actually running a self-signed server
  certificate, this spec needs a revision (most likely: add a CA
  fingerprint or PEM to the `pairingQR` struct on the Go side) before
  implementation.
- **Replay-counter gate: sliding window, on both phone and agent.** The Go
  side's committed `internal/e2e.Store` receive gate
  (`ValidateRecvCounter`/`CommitRecvCounter`) is strictly monotonic: it
  rejects any counter `n <= last_seen`. Counters are shared across all of a
  device's HTTP requests and every WS connection (by the Go plan's own
  "durable, never reset" design, to prevent nonce reuse across reconnects).
  But a single phone has **three concurrent agent→device channels** — HTTP
  responses, the `/terminal` WS, and the `/events` WS — all drawing from the
  same agent-side send counter, then delivered over independent transports
  with no cross-channel ordering guarantee. Strict monotonicity will reject
  correctly-decryptable, non-replayed frames purely because they arrived out
  of counter order (e.g. `InboxViewModel` holds `/events` open while also
  issuing `/feed/pending` HTTP calls; `TerminalScreen` runs the `/terminal`
  firehose). The fix, applied identically on both sides: replace the strict
  gate with a fixed-size sliding acceptance window (RFC 6479-style) — track
  the highest counter seen plus a bitmask of the last W=64 counters; accept
  `n` iff `n > highest - W` and not already marked seen. This preserves
  replay rejection (a truly reused counter is still rejected) while
  tolerating benign cross-channel reordering. The Go-side half of this fix
  is a small, separate amendment task in this spec's implementation plan,
  touching only `bridge/internal/e2e/store.go` and `store_test.go` — no
  other already-shipped Go file is reopened.

## Architecture & data flow

New Kotlin package `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/`:

- **`Identity.kt`** — generates (once) and persists the phone's own X25519
  identity keypair. Stored the same way `Settings.kt` stores today's
  secrets: `EncryptedSharedPreferences` (AES256_GCM master key, AES256_SIV
  key encryption, AES256_GCM value encryption) — not a raw file on disk like
  the Go agent's `~/.config/cmux-bridge/identity.key`, since Android app
  private storage plus platform-backed encryption is the idiomatic
  equivalent.
- **`Cipher.kt`** — BouncyCastle-backed primitives: X25519 ECDH
  (`X25519Agreement`), HKDF-SHA256 shared-secret derivation with
  `buildInfo(pubA, pubB)` sorting the two 32-byte public keys
  lexicographically before concatenation into the info string (must be
  byte-identical to the Go side's `buildInfo`, since both peers derive the
  same secret independently), and XChaCha20-Poly1305 seal/open using the
  `Nonce(direction, counter)` construction (24 bytes: `n[15] = direction`,
  `n[16:24] = big-endian counter`; `DirAgentToDevice = 0x00`,
  `DirDeviceToAgent = 0x01`) — a direct Kotlin port of
  `bridge/internal/e2e/cipher.go`.
- **`Session.kt`** — the phone's single paired-device session state: the
  derived shared secret, a durable monotonic send counter, and the sliding-
  window receive-acceptance state described above. Persisted in
  `EncryptedSharedPreferences` (unlike the Go agent, the phone only ever
  pairs with one agent at a time, so this is a single record, not a map
  keyed by device ID).
- **`Envelope.kt`** — HTTP body envelope encode/decode (JSON
  `{"v":1,"n":<counter>,"ct":"<base64 ciphertext+tag>"}`, matching
  `envelope.go`) and WS binary frame encode/decode (`[8-byte big-endian
  counter][ciphertext+tag]`, matching `frame.go`).

New package `data/pairing/`:

- **`PairingClient.kt`** — given a parsed `PairingQr` (`pair_url, code,
  agent_pubkey, expires_at, tenant_id`), generates the phone's e2e keypair
  (via `Identity`), POSTs `{code, name, device_pubkey}` to `pair_url`,
  receives `{token, tenant_id}`, derives the shared secret via
  `Cipher.deriveSharedSecret(myPriv, agentPub)`, and persists the result:
  the bearer token and base URL into `Settings` (base URL derived from
  `pair_url` by stripping the `/devices/pair` suffix), the shared secret and
  fresh counter state into `Session`.

New UI: **`ui/pairing/PairingScreen.kt`** (Compose + CameraX preview + ML Kit
`BarcodeScanning` client) replaces `ui/settings/SettingsScreen.kt` as the
route `CmuxNavHost` shows when `container.settings.bridgeConfig() == null`
(today `Routes.SETTINGS` already plays exactly this role — the route name
and its position as the nav graph's conditional start destination are
unchanged, only its screen composable and backing ViewModel are replaced).
`SettingsScreen`'s manual URL/token/.p12-import/CA-PEM fields, and
`Mtls.kt`'s `.p12`-loading `keyManagers()` function and `clientP12`/
`p12Password` config plumbing, are deleted entirely — the phone no longer
presents a client certificate (nginx's `ssl_verify_client optional`, from
the Go side's Task 8, already accommodates this).

**Pairing data flow:** user opens the app for the first time (or after the
migration wipe) → `PairingScreen` requests camera permission → live preview
with ML Kit decoding QR codes → on decode, parse+validate the JSON payload
(including client-side `expires_at` check) → `PairingClient.pair(qr)` on
`Dispatchers.IO` → success persists token + session and navigates to
`Routes.SESSIONS`; failure shows an inline error with a "scan again" action.

**Runtime data flow (post-pairing):** `AppContainer.httpClient()` builds one
`OkHttpClient` per `BridgeConfig` (as today) but adds `E2eInterceptor
(session)` whenever a paired `Session` exists. Every `BridgeClient` call
transparently gets its request body encrypted and response body decrypted.
`TerminalSocket.send()`/`EventsSocket`'s `onMessage` switch from JSON text
frames to encrypted binary frames, encoding/decoding through `Session`
before the existing JSON (de)serialization step.

## Wiring details

**`E2eInterceptor`** (`data/e2e/E2eInterceptor.kt`, `okhttp3.Interceptor`,
added to the `OkHttpClient.Builder` alongside the existing
`BearerInterceptor`):
- Outgoing: if the request carries a body, read it, encrypt via
  `session.encryptBody(plaintext)`, replace the request body with the
  resulting envelope JSON (`Content-Type: application/json` unchanged from
  today).
- Incoming: if the response carries a body, read it, decrypt via
  `session.decryptBody(envelope)`, replace the response body with the
  decrypted plaintext before returning it up the chain. GET requests
  (`sessions()`, `pendingFeed()`) have no outgoing body to encrypt but their
  responses are still decrypted — the two directions are handled
  independently per request, matching the Go server's symmetric treatment.
- A decrypt failure (wrong/missing session, corrupted ciphertext, counter
  outside the acceptance window) throws an `IOException` that `BridgeClient`
  callers already handle as a request failure — no new exception type is
  introduced at the `BridgeClient` API surface, keeping ViewModels
  unchanged.

**`TerminalSocket` / `EventsSocket`**: `send(TerminalUp)` serializes to JSON
as today, then `session.encryptFrame(bytes)` before `ws.send(ByteString)`
(binary send, replacing today's text send). The `WebSocketListener`'s
`onMessage(WebSocket, ByteString)` binary overload (replacing today's text
`onMessage(WebSocket, String)`) runs `session.decryptFrame(bytes)` then the
existing JSON decode. A decrypt failure on a single frame logs and drops it
(matching the existing `runCatching`-and-drop pattern for malformed JSON)
rather than tearing down the socket connection — a single corrupted or
out-of-window frame shouldn't kill an otherwise-healthy session.

**Sliding-window receive gate** (`Session.kt`): track `highestSeen: Long`
and a `BooleanArray`/bitset of the last `W = 64` counter slots. `accept(n)`:
reject if `n <= highestSeen - W` (too old, outside window) or if slot
`n mod W` is already marked for that counter value (replay); otherwise mark
it accepted, and if `n > highestSeen`, advance `highestSeen` to `n` and
clear any slots that scrolled out of the new window. Applied identically —
same window size, same algorithm — on the Go agent side (see amendment
below), so both peers tolerate the same degree of cross-channel reordering.

## Error handling

- **Camera permission denied**: `PairingScreen` shows a rationale with a
  button to open the app's system settings page; scanning is unavailable
  until granted. No manual-entry fallback (per the scan-to-pair-only
  decision) beyond the QR's own printed `code` string, which the Go
  `pair-device` CLI already displays as text — a future iteration could add
  manual code entry, but it's out of scope here since the CLI's neighborly
  text fallback still requires *some* UI to enter it, and none exists yet.
- **Malformed/foreign QR code** (not valid JSON, missing required fields):
  inline error, camera scanning resumes automatically — the user may have
  scanned an unrelated QR code.
- **Expired code** (`expires_at` in the past, checked client-side before the
  network call, and via the server's `410 pairing_code_invalid` if the
  client clock is skewed): inline error directing the user to generate a
  fresh code from the Mac (`cmux-bridge pair-device` mints a new one each
  run; codes are single-use and time-limited server-side).
- **Network/relay unreachable during `POST /devices/pair`**: inline error
  with a retry action that re-POSTs the same still-valid code (idempotent
  from the phone's perspective as long as the code hasn't already been
  redeemed by a prior successful attempt — if a prior attempt actually
  succeeded server-side but the phone never saw the response, retry gets
  a `410` and the user is told to re-scan, matching the Go store's
  single-outcome redemption semantics).
- **Decrypt failure post-pairing** (`E2eInterceptor`, `TerminalSocket`,
  `EventsSocket`): treated as a transient/fatal error at the existing
  failure-handling layer for each — `BridgeClient` callers see a request
  exception (existing retry/error UI applies unchanged); WS listeners drop
  the single frame and log, per above.
- **Re-pairing after relay-side revocation**: if the agent's session store
  no longer recognizes the phone's device ID (e.g. the operator ran
  `cmux-relay tenants revoke` or the agent's local `e2e.Store` was wiped),
  the phone's requests start failing decryption/`409 not_paired`
  consistently. No special client-side detection beyond normal error
  surfacing — the user re-runs the pairing flow from scratch (the app has
  no "you were unpaired" push signal, since that would itself require the
  now-broken channel).

## Testing

- **`data/e2e/CipherTest.kt`**: reproduces `bridge/internal/e2e/
  cipher_test.go`'s fixed vectors (`TestFixedCipherVector`,
  `TestDeriveSharedSecretFixedVector`) byte-for-byte — same hardcoded keys,
  plaintext, expected ciphertext/shared-secret hex. This is the
  cross-language interop proof the Go spec's Testing section anticipated
  ("fixed test vectors... that the future Kotlin implementation must
  reproduce byte-for-byte").
- **`data/e2e/SessionTest.kt`**: sliding-window accept/reject cases —
  in-order accept, out-of-order-but-within-window accept, exact replay
  reject, stale/outside-window reject, window-advance behavior as new high
  counters arrive.
- **`data/e2e/EnvelopeTest.kt`** / **`FrameTest.kt`**: round-trip encode/
  decode against fixed vectors, wrong-key rejection, wrong-direction
  rejection (mirrors `cipher_test.go`'s `TestOpenRejectsWrongDirection`).
- **`data/pairing/PairingClientTest.kt`**: OkHttp `MockWebServer` (already a
  test dependency via `libs.okhttp.mockwebserver`) simulating `/devices/
  pair` success, `410 pairing_code_invalid`, and network-error responses.
- **`data/e2e/E2eInterceptorTest.kt`**: `MockWebServer`-backed test that a
  request body is encrypted on the wire and a response body is decrypted
  before reaching `BridgeClient`, plus the decrypt-failure-throws-IOException
  case.
- **Instrumented/manual**: camera scanning itself and the full pair →
  encrypted-terminal-session flow are verified manually against a real
  `cmux-bridge pair-device` CLI session on the home-server relay — no
  automated end-to-end test, matching the Go side's own testing approach
  (`internal/e2e`'s Testing section: "No end-to-end test against real
  hardware/relay is planned").

## Go-side amendment (scoped addendum to this spec)

`bridge/internal/e2e/store.go`: replace `ValidateRecvCounter`'s and
`CommitRecvCounter`'s strict `n <= RecvCounter → reject` logic with the same
sliding-window algorithm as the phone's `Session.kt` (`W = 64`, bitset of
recently-seen counters within the window, reject only exact replays or
counters older than the window). `deviceSession`'s `RecvCounter`/
`RecvCounterSet` fields are replaced with `RecvHighest uint64` and a
persisted window bitset (e.g. `RecvWindow []bool` of length `W`, or a
`uint64` bitmask if `W <= 64`). Existing tests in `store_test.go` covering
strict rejection are updated to cover the window's accept/reject boundary
instead. This is the only Go file touched by this Android-focused spec;
every other Go file from the prior implementation cycle is untouched.

## Explicit non-goals (this spec)

- **Manual pairing-code entry** as a fallback to camera scanning (see Error
  handling above) — a possible future addition, not required now.
- **Multi-agent / multi-relay pairing** on one phone — `Session.kt` holds a
  single paired-device record, matching today's single-`BridgeConfig`
  app model. Re-pairing against a different agent overwrites the existing
  session.
- **Server TLS certificate pinning / private-CA support** — see the "Server
  TLS trust" decision above; this is an explicit scope cut, not an
  oversight, contingent on the recorded assumption that the relay uses a
  publicly-trusted certificate.
- **Forward secrecy / key rotation** — inherited unchanged from the Go
  spec's own non-goals; the phone's shared secret is as durable as the
  agent's.
- **Biometric lock / app-level re-auth** — already listed as a future item
  in `android/README.md`'s existing "not yet built" list; unrelated to this
  spec's scope.
