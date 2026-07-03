# Tailscale-Direct Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `cmux-bridge agent` a second, optional listener that serves the
same API directly over a Tailscale tailnet — no relay, no home server — while
leaving the existing relay path completely unchanged.

**Architecture:** `runAgent` gains a second always-on goroutine (alongside
the existing relay dial loop) that opens a TCP listener, wraps it in TLS
using the Mac's already-running Tailscale client to auto-provision a real
Let's Encrypt cert (`tailscale.com/client/tailscale.LocalClient.GetCertificate`),
and serves a new `Server.DirectListenHandler`. That handler composes the
existing, unmodified `encryptionMiddleware` and route table with one new
small adapter (`injectDeviceID`) that lets per-device bearer-token auth
(`auth.Require`, already fully standalone) stand in for the relay's proxy as
the source of truth for `X-Device-ID`. Pairing gets its own small set of
handlers, each a near-verbatim port of an existing `internal/relay`
handler, backed by the agent's own local `auth.Store` instead of the
relay's. The Android app needs no changes at all.

**Tech Stack:** Go 1.26, `tailscale.com/client/tailscale` (new dependency,
`LocalClient` only — no `tsnet`), existing `internal/auth`, `internal/e2e`,
`internal/server` packages.

## Global Constraints

- **Additive only.** Nothing about `RelayURL`, `dialAndServe`, `TrustedHandler`,
  or any existing relay/push code path changes. `direct_listen` unset (the
  default) must produce byte-for-byte the same `runAgent` behavior as today.
- **No Android changes.** This plan touches `bridge/` only. If any step
  seems to need an Android change, stop and flag it — per the design spec
  (`docs/superpowers/specs/2026-07-03-tailscale-direct-transport-design.md`)
  this was confirmed not to be necessary.
- **No `tsnet`, no embedded WireGuard.** Only `tailscale.com/client/tailscale`
  (`LocalClient`), which talks to the Mac's already-running system
  `tailscaled` over its local API socket.
- **Reuse existing primitives verbatim wherever the design doc says so** —
  `auth.Store`'s pairing-code methods, `e2e.Store`, `encryptionMiddleware`,
  and `cmd/cmux-bridge/pair.go`'s `pairDevice()` function are not to be
  reimplemented, only re-wired.
- **JSON wire shapes for the four new pairing endpoints must exactly match**
  the existing relay endpoints of the same name
  (`internal/relay/relay.go`'s `pairingCodeResp`, `newPairingCodeReq`,
  `pairingCodeStatusResp`, `pairingCodeInfoResp`, `devicePairReq`,
  `devicePairResp`) — Android's `PairingClient.kt` and `cmd/cmux-bridge/pair.go`
  both depend on these shapes today and must work against either backend
  unmodified.
- Every new Go file goes through `gofmt`; every task ends `go build ./...`
  and `go test ./...` clean from the `bridge/` directory.

---

### Task 1: Direct-mode config fields

**Files:**
- Modify: `bridge/internal/config/agent.go`
- Test: `bridge/internal/config/agent_test.go`

**Interfaces:**
- Produces: `AgentConfig.DirectListen string` (toml `direct_listen`, no
  default, empty = direct mode disabled) and `AgentConfig.DirectAuthStore
  string` (toml `direct_auth_store`, defaults to
  `~/.config/cmux-bridge/direct-auth.db`, home-expanded like the other path
  fields). Task 4 reads both.

- [ ] **Step 1: Write the failing tests**

Add to `bridge/internal/config/agent_test.go`:

```go
func TestLoadAgentDefaultsDirectAuthStorePath(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DirectListen != "" {
		t.Fatalf("DirectListen default = %q, want empty (direct mode off by default)", cfg.DirectListen)
	}
	if cfg.DirectAuthStore == "" || strings.Contains(cfg.DirectAuthStore, "~") {
		t.Fatalf("DirectAuthStore default not expanded: %q", cfg.DirectAuthStore)
	}
	if !strings.HasSuffix(cfg.DirectAuthStore, "direct-auth.db") {
		t.Fatalf("DirectAuthStore = %q", cfg.DirectAuthStore)
	}
}

func TestLoadAgentParsesDirectFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
direct_listen     = ":8443"
direct_auth_store = "/c/direct-auth.db"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DirectListen != ":8443" {
		t.Fatalf("DirectListen = %q", cfg.DirectListen)
	}
	if cfg.DirectAuthStore != "/c/direct-auth.db" {
		t.Fatalf("DirectAuthStore = %q", cfg.DirectAuthStore)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/config/... -run TestLoadAgent -v`
Expected: FAIL — `cfg.DirectListen`/`cfg.DirectAuthStore` undefined (compile
error).

- [ ] **Step 3: Add the fields**

In `bridge/internal/config/agent.go`, add to the `AgentConfig` struct (after
`YoloStore`):

```go
	// DirectListen is the address the agent listens on for direct
	// (Tailscale) connections, e.g. ":8443". Empty (the default) disables
	// direct mode entirely — the agent behaves exactly as it does today,
	// relay-only.
	DirectListen string `toml:"direct_listen"`
	// DirectAuthStore is the path to direct mode's own local SQLite device
	// store (internal/auth.Store) — deliberately separate from any
	// relay-shaped state, since direct mode has exactly one implicit tenant
	// (this Mac).
	DirectAuthStore string `toml:"direct_auth_store"`
```

In `agentDefaults()`, add:

```go
		DirectAuthStore: "~/.config/cmux-bridge/direct-auth.db",
```

In `LoadAgent`, add alongside the other `expandHome` calls:

```go
	cfg.DirectAuthStore = expandHome(cfg.DirectAuthStore)
```

(`DirectListen` is a listen address, not a path — no expansion.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/config/... -v`
Expected: PASS, all tests in the package.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/config/agent.go bridge/internal/config/agent_test.go
git commit -m "bridge: add direct_listen/direct_auth_store config fields"
```

---

### Task 2: `injectDeviceID` middleware + `Server.DirectHandler`

**Files:**
- Create: `bridge/internal/server/direct.go`
- Test: `bridge/internal/server/direct_test.go`

**Interfaces:**
- Consumes: `Server.routes(wrap func(http.Handler) http.Handler) http.Handler`
  (`trusted.go:31`), `Server.encryptionMiddleware(next http.Handler)
  http.Handler` (`encryption.go:32`), `Server.store *auth.Store` (already a
  field on `Server`, `server.go:21`), `auth.Require(s *auth.Store, next
  http.Handler) http.Handler` and `auth.DeviceFromContext(ctx)
  (auth.Device, bool)` (`internal/auth/middleware.go`).
- Produces: `Server.DirectHandler() http.Handler` — the authenticated route
  set for direct mode (everything `routes()` already serves, i.e.
  `/sessions`, `/events`, `/terminal/{id}`, `/feed/pending`,
  `/feed/{id}/reply`, `/sessions/{id}/rename`, `/sessions/{id}/yolo-mode`).
  Task 3 mounts this as the fallback (`"/"`) of the direct listener's mux,
  alongside the pairing routes it registers itself.

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/server/direct_test.go`:

```go
package server

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/testutil"
)

// directPairedDevice issues a real bearer token via store, then pairs its
// e2e shared secret keyed by that token's real hash -- unlike
// encryption_test.go's pairedSessions (which uses a fixed fake deviceID),
// this proves the two stores agree on the SAME id the way DirectHandler's
// injectDeviceID actually produces it.
func directPairedDevice(t *testing.T, store *auth.Store, sessions *e2e.Store) (token string, secret []byte) {
	t.Helper()
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatalf("CreateTenant: %v", err)
	}
	agentPriv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate agent key: %v", err)
	}
	devicePriv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate device key: %v", err)
	}
	devicePubB64 := base64.StdEncoding.EncodeToString(devicePriv.PublicKey().Bytes())
	tok, err := store.Issue(tenantID, "phone", devicePubB64)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	dev, ok := store.Verify(tok)
	if !ok {
		t.Fatal("issued token should verify")
	}
	secret, err = e2e.DeriveSharedSecret(agentPriv, devicePriv.PublicKey())
	if err != nil {
		t.Fatalf("DeriveSharedSecret: %v", err)
	}
	if err := sessions.AddDevice(dev.TokenHash, devicePriv.PublicKey(), secret); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	return tok, secret
}

func TestDirectHandlerRejectsMissingBearerToken(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, "#!/bin/sh\necho '{}'\n")
	authStore, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	s := New(config.Config{}, &cmux.Client{Bin: bin}, authStore)
	s.SetSessions(e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json")))

	srv := httptest.NewServer(s.DirectHandler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/sessions")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 with no bearer token, got %d", resp.StatusCode)
	}
}

func TestDirectHandlerOverwritesForgedDeviceIDHeader(t *testing.T) {
	// Adversarial: a real, currently-paired device sends a valid bearer
	// token but a forged X-Device-ID naming a DIFFERENT (nonexistent)
	// device, with its request body encrypted under ITS OWN real secret.
	// injectDeviceID must overwrite the header with the token's own real
	// hash before encryptionMiddleware ever sees it -- if the forged value
	// won, decryption would fail with 409, not succeed.
	script := "#!/bin/sh\ncat <<'JSON'\n" + fakeWorkspaceList + "\nJSON\n"
	bin := testutil.WriteFakeCmux(t, script)
	authStore, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))
	s := New(config.Config{}, &cmux.Client{Bin: bin}, authStore)
	s.SetSessions(sessions)

	tok, _ := directPairedDevice(t, authStore, sessions)

	srv := httptest.NewServer(s.DirectHandler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/sessions", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("X-Device-ID", "someone-elses-hash")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200 (forged X-Device-ID must be overwritten by the verified token's own id), got %d", resp.StatusCode)
	}
}

func TestDirectHandlerRejectsUnpairedDevice(t *testing.T) {
	// A valid bearer token whose e2e shared secret was never registered
	// (e.g. auth succeeded but pairing's second half never completed) must
	// fail closed at the encryption layer, not fall back to plaintext.
	bin := testutil.WriteFakeCmux(t, "#!/bin/sh\necho '{}'\n")
	authStore, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	s := New(config.Config{}, &cmux.Client{Bin: bin}, authStore)
	s.SetSessions(e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json")))

	tenantID, err := authStore.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tok, err := authStore.Issue(tenantID, "phone", "unpaired-device-pubkey-b64")
	if err != nil {
		t.Fatal(err)
	}

	srv := httptest.NewServer(s.DirectHandler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/sessions", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("want 409 not_paired, got %d", resp.StatusCode)
	}
}
```

None of the three tests need a hand-built encrypted request body: `/sessions`
is a bodyless `GET`, and `encryptionMiddleware` only attempts to decrypt when
`r.ContentLength != 0`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/server/... -run TestDirectHandler -v`
Expected: FAIL — `s.DirectHandler` undefined (compile error).

- [ ] **Step 3: Implement `direct.go`**

Create `bridge/internal/server/direct.go`:

```go
package server

import (
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// injectDeviceID copies the bearer-token-verified Device's TokenHash (set by
// auth.Require, which must run before this) into the X-Device-ID header,
// overwriting any client-supplied value. This is what lets the existing,
// unmodified encryptionMiddleware -- which trusts X-Device-ID because only
// the relay's proxy Director used to be able to set it -- work safely when
// there's no relay in front: a real device can prove its own bearer token
// but must never be able to pick which device's shared secret its request
// gets decrypted against.
func injectDeviceID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dev, _ := auth.DeviceFromContext(r.Context())
		r.Header.Set("X-Device-ID", dev.TokenHash)
		next.ServeHTTP(w, r)
	})
}

// DirectHandler is the authenticated route set served directly over
// Tailscale, with no relay in the path: per-device bearer-token auth
// (auth.Require, keyed on s.store) replaces the relay-token check, and
// injectDeviceID lets the existing encryptionMiddleware run unmodified.
// Order matters: auth.Require must resolve the token (needs only the
// Authorization header) before injectDeviceID can read it from context,
// and injectDeviceID must set X-Device-ID before encryptionMiddleware reads
// it to decrypt the body.
func (s *Server) DirectHandler() http.Handler {
	wrap := func(h http.Handler) http.Handler {
		return auth.Require(s.store, injectDeviceID(s.encryptionMiddleware(h)))
	}
	return s.routes(wrap)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/server/... -v`
Expected: PASS, entire package (this also re-runs every pre-existing
`internal/server` test — none should be affected, since `DirectHandler` is
new and additive).

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/direct.go bridge/internal/server/direct_test.go
git commit -m "bridge: add DirectHandler for relay-less direct-mode auth+e2e"
```

---

### Task 3: Direct-mode pairing HTTP handlers

**Files:**
- Create: `bridge/internal/server/direct_pairing.go`
- Test: `bridge/internal/server/direct_pairing_test.go`

**Interfaces:**
- Consumes: `auth.Store.NewPairingCode(tenantID, agentPubkey string, ttl
  time.Duration) (string, error)`, `auth.Store.PairingCodeStatus(tenantID,
  code string) (devicePubkey, tokenHash string, redeemed, ok bool)`,
  `auth.Store.PairingCodeInfo(code string) (agentPubkey, tenantID,
  expiresAt string, ok bool)`, `auth.Store.RedeemPairingCode(code, name,
  devicePubkey string) (token, tenantID string, ok bool)` (all existing,
  `internal/auth/store.go`).
- Produces: `MountDirectPairing(mux *http.ServeMux, store *auth.Store,
  tenantID string)` — registers the four pre-auth pairing routes onto a
  caller-supplied mux. Task 4 calls this, then mounts `Server.DirectHandler()`
  as that same mux's `"/"` fallback.

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/server/direct_pairing_test.go`:

```go
package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

func newDirectPairingServer(t *testing.T) (srv *httptest.Server, store *auth.Store, tenantID string) {
	t.Helper()
	store, err := auth.Open(filepath.Join(t.TempDir(), "direct-auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err = store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	MountDirectPairing(mux, store, tenantID)
	return httptest.NewServer(mux), store, tenantID
}

func TestDirectNewPairingCodeIssuesCode(t *testing.T) {
	srv, _, tenantID := newDirectPairingServer(t)
	defer srv.Close()

	resp, err := http.Post(srv.URL+"/agent/pairing-code", "application/json", strings.NewReader(`{"agent_pubkey":"agent-pubkey-b64"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var body pairingCodeResp
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Code == "" || body.ExpiresAt == "" {
		t.Fatalf("unexpected body: %+v", body)
	}
	if body.TenantID != tenantID {
		t.Fatalf("TenantID = %q, want %q", body.TenantID, tenantID)
	}
}

func TestDirectNewPairingCodeRejectsMissingAgentPubkey(t *testing.T) {
	srv, _, _ := newDirectPairingServer(t)
	defer srv.Close()

	resp, err := http.Post(srv.URL+"/agent/pairing-code", "application/json", strings.NewReader(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400, got %d", resp.StatusCode)
	}
}

func TestDirectPairingCodeStatusPendingThenRedeemed(t *testing.T) {
	srv, store, tenantID := newDirectPairingServer(t)
	defer srv.Close()

	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	poll := func() pairingCodeStatusResp {
		resp, err := http.Get(srv.URL + "/agent/pairing-code/" + code)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("want 200, got %d", resp.StatusCode)
		}
		var body pairingCodeStatusResp
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		return body
	}

	if got := poll(); got.Redeemed {
		t.Fatalf("code should not be redeemed yet: %+v", got)
	}

	tok, _, ok := store.RedeemPairingCode(code, "phone", "device-pubkey-b64")
	if !ok || tok == "" {
		t.Fatal("redeem should succeed")
	}

	got := poll()
	if !got.Redeemed || got.DevicePubkey != "device-pubkey-b64" {
		t.Fatalf("unexpected status after redeem: %+v", got)
	}
}

func TestDirectPairingCodeStatusUnknownCodeIs404(t *testing.T) {
	srv, _, _ := newDirectPairingServer(t)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/agent/pairing-code/bogus")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404, got %d", resp.StatusCode)
	}
}

func TestDirectPairingCodeInfoReturnsAgentPubkey(t *testing.T) {
	srv, store, tenantID := newDirectPairingServer(t)
	defer srv.Close()

	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	resp, err := http.Get(srv.URL + "/devices/pair-info/" + code)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var body pairingCodeInfoResp
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.AgentPubkey != "agent-pubkey-b64" || body.TenantID != tenantID {
		t.Fatalf("unexpected body: %+v", body)
	}
}

func TestDirectPairingCodeInfoRejectsUnknownCode(t *testing.T) {
	srv, _, _ := newDirectPairingServer(t)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/devices/pair-info/bogus")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("want 410, got %d", resp.StatusCode)
	}
}

func TestDirectDevicePairRedeemsCode(t *testing.T) {
	srv, store, tenantID := newDirectPairingServer(t)
	defer srv.Close()

	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	body := `{"code":"` + code + `","device_pubkey":"device-pubkey-b64","name":"my-phone"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var got devicePairResp
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Token == "" || got.TenantID != tenantID {
		t.Fatalf("unexpected response: %+v", got)
	}
	dev, ok := store.Verify(got.Token)
	if !ok || dev.Name != "my-phone" || dev.DevicePubkey != "device-pubkey-b64" {
		t.Fatalf("unexpected device: %+v", dev)
	}
}

func TestDirectDevicePairRejectsUnknownCode(t *testing.T) {
	srv, _, _ := newDirectPairingServer(t)
	defer srv.Close()

	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(`{"code":"bogus","device_pubkey":"x"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("want 410, got %d", resp.StatusCode)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/server/... -run TestDirect -v`
Expected: FAIL — `MountDirectPairing`, `pairingCodeResp`,
`pairingCodeStatusResp`, `pairingCodeInfoResp`, `devicePairResp` undefined
in package `server` (compile error) — these response types don't exist yet
in this package (they're currently private to `internal/relay`).

- [ ] **Step 3: Implement `direct_pairing.go`**

Create `bridge/internal/server/direct_pairing.go`:

```go
package server

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// pairingCodeTTL mirrors internal/relay/relay.go's constant of the same
// name -- how long a self-service pairing code stays redeemable.
const pairingCodeTTL = 10 * time.Minute

type pairingCodeResp struct {
	Code      string `json:"code"`
	ExpiresAt string `json:"expires_at"`
	TenantID  string `json:"tenant_id"`
}

type newPairingCodeReq struct {
	AgentPubkey string `json:"agent_pubkey"`
}

type pairingCodeStatusResp struct {
	Redeemed     bool   `json:"redeemed"`
	DevicePubkey string `json:"device_pubkey,omitempty"`
	TokenHash    string `json:"token_hash,omitempty"`
}

type pairingCodeInfoResp struct {
	AgentPubkey string `json:"agent_pubkey"`
	ExpiresAt   string `json:"expires_at"`
	TenantID    string `json:"tenant_id"`
}

type devicePairReq struct {
	Code         string `json:"code"`
	DevicePubkey string `json:"device_pubkey"`
	Name         string `json:"name"`
}

type devicePairResp struct {
	Token    string `json:"token"`
	TenantID string `json:"tenant_id"`
}

func writeDirectPairingErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(`{"error":"` + msg + `"}`))
}

// MountDirectPairing registers the four pre-auth pairing routes direct mode
// needs onto mux, backed by store and scoped to the single implicit tenant
// tenantID (direct mode has exactly one, created once at agent startup --
// see runAgent). Each handler is a near-verbatim port of the matching
// internal/relay/relay.go handler, minus that file's agentOnly/mTLS-CN
// tenant resolution: there's no second tenant to disambiguate from here,
// and the real access boundary for this whole listener is Tailscale's own
// network ACLs, not a per-request identity check on these four routes.
func MountDirectPairing(mux *http.ServeMux, store *auth.Store, tenantID string) {
	mux.Handle("POST /agent/pairing-code", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		var rq newPairingCodeReq
		if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.AgentPubkey == "" {
			writeDirectPairingErr(w, http.StatusBadRequest, "missing agent_pubkey")
			return
		}
		code, err := store.NewPairingCode(tenantID, rq.AgentPubkey, pairingCodeTTL)
		if err != nil {
			writeDirectPairingErr(w, http.StatusInternalServerError, "internal_error")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(pairingCodeResp{
			Code:      code,
			ExpiresAt: time.Now().Add(pairingCodeTTL).UTC().Format(time.RFC3339),
			TenantID:  tenantID,
		})
	}))

	mux.Handle("GET /agent/pairing-code/{code}", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		code := req.PathValue("code")
		pubkey, hash, redeemed, ok := store.PairingCodeStatus(tenantID, code)
		if !ok {
			writeDirectPairingErr(w, http.StatusNotFound, "not_found")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(pairingCodeStatusResp{
			Redeemed:     redeemed,
			DevicePubkey: pubkey,
			TokenHash:    hash,
		})
	}))

	mux.Handle("GET /devices/pair-info/{code}", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		code := req.PathValue("code")
		agentPubkey, tid, expiresAt, ok := store.PairingCodeInfo(code)
		if !ok {
			writeDirectPairingErr(w, http.StatusGone, "pairing_code_invalid")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(pairingCodeInfoResp{
			AgentPubkey: agentPubkey,
			ExpiresAt:   expiresAt,
			TenantID:    tid,
		})
	}))

	mux.Handle("POST /devices/pair", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		req.Body = http.MaxBytesReader(w, req.Body, 4<<10)
		var rq devicePairReq
		if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.Code == "" || rq.DevicePubkey == "" {
			writeDirectPairingErr(w, http.StatusBadRequest, "missing code or device_pubkey")
			return
		}
		name := rq.Name
		if name == "" {
			name = "phone"
		}
		tok, tid, ok := store.RedeemPairingCode(rq.Code, name, rq.DevicePubkey)
		if !ok {
			writeDirectPairingErr(w, http.StatusGone, "pairing_code_invalid")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(devicePairResp{Token: tok, TenantID: tid})
	}))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/server/... -v`
Expected: PASS, entire package.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/direct_pairing.go bridge/internal/server/direct_pairing_test.go
git commit -m "bridge: add local pairing-code handlers for direct mode"
```

---

### Task 4: `runAgent` wiring — direct listener + auto-tenant + Tailscale cert

**Files:**
- Modify: `bridge/cmd/cmux-bridge/agent.go`
- Modify: `bridge/go.mod` / `bridge/go.sum` (via `go get`)
- Test: `bridge/cmd/cmux-bridge/agent_test.go` (create if it doesn't exist
  — check first: if it exists, add to it instead)

**Interfaces:**
- Consumes: `auth.Open(path) (*auth.Store, error)`, `auth.Store.ListTenants()
  ([]auth.Tenant, error)`, `auth.Store.CreateTenant() (string, error)`
  (`internal/auth/store.go`), `Server.DirectHandler()` (Task 2),
  `MountDirectPairing` (Task 3), `tailscale.com/client/tailscale.LocalClient`.
- Produces: when `cfg.DirectListen != ""`, a live HTTPS listener serving the
  direct-mode API alongside the existing relay dial loop.

- [ ] **Step 1: Add the `tailscale.com` dependency**

Run: `cd bridge && go get tailscale.com/client/tailscale`
Expected: `go.mod`/`go.sum` gain `tailscale.com` and its transitive deps.
Then run `go build ./...` once just to confirm the module resolves and
nothing existing breaks — no new code references it yet.

- [ ] **Step 2: Write the failing test**

First check whether `bridge/cmd/cmux-bridge/agent_test.go` already exists
(`ls bridge/cmd/cmux-bridge/*_test.go`). If it doesn't, create it; if it
does, add this test to it.

```go
func TestRunDirectListenerAutoCreatesTenantOnce(t *testing.T) {
	dir := t.TempDir()
	storePath := filepath.Join(dir, "direct-auth.db")
	store, err := auth.Open(storePath)
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := ensureDirectTenant(store)
	if err != nil {
		t.Fatal(err)
	}
	if tenantID == "" {
		t.Fatal("expected a non-empty tenant id")
	}

	// Calling it again against the SAME store must reuse the existing
	// tenant, not mint a second one -- otherwise toggling direct_listen
	// on/off across restarts would orphan every previously paired device.
	again, err := ensureDirectTenant(store)
	if err != nil {
		t.Fatal(err)
	}
	if again != tenantID {
		t.Fatalf("ensureDirectTenant not idempotent: got %q then %q", tenantID, again)
	}
}
```

Add `"path/filepath"` and `"github.com/sodre90/cmux-bridge/internal/auth"`
to that test file's imports if not already present.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd bridge && go test ./cmd/cmux-bridge/... -run TestRunDirectListener -v`
Expected: FAIL — `ensureDirectTenant` undefined (compile error).

- [ ] **Step 4: Implement the direct-listener wiring in `agent.go`**

Add these imports to `bridge/cmd/cmux-bridge/agent.go`:

```go
	"crypto/tls"
	"net"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"tailscale.com/client/tailscale"
```
(`crypto/tls` and `net` may already be partly imported — merge with the
existing import block rather than duplicating.)

Add this function (anywhere in `agent.go`, e.g. just above `runAgent`):

```go
// ensureDirectTenant returns direct mode's single implicit tenant id,
// creating it once on first use. Idempotent across restarts: an existing
// tenant is always reused, so toggling direct_listen off and back on never
// orphans devices paired while it was on.
func ensureDirectTenant(store *auth.Store) (string, error) {
	tenants, err := store.ListTenants()
	if err != nil {
		return "", err
	}
	for _, t := range tenants {
		if !t.Revoked {
			return t.ID, nil
		}
	}
	return store.CreateTenant()
}

// serveDirect runs the direct (Tailscale) listener until ctx is canceled or
// the listener fails. It never affects the relay dial loop running
// alongside it in runAgent. store/tenantID back both the pairing routes and
// (via handler, already bound to the same store through Server.store)
// authenticated requests -- one auth.Store, opened once, for the whole
// listener.
func serveDirect(ctx context.Context, listenAddr string, store *auth.Store, tenantID string, handler http.Handler) error {
	mux := http.NewServeMux()
	server.MountDirectPairing(mux, store, tenantID)
	mux.Handle("/", handler)

	tcpLn, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return fmt.Errorf("direct mode: listen %s: %w", listenAddr, err)
	}
	lc := &tailscale.LocalClient{}
	tlsLn := tls.NewListener(tcpLn, &tls.Config{GetCertificate: lc.GetCertificate})

	log.Printf("agent: direct listener up on %s", listenAddr)
	errCh := make(chan error, 1)
	go func() { errCh <- http.Serve(tlsLn, mux) }()
	select {
	case <-ctx.Done():
		tlsLn.Close()
		return ctx.Err()
	case err := <-errCh:
		return err
	}
}
```

`fmt` needs adding to the import block if not already present.

In `runAgent`, right after the existing:

```go
	srv := server.New(config.Config{}, &cmux.Client{Bin: cfg.CmuxBin}, nil)
	srv.SetSessions(e2e.OpenStore(cfg.SessionStore))
	srv.SetYoloStore(yolo.OpenStore(cfg.YoloStore))
	go srv.RunEvents(ctx)
	handler := srv.TrustedHandler(cfg.RelayToken)
```

change the `server.New(...)` call's third argument from `nil` to a store
variable (opened once, shared by both `DirectHandler()`'s bearer-token
checks and the pairing routes), and spawn the new goroutine, so the block
reads:

```go
	var directStore *auth.Store // nil unless direct mode is on; Handler()/authWrap stay unused in production either way
	var directTenantID string
	if cfg.DirectListen != "" {
		var err error
		directStore, err = auth.Open(cfg.DirectAuthStore)
		if err != nil {
			log.Printf("agent: direct mode: open auth store: %v", err)
			return 1
		}
		directTenantID, err = ensureDirectTenant(directStore)
		if err != nil {
			log.Printf("agent: direct mode: ensure tenant: %v", err)
			return 1
		}
	}
	srv := server.New(config.Config{}, &cmux.Client{Bin: cfg.CmuxBin}, directStore)
	srv.SetSessions(e2e.OpenStore(cfg.SessionStore))
	srv.SetYoloStore(yolo.OpenStore(cfg.YoloStore))
	go srv.RunEvents(ctx)
	handler := srv.TrustedHandler(cfg.RelayToken)

	if cfg.DirectListen != "" {
		go func() {
			if err := serveDirect(ctx, cfg.DirectListen, directStore, directTenantID, srv.DirectHandler()); err != nil && ctx.Err() == nil {
				log.Printf("agent: direct listener ended: %v", err)
			}
		}()
	}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd bridge && go build ./... && go test ./... -v`
Expected: PASS, whole module. This also confirms `direct_listen` unset
(every existing test's config) takes the untouched `directStoreForServer =
nil` path — identical to `runAgent` before this task.

- [ ] **Step 6: Commit**

```bash
git add bridge/cmd/cmux-bridge/agent.go bridge/cmd/cmux-bridge/agent_test.go bridge/go.mod bridge/go.sum
git commit -m "bridge: wire direct (Tailscale) listener into runAgent"
```

---

### Task 5: CLI `pair-device --direct`

**Files:**
- Modify: `bridge/cmd/cmux-bridge/pair.go`

**Interfaces:**
- Consumes: `pairDevice(client *http.Client, agentBase, devicePairURL
  string, identity *e2e.Identity, sessions *e2e.Store, out io.Writer,
  pollPeriod time.Duration, deadline time.Time) error` (existing, pair.go:108
  — unchanged, called with different `client`/`agentBase` for `--direct`).

- [ ] **Step 1: Add the `--direct` flag and branch in `runPairDevice`**

In `bridge/cmd/cmux-bridge/pair.go`, add `"strings"` and
`"tailscale.com/client/tailscale"` to the imports, then replace
`runPairDevice`'s body from the `cfg.RelayURL == ""` check through building
`client` with:

```go
func runPairDevice(args []string) int {
	fs := flag.NewFlagSet("pair-device", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultAgentConfigPath(), "path to agent.toml")
	timeout := fs.Duration("timeout", 5*time.Minute, "how long to wait for the phone to scan and redeem the code")
	direct := fs.Bool("direct", false, "pair against this Mac's own direct (Tailscale) listener instead of the relay")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, err := config.LoadAgent(*cfgPath)
	if err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}

	var agentBase string
	var client *http.Client
	if *direct {
		if cfg.DirectListen == "" {
			log.Printf("pair-device: --direct requires direct_listen to be set in %s", *cfgPath)
			return 1
		}
		lc := &tailscale.LocalClient{}
		st, err := lc.Status(context.Background())
		if err != nil {
			log.Printf("pair-device: tailscale status: %v", err)
			return 1
		}
		if st.Self == nil || st.Self.DNSName == "" {
			log.Printf("pair-device: this Mac has no Tailscale DNS name yet -- is Tailscale up?")
			return 1
		}
		host := strings.TrimSuffix(st.Self.DNSName, ".")
		agentBase = "https://" + host + cfg.DirectListen
		// The direct listener's cert is a real, publicly-trusted Let's
		// Encrypt cert (tailscale cert) -- the default transport's system
		// root CAs already validate it, no client cert needed at all.
		client = &http.Client{}
	} else {
		if cfg.RelayURL == "" {
			log.Printf("pair-device: relay_url is required (or pass --direct)")
			return 1
		}
		agentBase, err = httpsBaseFromRelayURL(cfg.RelayURL)
		if err != nil {
			log.Printf("pair-device: %v", err)
			return 1
		}
		tlsCfg, err := loadTLS(cfg.ClientCert, cfg.ClientKey, cfg.CACert)
		if err != nil {
			log.Printf("pair-device: tls: %v", err)
			return 1
		}
		client = &http.Client{Transport: &http.Transport{TLSClientConfig: tlsCfg}}
	}
	// /devices/pair is public on the same vhost as the agent-facing
	// pairing-code endpoints in both modes.
	devicePairURL := agentBase + "/devices/pair"

	identity, err := e2e.LoadOrCreateIdentity(cfg.IdentityKey)
	if err != nil {
		log.Printf("pair-device: e2e identity: %v", err)
		return 1
	}
	sessions := e2e.OpenStore(cfg.SessionStore)

	if err := pairDevice(client, agentBase, devicePairURL, identity, sessions, os.Stdout, 2*time.Second, time.Now().Add(*timeout)); err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	return 0
}
```

Note `cfg.DirectListen` is an address like `:8443` (leading colon), so
`agentBase = "https://" + host + cfg.DirectListen` correctly produces
`https://mac.tailnetname.ts.net:8443` — no extra `:` needed. Add
`"context"` to the imports if not already present (it likely isn't in this
file today).

- [ ] **Step 2: Build and smoke-test the flag parses**

Run: `cd bridge && go build ./... && ./cmux-bridge pair-device --help 2>&1 | grep -A1 direct`

Since there's no live relay or Tailscale in this environment, a full
`pair-device --direct` run isn't testable here — this step only confirms
`-direct` is a recognized flag and the binary still builds. The command
should print the new flag's usage line (`-direct` ... "pair against this
Mac's own direct (Tailscale) listener instead of the relay"). Real
end-to-end pairing is exercised manually in the Verification section below,
against real devices.

- [ ] **Step 3: Run the full test suite once more**

Run: `cd bridge && go build ./... && go test ./...`
Expected: PASS, no regressions (this task adds no new automated test —
`pairDevice()` itself is unchanged and already covered where it's called
from existing tests, and the new branch's only untested logic is a thin
CLI-argument/HTTP-client selection that has no meaningful unit-testable
behavior without a live Tailscale daemon).

- [ ] **Step 4: Commit**

```bash
git add bridge/cmd/cmux-bridge/pair.go
git commit -m "bridge: add pair-device --direct for Tailscale-direct pairing"
```

---

### Task 6: Deploy examples + README docs

**Files:**
- Modify: `bridge/deploy/agent.example.toml`
- Modify: `bridge/README.md`
- Modify: `README.md` (repo root)

**Interfaces:** None — documentation only, no code.

- [ ] **Step 1: Update `bridge/deploy/agent.example.toml`**

Add, after the existing `bootstrap_url` comment line:

```toml
# Direct (Tailscale) mode -- optional, additive to the relay above. See
# bridge/README.md#direct-tailscale-mode for the one-time Tailscale setup
# this requires.
# direct_listen     = ":8443"
# direct_auth_store = "~/.config/cmux-bridge/direct-auth.db"
```

- [ ] **Step 2: Add a "Direct (Tailscale) mode" section to `bridge/README.md`**

Read the existing file first to match its heading level and style, then add
a new section (after the existing Pair-a-device section, matching this
plan's design doc verbatim for the setup steps):

```markdown
## Direct (Tailscale) mode

An optional, additive alternative to the relay above: if your Mac and phone
are both on the same [Tailscale](https://tailscale.com) tailnet, the phone
can talk straight to the Mac agent with no relay and no home server in the
path. The relay keeps working exactly as before — this is a second listener,
not a replacement, and push notifications still require the relay.

1. Install Tailscale on the Mac (Mac App Store, or `brew install --cask
   tailscale`) and run `tailscale up`.
2. In the [Tailscale admin console](https://login.tailscale.com/admin/dns),
   enable **MagicDNS** and, in the same DNS page's **HTTPS Certificates**
   section, enable HTTPS certificates for the tailnet.
3. Install the official Tailscale app from the Play Store on the phone and
   sign in to the same tailnet.
4. Confirm cert issuance works: `sudo tailscale cert
   $(tailscale status --json | jq -r .Self.DNSName)`.
5. Add to `agent.toml`: `direct_listen = ":8443"` (any free port), restart
   the agent.
6. Run `cmux-bridge pair-device --config ~/.config/cmux-bridge/agent.toml
   --direct`, then complete pairing on the phone (Settings → Enter server
   URL and code manually) using the printed
   `https://<mac>.<tailnet>.ts.net:8443` URL and code.

Switching between relay and direct mode is a manual re-pair (Settings screen
→ enter the other URL/code) — there's no automatic fallback in v1.
```

- [ ] **Step 3: Add one bullet to the repo-root `README.md`**

In the "How it fits together" or "What the app does" section (read the
current file to place it naturally alongside the existing YOLO
mode/rename/push bullets), add:

```markdown
- **Direct (Tailscale) mode** — an optional, additive alternative to the
  relay above: if the phone and Mac share a Tailscale tailnet, the app can
  talk straight to the Mac agent with no relay or home server involved. See
  [bridge/README.md → Direct (Tailscale) mode](bridge/README.md#direct-tailscale-mode).
```

- [ ] **Step 4: Commit**

```bash
git add bridge/deploy/agent.example.toml bridge/README.md README.md
git commit -m "docs: document direct (Tailscale) mode setup"
```

---

## Verification

- `cd bridge && go build ./... && go test ./...` — clean after every task
  above; run once more after Task 6 as a final sanity check (docs-only, but
  costs nothing to confirm).
- Manual end-to-end (requires real Tailscale account + Mac + Android phone,
  not reproducible in CI):
  1. Complete the six setup steps in `bridge/README.md`'s new section.
  2. Confirm the sessions list, a live terminal, and a feed reply all work
     with the phone paired against the direct URL.
  3. Stop `cmux-relay` (or otherwise cut the Mac's relay connectivity) while
     keeping Tailscale up, and confirm the app still works — proves the
     relay is genuinely not in the path.
  4. Separately, with the relay back up and the phone still paired in direct
     mode, trigger a permission prompt and confirm a push notification still
     arrives — proves push's independence from the currently-paired
     transport.
  5. Confirm re-pairing (Settings → enter the relay's URL/code again)
     switches back cleanly.
