# Agent-native push notifications for direct (Tailscale) mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Mac agent send FCM push notifications directly (no relay involved) for phones paired only to direct (Tailscale) mode, which today get zero push, permanently, by design.

**Architecture:** Additive second push path on the agent, reusing the agent's own already-existing local `auth.Store` (direct mode's device registry) and the already-existing transport-agnostic `internal/push.Sender`. The relay's existing push path (`pushmon.go`) is completely untouched. Android's push registration moves from a hard `RELAY` pin onto the existing fallback-aware `activeBridge()`, so registration (and therefore push) lands on whichever slot is actually configured/reachable.

**Tech Stack:** Go (bridge), Kotlin/Coroutines (Android) — no new dependencies on either side; both projects already import everything this plan needs (`internal/push`, OkHttp/MockWebServer).

## Global Constraints

- **Additive, relay untouched.** `internal/relay/pushmon.go`, the relay's own `auth.Store`, and the relay's own FCM config must not change in this plan. Existing relay-only installs must see zero behavior change.
- **`server.New`'s three-argument signature does not change.** The new `directTenantID` reaches `Server` only via `SetPusher(p, tenantID)`, never via a `New()` parameter — this avoids touching every existing test call site (`server.New(cfg, cmux, store)` appears in ~10 test files).
- **`/devices/register` is mounted only on `DirectHandler()`, never on the shared `routes()` used by both `TrustedHandler`/`DirectHandler`, and never on `TrustedHandler()` itself.** `TrustedHandler`'s relay-tunneled path has no real per-device bearer validation at the agent (`RequireRelayToken` only), so `auth.BearerToken(r)` would be meaningless there. `trusted_test.go`'s existing "`/devices/register` is not mounted in trusted mode → 404" assertion must keep passing unmodified.
- **Registration writes use the raw bearer token, never `X-Device-ID`.** `auth.Store.SetFCMToken(token, fcm)` hashes its `token` argument internally; `X-Device-ID` already carries a hash. Passing the hash would double-hash and silently match zero rows. Use `auth.BearerToken(r)` (already exported, already used identically at `internal/relay/relay.go:319`).
- **Everything is off by default.** `cfg.FCMProjectID`/`cfg.FCMCredentials` empty (the default) must leave direct mode behaving exactly as it does today — no push, no error, no behavior change. Mirrors the existing pattern for `DirectListen`/`YoloStore`.
- **Same Firebase project, different config file/location** — the agent's `fcm_project_id`/`fcm_credentials` config keys are new to `AgentConfig` but textually identical to the relay's own `Config` fields of the same name; no new TOML key names are invented.
- Commits authored solely by `sodre90 <erdos.peter.bme@gmail.com>`, NEVER any `Co-Authored-By`/AI-attribution trailer (verify via `git log -1 --format='%an %ae %B'` after every commit — do not just trust `git commit`'s own confirmation).
- Branch: continue on `tailscale-direct-transport` (already the active branch; this project depends on direct mode, which lives there and is not yet merged to `main`).

---

### Task 1: Agent FCM config fields

**Files:**
- Modify: `bridge/internal/config/agent.go`
- Test: `bridge/internal/config/agent_test.go`

**Interfaces:**
- Produces: `AgentConfig.FCMProjectID string`, `AgentConfig.FCMCredentials string` — consumed by Task 4's `runAgent` wiring.

- [ ] **Step 1: Write the failing tests**

Append to `bridge/internal/config/agent_test.go`:

```go
func TestLoadAgentDefaultsFCMFieldsEmpty(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.FCMProjectID != "" || cfg.FCMCredentials != "" {
		t.Fatalf("FCM fields should default empty (push disabled), got project=%q credentials=%q", cfg.FCMProjectID, cfg.FCMCredentials)
	}
}

func TestLoadAgentParsesFCMFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
fcm_project_id  = "my-project"
fcm_credentials = "/c/fcm-key.json"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.FCMProjectID != "my-project" {
		t.Fatalf("FCMProjectID = %q", cfg.FCMProjectID)
	}
	if cfg.FCMCredentials != "/c/fcm-key.json" {
		t.Fatalf("FCMCredentials = %q", cfg.FCMCredentials)
	}
}

func TestLoadAgentExpandsFCMCredentialsHome(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	if err := os.WriteFile(path, []byte(`fcm_credentials = "~/fcm-key.json"`+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(cfg.FCMCredentials, "~") {
		t.Fatalf("FCMCredentials not expanded: %q", cfg.FCMCredentials)
	}
	if !strings.HasSuffix(cfg.FCMCredentials, "fcm-key.json") {
		t.Fatalf("FCMCredentials = %q", cfg.FCMCredentials)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/config/... -run TestLoadAgent -v`
Expected: FAIL — `cfg.FCMProjectID`/`cfg.FCMCredentials` are unresolved fields (compile error).

- [ ] **Step 3: Write the implementation**

In `bridge/internal/config/agent.go`, add two fields to `AgentConfig` (after `DirectAuthStore`):

```go
	// FCMProjectID is the Firebase project id for direct-mode push. Empty
	// disables it -- direct mode behaves exactly as it does today. Same
	// Firebase project as the relay's own fcm_project_id, configured
	// separately here since the agent has its own independent device store.
	FCMProjectID string `toml:"fcm_project_id"`
	// FCMCredentials is the path to a Google service-account JSON key for
	// direct-mode push. Empty disables it.
	FCMCredentials string `toml:"fcm_credentials"`
```

In `LoadAgent`, alongside the existing `expandHome` calls, add:

```go
	cfg.FCMCredentials = expandHome(cfg.FCMCredentials)
```

(`FCMProjectID` is not a path — no `expandHome` call for it, matching how `RelayToken`/other non-path fields are left alone.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/config/... -v`
Expected: PASS, full package (all pre-existing tests plus the 3 new ones).

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/config/agent.go bridge/internal/config/agent_test.go
git commit -m "bridge: add agent-side FCM config fields"
```

---

### Task 2: `Server.maybeSendPush` — agent-native attention → FCM

**Files:**
- Create: `bridge/internal/server/push.go`
- Create: `bridge/internal/server/push_test.go`
- Modify: `bridge/internal/server/server.go`
- Modify: `bridge/internal/server/events.go`

**Interfaces:**
- Produces: `server.Pusher` interface, `Server.SetPusher(p Pusher, tenantID string)`, `Server.maybeSendPush(ctx, EventFrame)` — consumed by Task 4 (`runAgent` calls `SetPusher`) and by `ingestEvents` (this task wires the call itself).
- Consumes: `Server.store`, `Server.directTenantID` (this task adds the latter field).

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/server/push_test.go`:

```go
package server

import (
	"context"
	"path/filepath"
	"sync"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
)

type fakePusher struct {
	mu    sync.Mutex
	calls []struct {
		token, title, body string
		data               map[string]string
	}
}

func (p *fakePusher) Send(_ context.Context, tok, title, body string, data map[string]string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls = append(p.calls, struct {
		token, title, body string
		data               map[string]string
	}{tok, title, body, data})
	return nil
}

func (p *fakePusher) callCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.calls)
}

func newPushTestServer(t *testing.T) (*Server, *auth.Store) {
	t.Helper()
	store, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	s := New(config.Config{}, &cmux.Client{}, store)
	return s, store
}

func TestMaybeSendPushNoopWithoutPusher(t *testing.T) {
	s, store := newPushTestServer(t)
	tenant, _ := store.CreateTenant()
	tok, _ := store.Issue(tenant, "phone", "test-pubkey-b64")
	store.SetFCMToken(tok, "fcm-token-1")
	// s.pusher is nil (SetPusher never called) -- must not panic, must not
	// look anything up.
	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})
}

func TestMaybeSendPushNoopWithoutStore(t *testing.T) {
	s := New(config.Config{}, &cmux.Client{}, nil) // store nil: direct mode off
	fp := &fakePusher{}
	s.SetPusher(fp, "some-tenant")
	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})
	if fp.callCount() != 0 {
		t.Fatalf("must not call pusher when store is nil, got %d calls", fp.callCount())
	}
}

func TestMaybeSendPushSendsToEveryRegisteredToken(t *testing.T) {
	s, store := newPushTestServer(t)
	tenant, _ := store.CreateTenant()
	tok1, _ := store.Issue(tenant, "phone-1", "test-pubkey-1")
	tok2, _ := store.Issue(tenant, "phone-2", "test-pubkey-2")
	store.SetFCMToken(tok1, "fcm-1")
	store.SetFCMToken(tok2, "fcm-2")

	fp := &fakePusher{}
	s.SetPusher(fp, tenant)

	s.maybeSendPush(context.Background(), EventFrame{
		NeedsAttention: true, FeedID: "F1", WorkspaceID: "W1", SurfaceID: "S1",
		Title: "Run rm -rf?", Kind: "permissionRequest",
	})

	if fp.callCount() != 2 {
		t.Fatalf("want 2 push calls (one per registered token), got %d", fp.callCount())
	}
	got := map[string]bool{}
	for _, c := range fp.calls {
		got[c.token] = true
		if c.data["type"] != "attention" || c.data["feed_id"] != "F1" || c.data["workspace_id"] != "W1" || c.data["kind"] != "permissionRequest" {
			t.Fatalf("unexpected push data: %+v", c.data)
		}
		if c.body != "Run rm -rf?" {
			t.Fatalf("body = %q, want the frame's Title", c.body)
		}
	}
	if !got["fcm-1"] || !got["fcm-2"] {
		t.Fatalf("expected both tokens to receive push, got calls: %+v", fp.calls)
	}
}

func TestMaybeSendPushNoopWithNoRegisteredTokens(t *testing.T) {
	s, store := newPushTestServer(t)
	tenant, _ := store.CreateTenant()
	fp := &fakePusher{}
	s.SetPusher(fp, tenant)

	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})

	if fp.callCount() != 0 {
		t.Fatalf("no tokens registered -- want 0 calls, got %d", fp.callCount())
	}
}

func TestMaybeSendPushScopesToOwnTenant(t *testing.T) {
	s, store := newPushTestServer(t)
	tenantA, _ := store.CreateTenant()
	tenantB, _ := store.CreateTenant()
	tokA, _ := store.Issue(tenantA, "phone-a", "test-pubkey-a")
	tokB, _ := store.Issue(tenantB, "phone-b", "test-pubkey-b")
	store.SetFCMToken(tokA, "fcm-a")
	store.SetFCMToken(tokB, "fcm-b")

	fp := &fakePusher{}
	s.SetPusher(fp, tenantA) // Server only knows about tenantA

	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})

	if fp.callCount() != 1 || fp.calls[0].token != "fcm-a" {
		t.Fatalf("push must be scoped to directTenantID only, got calls: %+v", fp.calls)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/server/... -run TestMaybeSendPush -v`
Expected: FAIL — `Pusher`, `SetPusher`, `maybeSendPush` are unresolved references (compile error).

- [ ] **Step 3: Write the implementation**

Create `bridge/internal/server/push.go`:

```go
package server

import (
	"context"
	"log"
	"time"
)

// Pusher sends an FCM data message to one registration token. push.Sender
// (internal/push) satisfies it -- see internal/relay.Pusher for the
// identical shape used relay-side; the two are duck-typed independently
// since relay and server are separate packages with no reason to share an
// interface type across a package boundary that otherwise has none.
type Pusher interface {
	Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
}

// maybeSendPush fans a NeedsAttention frame out to every FCM token
// registered in this agent's own local device store (direct-mode pairs
// only -- the relay's separate store/pushmon subscription handles
// relay-paired devices completely independently and is untouched by this).
// No-op with zero store/network cost when direct mode or FCM aren't
// configured, the common case for an agent that hasn't opted into either.
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

In `bridge/internal/server/server.go`, add two fields to the `Server` struct (after `yolo`) and one setter (after `SetYoloStore`):

```go
	// pusher is nil unless SetPusher is called (only by runAgent's production
	// wiring, and only when direct mode + FCM config are both present). Nil
	// means direct-mode attention frames are never pushed -- the
	// plaintext-equivalent default every existing test exercises.
	pusher Pusher
	// directTenantID scopes pusher's token lookup to direct mode's one
	// implicit tenant. Only meaningful together with pusher, so both are set
	// by the same call.
	directTenantID string
```

```go
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

In `bridge/internal/server/events.go`'s `ingestEvents`, add the call alongside the existing YOLO auto-resolve:

```go
		if f, ok := classify(m); ok {
			if f.NeedsAttention {
				s.enrichTitle(ctx, &f)
				s.maybeAutoResolve(ctx, f.WorkspaceID)
				s.maybeSendPush(ctx, f)
			}
			s.hub.broadcast(f)
		}
```

(Only the one new line changes in this function — everything else in `ingestEvents` is unchanged.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/server/... -v`
Expected: PASS, full package (all pre-existing tests plus the 5 new ones in `push_test.go`).

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/push.go bridge/internal/server/push_test.go bridge/internal/server/server.go bridge/internal/server/events.go
git commit -m "bridge: send FCM push for direct-mode attention frames"
```

---

### Task 3: `POST /devices/register` on the direct listener

**Files:**
- Modify: `bridge/internal/server/push.go` (handler lives here, alongside `Pusher`/`maybeSendPush` — all push-related)
- Modify: `bridge/internal/server/direct.go`
- Modify: `bridge/internal/server/trusted.go` (comment only)
- Test: `bridge/internal/server/push_test.go`

**Interfaces:**
- Produces: `Server.handleRegisterDevice` (HTTP handler), mounted only on `DirectHandler()`.
- Consumes: `Server.store`, `auth.BearerToken(r)` (already exported by `internal/auth`, already imported in this package).

- [ ] **Step 1: Write the failing tests**

Append to `bridge/internal/server/push_test.go`:

```go
func newDirectRegisterTestServer(t *testing.T) (*Server, *auth.Store) {
	t.Helper()
	store, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	s := New(config.Config{}, &cmux.Client{}, store)
	s.SetSessions(e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json")))
	return s, store
}

func TestHandleRegisterDeviceStoresToken(t *testing.T) {
	s, store := newDirectRegisterTestServer(t)
	tenant, _ := store.CreateTenant()
	tok, _ := store.Issue(tenant, "phone", "test-pubkey-b64")

	srv := httptest.NewServer(s.DirectHandler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/devices/register", strings.NewReader(`{"fcm_token":"fcm-abc"}`))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}

	tokens := store.TenantFCMTokens(tenant)
	if len(tokens) != 1 || tokens[0] != "fcm-abc" {
		t.Fatalf("token not stored correctly: %+v", tokens)
	}
}

func TestHandleRegisterDeviceRejectsMissingFCMToken(t *testing.T) {
	s, store := newDirectRegisterTestServer(t)
	tenant, _ := store.CreateTenant()
	tok, _ := store.Issue(tenant, "phone", "test-pubkey-b64")

	srv := httptest.NewServer(s.DirectHandler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/devices/register", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for missing fcm_token, got %d", resp.StatusCode)
	}
}

func TestHandleRegisterDeviceRejectsInvalidBearer(t *testing.T) {
	s, _ := newDirectRegisterTestServer(t)

	srv := httptest.NewServer(s.DirectHandler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/devices/register", strings.NewReader(`{"fcm_token":"fcm-abc"}`))
	req.Header.Set("Authorization", "Bearer not-a-real-token")
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 for an invalid bearer token, got %d", resp.StatusCode)
	}
}

func TestHandleRegisterDeviceNotMountedOnTrustedHandler(t *testing.T) {
	s, _ := newDirectRegisterTestServer(t)
	srv := httptest.NewServer(s.TrustedHandler("relay-secret"))
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/devices/register", strings.NewReader(`{"fcm_token":"fcm-abc"}`))
	req.Header.Set("X-Relay-Token", "relay-secret")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404 -- /devices/register must not exist on the relay-tunneled handler, got %d", resp.StatusCode)
	}
}
```

Add the needed imports to `push_test.go`'s import block: `net/http`, `net/http/httptest`, `strings`, and `github.com/sodre90/cmux-bridge/internal/e2e`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/server/... -run TestHandleRegisterDevice -v`
Expected: FAIL — `s.handleRegisterDevice`/the new route are unresolved or return 404 everywhere (compile error until Step 3's handler exists; then a routing failure until it's mounted).

- [ ] **Step 3: Write the implementation**

Append to `bridge/internal/server/push.go`:

```go
type registerDeviceRequest struct {
	FCMToken string `json:"fcm_token"`
}

// handleRegisterDevice stores a device's FCM registration token in this
// agent's own local store, keyed by the caller's own bearer token (NOT
// X-Device-ID, which carries a hash -- SetFCMToken hashes its argument
// itself; see auth.BearerToken's use here and identically at
// internal/relay/relay.go's handleRegister). Mounted only on
// DirectHandler()'s route set (see direct.go) -- never on TrustedHandler(),
// whose relay-tunneled requests have no real per-device bearer validation
// at the agent, so auth.BearerToken(r) would be meaningless there.
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

Add `encoding/json` and `net/http` to `push.go`'s import block, plus
`"github.com/sodre90/cmux-bridge/internal/auth"`.

In `bridge/internal/server/direct.go`, change `DirectHandler` to mount the
new route on top of the shared route set:

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

(`s.routes` returns a concrete `*http.ServeMux` wrapped in its declared
`http.Handler` return type, so the type assertion is safe.)

In `bridge/internal/server/trusted.go`, update `routes()`'s stale comment
(currently: `"Device registration is handled exclusively by the relay, so
it is never mounted here."`) to:

```go
// Device registration (/devices/register) is deliberately not mounted here:
// it's added directly onto DirectHandler()'s route set (see direct.go)
// instead, because this function's wrap is shared by TrustedHandler (the
// relay-tunneled path, with no real per-device bearer validation at the
// agent) and DirectHandler (which does have one) -- the route only makes
// sense for the latter.
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/server/... -v` and `cd bridge && go test ./... ` (full suite, confirming `trusted_test.go`'s pre-existing 404 assertion still passes unmodified).
Expected: PASS, full repo.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/push.go bridge/internal/server/push_test.go bridge/internal/server/direct.go bridge/internal/server/trusted.go
git commit -m "bridge: mount /devices/register on the direct listener"
```

---

### Task 4: Wire the agent-native pusher into `runAgent`

**Files:**
- Modify: `bridge/cmd/cmux-bridge/agent.go`

**Interfaces:**
- Consumes: `config.AgentConfig.FCMProjectID`/`FCMCredentials` (Task 1), `server.Server.SetPusher` (Task 2), `push.FromServiceAccount` (already exists, `internal/push/credentials.go:16`).

- [ ] **Step 1: Write the implementation**

No new unit test for this step: `runAgent` itself is the process entrypoint
and is not unit-tested anywhere in this codebase today (only its extracted
pure helpers are — `nextBackoff`, `loadTLS`, `directListenPort`,
`ensureDirectTenant`, etc., each in their own test). This wiring is a
straight port of `cmd/cmux-relay/serve.go:74-82`'s existing, already-tested
pattern into a new call site — verified by build success + manual end-to-end
below, matching how this same file's direct-mode listener wiring was
verified earlier in this branch's history.

In `bridge/cmd/cmux-bridge/agent.go`'s `runAgent`, add the import
`"github.com/sodre90/cmux-bridge/internal/push"`, then insert the pusher
construction right after the existing `srv.SetYoloStore(...)` line and
before `go srv.RunEvents(ctx)`:

```go
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
	go srv.RunEvents(ctx)
```

(The `cfg.DirectListen != ""` guard is necessary because `directTenantID` is
only ever assigned inside this same function's existing `if cfg.DirectListen
!= "" { ... }` block above — without direct mode on, there is no tenant to
scope push to at all, and `directStore` itself would be nil.)

- [ ] **Step 2: Verify the build**

Run: `cd bridge && go build ./... && go vet ./...`
Expected: builds and vets cleanly.

Run: `cd bridge && go test ./...`
Expected: full suite still green (this change adds no new automated
coverage of its own, per Step 1 — it must not regress anything existing).

- [ ] **Step 3: Manual verification note**

By inspection: with `fcm_project_id`/`fcm_credentials` both empty (the
default, and every existing install's actual state), the `if` condition is
false, `srv.SetPusher` is never called, `s.pusher` stays nil, and
`maybeSendPush` (Task 2) is a no-op — confirming this task changes no
behavior for any agent that hasn't opted into direct-mode push. With
`direct_listen` set but the FCM fields empty, same result. Real end-to-end
verification (both FCM fields configured, a real service-account key,
direct-only pairing, a real triggered prompt) is deferred to this plan's
final manual E2E step, after all tasks land.

- [ ] **Step 4: Commit**

```bash
git add bridge/cmd/cmux-bridge/agent.go
git commit -m "bridge: enable agent-native FCM push when configured"
```

---

### Task 5: Android `FallbackBridgeClient.registerDevice`

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/FallbackBridgeClient.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/FallbackBridgeClientTest.kt`

**Interfaces:**
- Produces: `FallbackBridgeClient.registerDevice(fcmToken: String)` — consumed by Task 6.
- Consumes: `BridgeClient.registerDevice(fcmToken: String)` (already exists, `BridgeClient.kt:44`).

- [ ] **Step 1: Write the failing tests**

Append to `FallbackBridgeClientTest.kt` (same file, same
`primaryServer`/`fallbackServer`/`clientFor` helpers already defined there):

```kotlin
    @Test
    fun registerDevicePrimarySuccessNeverCallsFallback() {
        primaryServer.enqueue(MockResponse())
        val fb = FallbackBridgeClient(primary = { clientFor(primaryServer) }, fallback = { clientFor(fallbackServer) })

        runBlocking { fb.registerDevice("fcm-token-abc") }

        assertEquals(1, primaryServer.requestCount)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun registerDeviceFallsBackWhenPrimaryUnreachable() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fallbackServer.enqueue(MockResponse())
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer) },
        )

        runBlocking { fb.registerDevice("fcm-token-abc") }

        assertEquals(1, primaryServer.requestCount)
        assertEquals(1, fallbackServer.requestCount)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.FallbackBridgeClientTest"`
Expected: FAIL — `FallbackBridgeClient.registerDevice` is an unresolved
reference (compile error).

- [ ] **Step 3: Write the implementation**

In `FallbackBridgeClient.kt`, add the method alongside the other wrapped
calls:

```kotlin
    suspend fun registerDevice(fcmToken: String) = call { it.registerDevice(fcmToken) }
```

Update the class's doc comment: it currently lists the wrapped calls without
mentioning `registerDevice` at all (the prior project's design spec noted it
was "deliberately NOT exposed here"). Add a line noting it now is, because
both slots can genuinely accept a registration (this plan's whole point).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.FallbackBridgeClientTest"`
Expected: PASS, full class (10 pre-existing + 2 new = 12/12).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/data/FallbackBridgeClient.kt android/app/src/test/java/com/sodre90/cmuxremote/data/FallbackBridgeClientTest.kt
git commit -m "android: expose registerDevice on FallbackBridgeClient"
```

---

### Task 6: Push registration follows `activeBridge()`, not the `RELAY` pin

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/push/CmuxMessagingService.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/MainActivity.kt`

**Interfaces:**
- Consumes: `AppContainer.activeBridge(): FallbackBridgeClient?` (already exists), `FallbackBridgeClient.registerDevice` (Task 5).

This is the Android half of this plan's fix: now that direct mode can
genuinely accept a registration (Tasks 2-4), hard-pinning to `RELAY` would
leave a direct-only phone exactly as broken as before this plan.
`ConnectionSlot` becomes otherwise unused in both files once this change
lands (confirmed by grep — each file's only other reference to
`ConnectionSlot` is the one line being changed) — remove the now-dead
import from both.

- [ ] **Step 1: Change `CmuxMessagingService.onNewToken`**

In `CmuxMessagingService.kt`:

```kotlin
    override fun onNewToken(token: String) {
        val container = (application as? CmuxApp)?.container ?: return
        scope.launch {
            try {
                container.activeBridge()?.registerDevice(token)
            } catch (_: Exception) {
                // Bridge unreachable or unconfigured; token is resent on next start.
            }
        }
    }
```

Remove the now-unused `import com.sodre90.cmuxremote.data.ConnectionSlot`.

- [ ] **Step 2: Change `MainActivity.registerFcmToken`**

In `MainActivity.kt`:

```kotlin
    private fun registerFcmToken() {
        val container = (application as CmuxApp).container
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                scope.launch {
                    try {
                        container.activeBridge()?.registerDevice(token)
                    } catch (_: Exception) {
                        // Bridge unreachable or unconfigured; retried next launch.
                    }
                }
            }
        } catch (_: Throwable) {
            // Firebase not configured (no google-services.json); push inactive.
        }
    }
```

Remove the now-unused `import com.sodre90.cmuxremote.data.ConnectionSlot`.

- [ ] **Step 3: Build**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: compiles cleanly (no unresolved/unused-import warnings escalated
to errors) — confirm no other reference to `ConnectionSlot` remains in
either file (`grep -n ConnectionSlot` on both should return nothing).

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: full suite green — 135/135 from the prior project's final state,
plus Task 5's 2 new `FallbackBridgeClientTest` cases, so 137/137 — no test
in this codebase exercises `CmuxMessagingService`/`MainActivity` directly,
per the established no-Robolectric constraint, so this step's confirmation
is "the rest of the suite still passes," not new coverage of these two
files.

- [ ] **Step 4: Manual verification note**

Confirm by inspection, for both files: if only `DIRECT` is paired,
`container.activeBridge()` returns the fallback-aware client whose primary
resolves to null and fallback resolves to the direct slot's `BridgeClient`
— so `registerDevice` now reaches the direct listener's new
`/devices/register` route (Task 3) instead of short-circuiting to nothing.
If only `RELAY` is paired, behavior is unchanged (registration reaches the
relay exactly as before this whole plan). If both are paired, registration
tries relay first and only reaches direct if relay is down, per
`FallbackBridgeClient`'s existing, already-tested primary/fallback
semantics — no new logic to verify here.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/push/CmuxMessagingService.kt android/app/src/main/java/com/sodre90/cmuxremote/MainActivity.kt
git commit -m "android: register push via the fallback-aware bridge client"
```

---

## Final Verification (after all tasks)

- `cd bridge && go build ./... && go vet ./... && go test ./...` — full green.
- `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` — full green, APK produced.
- **Manual end-to-end** (the real fix this plan delivers): configure
  `fcm_project_id`/`fcm_credentials` in `agent.toml` (same service-account
  key already used for the relay, or a fresh download of the same one);
  install the updated APK; pair a phone to direct mode only (no relay slot
  paired at all, or explicitly clear the relay slot from
  `ConnectionSettingsScreen` first); trigger a real blocking prompt in a
  disposable workspace; confirm the notification arrives, with the Mac
  agent's log showing `"agent: direct-mode FCM push enabled for project
  ..."` at startup; confirm it still arrives with the relay connection
  temporarily blocked/killed, proving no relay involvement in the delivery
  path. Confirm a relay-only phone's push still works completely unchanged.
