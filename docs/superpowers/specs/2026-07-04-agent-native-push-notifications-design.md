# Agent-native push notifications for direct (Tailscale) mode

## Context

Push ("an agent needs you" FCM notifications) is entirely relay-owned today:
`internal/relay/pushmon.go`'s `MonitorAgent` opens its own subscription to the
agent's `/events` over the tunnel and fans blocking prompts out to FCM, using
FCM tokens registered via the relay-only `POST /devices/register`
(`internal/relay/relay.go:138`). Direct (Tailscale) mode has no equivalent —
`internal/server/trusted.go`'s shared route table explicitly does not mount
`/devices/register` ("device registration is handled exclusively by the
relay"), and the dual-pairing-automatic-fallback project (previous spec)
pinned Android's push registration specifically to the `RELAY` slot for
exactly this reason. A phone paired *only* to direct mode today gets zero
push, permanently, by design — not a bug, but a real gap the user asked to
close: "maybe we can move the notification part to the bridge side, can't
we?"

## Current architecture (confirmed from code)

- **Two separate device registries, not one.** The relay and the agent each
  own their own independent `internal/auth.Store` (SQLite, `devices` table
  with `token_hash`/`tenant_id`/`fcm_token` columns). A phone paired via the
  relay gets a device row *only* in the relay's store (home server); a phone
  paired via direct mode gets a device row *only* in the agent's own local
  store (`cfg.DirectAuthStore`, opened once in `runAgent`,
  `cmd/cmux-bridge/agent.go:276`). These never merge — this is the same
  separation the dual-pairing project already relied on for per-slot
  credential isolation.
- **`internal/auth.Store.SetFCMToken`/`TenantFCMTokens` already exist** and
  are transport-agnostic (`internal/auth/store.go:330,344`) — they're just
  never called for the agent's own local store today, because nothing mounts
  `/devices/register` on it.
- **`internal/server.Server` already holds exactly the right store
  reference.** `runAgent` constructs `server.New(cfg, cmuxClient,
  directStore)` (`agent.go:287`) — `directStore` is nil unless
  `cfg.DirectListen != ""`, and is otherwise the agent's own local
  `auth.Store`. `Server.store` (`internal/server/server.go:21`) is that same
  field, already used by `DirectHandler()`'s `auth.Require(s.store, ...)` for
  direct-mode's own bearer-token auth (`internal/server/direct.go:33-38`).
- **`internal/server/direct_pairing.go`'s `MountDirectPairing`** already
  implements a fully independent, no-relay pairing flow
  (`/agent/pairing-code`, `/devices/pair`, `/devices/pair-info/{code}`)
  against this same local store, scoped to direct mode's one implicit tenant
  (`ensureDirectTenant`, `agent.go:281`). Adding `/devices/register` here is
  the same shape of change, not a new pattern.
- **`internal/server/events.go`'s `ingestEvents`** is the single, canonical
  classifier of `cmux events` frames, run once per agent process
  (`srv.RunEvents(ctx)`, `agent.go:290`) regardless of which transport(s) are
  enabled. It already calls `s.maybeAutoResolve(ctx, f.WorkspaceID)`
  (`internal/server/yolo.go:59`) on every `NeedsAttention` frame for YOLO
  mode — the exact hook point a second, agent-native push trigger needs, for
  free.
- **`internal/push.Sender`** (`internal/push/fcm.go`) is a plain FCM HTTP v1
  client with an injectable OAuth token source — nothing relay-specific about
  it. **`internal/push.FromServiceAccount(ctx, projectID, credentialsPath)
  (*Sender, error)`** (`internal/push/credentials.go:16`) is the existing
  constructor `cmd/cmux-relay/serve.go:74-82` uses; it works identically if
  called from the agent binary instead.
- **Android**: `FallbackBridgeClient` (`android/.../data/FallbackBridgeClient.kt`)
  deliberately does not expose `registerDevice` today — push registration
  goes straight to `container.bridgeClient(ConnectionSlot.RELAY)` from both
  `CmuxMessagingService.onNewToken` and `MainActivity.registerFcmToken`.
  `BridgeClient.registerDevice(fcmToken)` (`BridgeClient.kt:44`) POSTs
  `{fcm_token}` to `$root/devices/register` — already transport-agnostic; it
  just has nowhere to land on the direct listener yet.

## Decisions made

- **Option B: additive, not a full move.** Leave the relay's existing push
  path (`pushmon.go`, relay's own `auth.Store`, relay's own FCM config)
  completely untouched — it works today, is in daily use, and a full move
  would require bridging two separate device-identity stores for no benefit
  the user asked for. Instead, add a second, independent push path on the
  agent, scoped to devices in the agent's *own* local store (direct-mode
  pairs only). *(This was presented to the user as a three-way choice
  alongside a full-move option and a relay-forwards-tokens-to-agent option;
  the clarifying question timed out with no response, so — per this
  project's standing "proceed on timeout, record the assumption" rule — the
  clearly-recommended, lowest-risk option was chosen. Confirm or redirect
  when reviewing this spec.)*
- **Same Firebase project, configured twice.** The Mac agent gets its own
  `fcm_project_id`/`fcm_credentials` config (same TOML keys the relay
  already uses, same Firebase project, same service-account key file copied
  or re-downloaded) — an operational duplication, not a code cost. No new
  Firebase project, no changes to the Android `google-services.json` step.
- **Android push registration moves off the `RELAY` pin, onto
  `activeBridge()`.** Now that *both* slots can genuinely accept a
  registration, hard-pinning to relay is no longer correct — it would leave
  a direct-only phone exactly as broken as today. Routing registration
  through the existing fallback-aware client (relay tried first, direct only
  on relay failure) gets the right behavior for all three real phone states
  (relay-only, direct-only, both) with no new Android logic beyond exposing
  one more method on `FallbackBridgeClient` and deleting the `RELAY`-pin
  special case Task 6 of the prior project added.
- **No de-duplication logic for dual-paired phones.** `FallbackBridgeClient`
  already tries primary before falling back and only fails over on a
  transport error or 5xx (post the prior project's own fix,
  `FallbackBridgeClient.kt`'s narrowed catch) — a healthy relay always wins,
  so registration (and therefore push delivery) lands on exactly one slot's
  store in the common case. If both stores somehow ended up with a live FCM
  token for the same physical phone (e.g. relay was flaky right when the
  token rotated), the phone would receive two copies of the same
  notification — accepted as a rare, harmless edge case, not worth extra
  code to prevent.
- **No bridge-side unification of the two `auth.Store` instances.** Each
  store's FCM fan-out only ever sees its own devices — the relay's `pushmon`
  keeps calling `store.TenantFCMTokens(tenantID)` against the relay's store
  exactly as today; the agent's new trigger calls the same method against
  its own local store and its own one implicit tenant.

## Design

### Bridge (Go): agent-native FCM sender + local device registration

**`internal/config/agent.go`** — add two fields to `AgentConfig`, mirroring
the relay's own config exactly:

```go
// FCMProjectID is the Firebase project id for direct-mode push. Empty
// disables it — direct mode behaves exactly as it does today.
FCMProjectID string `toml:"fcm_project_id"`
// FCMCredentials is the path to a Google service-account JSON key for
// direct-mode push. Empty disables it.
FCMCredentials string `toml:"fcm_credentials"`
```

No default value (empty string default, matching every other optional
feature flag in this struct — YOLO store, direct listen, etc.). No
`expandHome` needed for `FCMProjectID` (not a path); `FCMCredentials` does
need it, alongside the other path fields in `LoadAgent`.

**`internal/server/server.go`** — add a `pusher` field and setter, mirroring
`SetYoloStore`'s exact shape:

```go
// Pusher sends an FCM data message to one registration token. push.Sender
// satisfies it (see internal/relay.Pusher for the identical shape used
// relay-side).
type Pusher interface {
    Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
}

// pusher is nil unless SetPusher is called (only by runAgent's production
// wiring, and only when direct mode + FCM config are both present). Nil
// means direct-mode attention frames are never pushed -- the
// plaintext-equivalent default every existing test exercises.
pusher Pusher
// directTenantID scopes pusher's token lookup to direct mode's one
// implicit tenant. Only meaningful together with pusher, so both are set
// by the same call.
directTenantID string

// SetPusher enables agent-native push for direct-mode-paired devices,
// scoped to tenantID (direct mode's one implicit tenant -- the same value
// already passed to MountDirectPairing). Called only by runAgent's
// production wiring. Deliberately not a New() parameter: pusher is
// optional, orthogonal config, following the same post-construction-setter
// idiom as SetSessions/SetYoloStore rather than growing New()'s signature
// and breaking every existing test call site.
func (s *Server) SetPusher(p Pusher, tenantID string) {
    s.pusher = p
    s.directTenantID = tenantID
}
```

**`internal/server/yolo.go`** (or a new small `internal/server/push.go` next
to it — same file is fine given how short this is, matching
`maybeAutoResolve`'s neighboring placement) — add the trigger, called
alongside the existing YOLO auto-resolve:

```go
// maybeSendPush fans a NeedsAttention frame out to every FCM token
// registered in this agent's own local device store (direct-mode pairs
// only -- the relay's separate store/pushmon subscription handles
// relay-paired devices independently). No-op with zero RPC/network cost
// when direct mode or FCM aren't configured, the common case for an agent
// that hasn't opted into either.
func (s *Server) maybeSendPush(ctx context.Context, f EventFrame) {
    if s.pusher == nil || s.store == nil {
        return
    }
    tokens := s.store.TenantFCMTokens(s.directTenantID)
    if len(tokens) == 0 {
        return
    }
    body := f.Title
    if body == "" {
        body = f.Kind
    }
    data := map[string]string{
        "type":         "attention",
        "feed_id":      f.FeedID,
        "workspace_id": f.WorkspaceID,
        "surface_id":   f.SurfaceID,
        "kind":         f.Kind,
    }
    sendCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
    defer cancel()
    for _, tok := range tokens {
        if err := s.pusher.Send(sendCtx, tok, "Agent needs your attention", body, data); err != nil {
            log.Printf("agent: direct-mode push failed (kind=%s ws=%s): %v", f.Kind, f.WorkspaceID, err)
        }
    }
}
```

`s.directTenantID` is set by `SetPusher` above (not threaded through `New`),
mirroring how `MountDirectPairing(mux, store, tenantID)` already receives
the same `directTenantID` value from `runAgent` for direct mode's pairing
routes — `maybeSendPush` (which runs from `ingestEvents`, not a per-request
handler, so it has no request to read a path/header tenant ID from) needs
`Server` to carry that same value, and `SetPusher` is the one call site that
already has it in scope in `runAgent`. No existing test call site changes:
`server.New(cfg, cmux, store)` keeps its current three-parameter signature
exactly as today.

**`internal/server/events.go`**, in `ingestEvents`:

```go
if f.NeedsAttention {
    s.enrichTitle(ctx, &f)
    s.maybeAutoResolve(ctx, f.WorkspaceID)
    s.maybeSendPush(ctx, f)
}
```

**`internal/server/trusted.go`** — update the now-stale comment on `routes()`
("Device registration is handled exclusively by the relay, so it is never
mounted here") to note the real, narrower reason `/devices/register` still
isn't in this shared function: it's mounted on `DirectHandler()` specifically
(below), not here, because this function's `wrap` is reused by both
`TrustedHandler` (relay-tunneled, no per-device bearer validation at the
agent) and `DirectHandler` (direct-mode, real per-device bearer validation)
— and this route only makes sense for the latter. Do not add
`mux.Handle("POST /devices/register", ...)` inside `routes()` itself.

New handler (same file as the FCM code above, e.g. `internal/server/yolo.go`,
or a new small `internal/server/push.go`) — writes into `s.store` keyed by
the caller's own validated bearer token:

```go
type registerDeviceRequest struct {
    FCMToken string `json:"fcm_token"`
}

func (s *Server) handleRegisterDevice(w http.ResponseWriter, r *http.Request) {
    if s.store == nil {
        writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "not_available"})
        return
    }
    var rq registerDeviceRequest
    if err := json.NewDecoder(r.Body).Decode(&rq); err != nil || rq.FCMToken == "" {
        writeJSON(w, http.StatusBadRequest, map[string]string{"error": "missing fcm_token"})
        return
    }
    if !s.store.SetFCMToken(auth.BearerToken(r), rq.FCMToken) {
        writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
        return
    }
    writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
```

`SetFCMToken(token, fcm string)` (`internal/auth/store.go:330`) takes the
device's **raw bearer token** and hashes it internally (`hashToken(token)`)
— it must **not** be called with `X-Device-ID` (which, as `injectDeviceID`
sets it and as `writeEventFrame`/`s.sessions.SharedSecret(deviceID)` already
consume it elsewhere in this same package, carries the token's **hash**,
not the raw token — passing the hash to `SetFCMToken` would double-hash and
never match any row). Read the raw bearer token directly via
`auth.BearerToken(r)` instead (available since `auth.Require` already ran
and validated it, same as every other route on this mux) — this exactly
mirrors how `internal/relay/relay.go:319`'s existing
`r.store.SetFCMToken(auth.BearerToken(req), rq.FCMToken)` already does the
identical thing against the relay's own store. `internal/auth` is already
imported in this package (`server.go:10`). This makes `handleRegisterDevice`
mountable **only** on `DirectHandler()`'s route set (the direct listener,
where `auth.Require` genuinely re-validates the device's own bearer token
against `s.store` on every request) — it must **not** be mounted on
`TrustedHandler()` (the relay-tunneled path), because `RequireRelayToken`
there never calls `auth.Require`/never populates a real per-device bearer
context, so `auth.BearerToken(r)` would read whatever bearer the *relay*
forwarded verbatim, which is meaningless against the agent's own local
store (a relay-paired device's bearer token was issued by the *relay's*
store, not this one, and has no row in `s.store` to update).

This changes the route wiring from "one shared `routes()` used by both
handlers" to "one extra route on `DirectHandler()` specifically." Concretely,
**do not** add `/devices/register` to `s.routes(wrap)`
(`internal/server/trusted.go:31`, shared by both `TrustedHandler` and
`DirectHandler`) — add it directly in `DirectHandler()`
(`internal/server/direct.go:33`) on top of the shared route set:

```go
func (s *Server) DirectHandler() http.Handler {
    wrap := func(h http.Handler) http.Handler {
        return auth.Require(s.store, injectDeviceID(s.encryptionMiddleware(h)))
    }
    mux := s.routes(wrap).(*http.ServeMux)
    mux.Handle("POST /devices/register", wrap(http.HandlerFunc(s.handleRegisterDevice)))
    return mux
}
```

(`s.routes` already returns `*http.ServeMux`, so the type assertion is safe
and mirrors nothing exotic — `http.NewServeMux()`'s return type is exactly
that concrete type, not an interface, so `routes`'s own declared return type
of `http.Handler` is just the narrower public-facing signature.) This keeps
`trusted_test.go`'s existing "not mounted in trusted mode → 404" assertion
correct and unchanged — it never gains this route — while `direct.go`'s own
handler gains exactly one new endpoint.

**`cmd/cmux-bridge/agent.go`**, in `runAgent` — construct the pusher the same
way `cmd/cmux-relay/serve.go:74-82` already does, and wire it in only when
direct mode is on:

```go
if cfg.DirectListen != "" {
    // ... existing directStore/directTenantID setup ...
}
srv := server.New(config.Config{}, &cmux.Client{Bin: cfg.CmuxBin}, directStore)
srv.SetSessions(e2e.OpenStore(cfg.SessionStore))
srv.SetYoloStore(yolo.OpenStore(cfg.YoloStore))
if cfg.DirectListen != "" && cfg.FCMProjectID != "" && cfg.FCMCredentials != "" {
    if p, err := push.FromServiceAccount(context.Background(), cfg.FCMProjectID, cfg.FCMCredentials); err != nil {
        log.Printf("agent: direct-mode push disabled: %v", err)
    } else {
        srv.SetPusher(p, directTenantID)
        log.Printf("agent: direct-mode FCM push enabled for project %s", cfg.FCMProjectID)
    }
}
```

(`server.New`'s call keeps its existing three-argument signature —
`directTenantID` reaches `Server` only via `SetPusher`, per the Design
section above.)

### Android: registration follows the fallback path, not a hard pin

**`FallbackBridgeClient.kt`** — expose `registerDevice`, same shape as every
other wrapped call:

```kotlin
suspend fun registerDevice(fcmToken: String) = call { it.registerDevice(fcmToken) }
```

Update the class doc comment's stale note ("`registerDevice()` is
deliberately NOT exposed here — see push section below" in the prior
project's own file) to explain the new reality: it *is* exposed now, because
both slots can genuinely accept a registration.

**`CmuxMessagingService.kt`**: `onNewToken` changes from
`container.bridgeClient(ConnectionSlot.RELAY)?.registerDevice(token)` to
`container.activeBridge()?.registerDevice(token)`.

**`MainActivity.kt`**: `registerFcmToken()`'s inner call changes identically,
from `container.bridgeClient(ConnectionSlot.RELAY)?.registerDevice(token)` to
`container.activeBridge()?.registerDevice(token)`.

Both call sites drop their `import com.sodre90.cmuxremote.data.ConnectionSlot`
if it becomes otherwise unused in that file (check each file's remaining
uses before removing the import).

## Data flow

### Direct-only phone (the case this project fixes)

1. Phone pairs against direct mode only (`Settings`/`Session`'s `DIRECT`
   slot populated, `RELAY` slot empty — exactly today's `activeBridge()`
   behavior: `bridgeClient(RELAY)` is null, so `FallbackBridgeClient`'s
   `primary()` is null and every call, including the new `registerDevice`,
   goes straight to the direct slot's `BridgeClient`).
2. `MainActivity.registerFcmToken()` (on launch) or
   `CmuxMessagingService.onNewToken()` (on token rotation) calls
   `container.activeBridge()?.registerDevice(token)`, which reaches the
   agent's own `DirectHandler()`'s new `/devices/register` route, writing
   the FCM token into the agent's own local `auth.Store` keyed by that
   phone's direct-mode bearer token hash.
3. The agent's `ingestEvents` classifies a `NeedsAttention` frame,
   `maybeSendPush` reads `s.store.TenantFCMTokens(s.directTenantID)` (now
   non-empty), and sends the FCM data message directly to Google from the
   Mac's own outbound internet connection — no relay, no home server,
   involved at any point in this path.
4. The phone receives the push exactly as it does today for a relay-paired
   device (same `type=attention` data message shape, same
   `CmuxMessagingService` handling, same deep-link behavior) — the Android
   app needs zero changes to how it *receives* or *displays* push; only
   *registration* changes.

### Relay-only phone (unchanged) / both paired (unchanged common case)

Both behave exactly as the dual-pairing project already established:
registration and push both land on whichever slot `FallbackBridgeClient`
picks (relay, unless it's down), and the relay's existing `pushmon.go` path
keeps working completely unmodified.

## Error handling / edge cases

- **Direct mode enabled, FCM not configured on the agent:** `srv.SetPusher`
  is never called, `s.pusher` stays nil, `maybeSendPush` is a no-op — direct
  mode behaves exactly as it does today (works fully, just no push),
  matching this project's own "off by default" pattern used everywhere else
  (YOLO store, direct listen itself).
- **FCM configured but `push.FromServiceAccount` fails (bad credentials
  file, unreadable path):** logged and push stays disabled for that agent
  run — mirrors `cmd/cmux-relay/serve.go`'s identical existing behavior
  exactly (`log.Printf("serve: push disabled: %v", err)`), not a fatal
  startup error.
- **A device registers via direct mode's new endpoint before ever being
  paired (bearer token invalid):** `auth.Require` (already wrapping this
  route via `DirectHandler`'s `wrap`) rejects the request with 401 before
  `handleRegisterDevice` ever runs — identical to every other route on this
  handler today, no new auth logic introduced.
- **A relay-paired device's request somehow reaches `handleRegisterDevice`:**
  can't happen by construction — the route only exists on `DirectHandler()`,
  never on `TrustedHandler()` (the relay-tunneled path), per the Design
  section's explicit note against adding it to the shared `routes()`.
- **Dual-paired phone, relay flaps right as the FCM token rotates:** covered
  under Decisions above — at most a duplicate notification, never a crash or
  lost notification.
- **Existing relay-only installs (the overwhelming common case today):** zero
  behavior change. `cfg.FCMProjectID`/`cfg.FCMCredentials` stay empty on the
  agent unless explicitly configured; `cfg.DirectListen` stays empty for
  anyone who hasn't opted into direct mode at all.

## Testing

- **Bridge (Go) unit tests:**
  - `internal/server`: `handleRegisterDevice` — valid bearer + valid body →
    200 and `store.SetFCMToken` was actually called with the right
    token/fcm pair (assert via a subsequent `TenantFCMTokens` read, mirroring
    how `internal/relay/relay_test.go` already tests the relay's identical
    handler); missing/empty `fcm_token` → 400; unmounted on
    `TrustedHandler()` (a request to `/devices/register` through that
    handler returns 404, extending `trusted_test.go`'s existing assertion
    rather than replacing it — this route must appear on `DirectHandler()`
    only).
  - `internal/server`: `maybeSendPush` — nil pusher → no call to a spy
    `Pusher`; non-nil pusher with tokens present → spy receives exactly one
    `Send` per token with the expected title/body/data shape (mirror
    `internal/relay/pushmon_test.go`'s existing `fanout` test structure
    closely, since this is the same logic ported to a new package); empty
    token list → no call.
  - `internal/config`: `LoadAgent` parses `fcm_project_id`/`fcm_credentials`
    correctly; `fcm_credentials` gets `expandHome`'d like the other path
    fields.
- **Android unit tests:**
  - `FallbackBridgeClientTest`: add a `registerDevice` case mirroring the
    existing `sessions()` coverage (primary success → fallback never
    called; primary failure → fallback called) — same pattern already used
    for every other wrapped call, applied to the newly-exposed method.
  - `CmuxMessagingServiceTest`/`MainActivity`-adjacent (if any exist,
    otherwise verify by inspection matching this codebase's established
    convention for Android-framework-coupled classes with no Robolectric):
    confirm the call site reads `container.activeBridge()`, not
    `container.bridgeClient(ConnectionSlot.RELAY)`.
- **Manual end-to-end:** configure `fcm_project_id`/`fcm_credentials` in
  `agent.toml` (the same service-account key already used for the relay, or
  a fresh download of the same one); pair a phone to direct mode only (no
  relay slot paired at all); trigger a real blocking prompt in a disposable
  workspace; confirm the notification arrives with the Mac's agent log
  showing `"agent: direct-mode FCM push enabled for project ..."` and no
  relay involvement at any point (e.g. temporarily block the relay
  connection entirely and confirm push still arrives).

## Explicit non-goals (this project)

- Unifying the relay's and the agent's device-identity stores — the two
  `auth.Store` instances stay fully independent, per the Decisions section.
- Retiring or modifying `internal/relay/pushmon.go` or any relay-side FCM
  code — untouched.
- De-duplicating push for a dual-paired phone that somehow has live tokens
  in both stores simultaneously — accepted as a rare, harmless edge case.
- Any new Android UI (no visible "push works over Tailscale now" indicator;
  this is a backend delivery-path fix, invisible by design, matching the
  dual-pairing project's own "seamless, no indicator" precedent).
- A new Firebase project or any change to `google-services.json`/the
  Android-side push setup instructions in `android/README.md` — those are
  already correct and unchanged by this project.
