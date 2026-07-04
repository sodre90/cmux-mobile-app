package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
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

func newDirectRegisterTestServer(t *testing.T) (*Server, *auth.Store) {
	t.Helper()
	store, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	s := New(config.Config{}, &cmux.Client{}, store)
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

// TestHandleRegisterDeviceAcceptsRealEncryptedEnvelope exercises
// /devices/register through DirectHandler with SetSessions wired up, the way
// runAgent's production configuration always has it (agent.go's runAgent
// unconditionally calls SetSessions with a real e2e.Store). Every other test
// above uses newDirectRegisterTestServer, which never calls SetSessions, so
// they only ever exercised the plaintext passthrough (s.sessions == nil) --
// a configuration direct mode never actually runs in. This test sends a
// genuinely e2e-encrypted envelope, the same way a correctly slot-aware
// Android E2eInterceptor now does on the DIRECT slot, and confirms the FCM
// token really lands in the auth store through the real encrypted pipeline.
func TestHandleRegisterDeviceAcceptsRealEncryptedEnvelope(t *testing.T) {
	authStore, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))

	tok, secret := directPairedDevice(t, authStore, sessions)
	dev, ok := authStore.Verify(tok)
	if !ok {
		t.Fatal("issued token should verify")
	}

	s := New(config.Config{}, &cmux.Client{}, authStore)
	s.SetSessions(sessions)

	plaintextReq := []byte(`{"fcm_token":"fcm-real"}`)
	ct, err := e2e.Seal(secret, e2e.Nonce(e2e.DirDeviceToAgent, 0), plaintextReq)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	envelope, err := json.Marshal(struct {
		V  int    `json:"v"`
		N  uint64 `json:"n"`
		CT string `json:"ct"`
	}{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal envelope: %v", err)
	}

	srv := httptest.NewServer(s.DirectHandler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/devices/register", strings.NewReader(string(envelope)))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200 for a real e2e-encrypted /devices/register request, got %d", resp.StatusCode)
	}

	tokens := authStore.TenantFCMTokens(dev.TenantID)
	if len(tokens) != 1 || tokens[0] != "fcm-real" {
		t.Fatalf("fcm token did not make it through the encrypted pipeline into the auth store: %+v", tokens)
	}
}
