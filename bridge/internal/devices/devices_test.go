package devices_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/devices"
	"github.com/sodre90/cmux-bridge/internal/pairing"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

const testPubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

func newStore(t *testing.T) *auth.Store {
	t.Helper()
	s, err := auth.Open(filepath.Join(t.TempDir(), "store.db"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func newTenant(t *testing.T, s *auth.Store) string {
	t.Helper()
	id, err := s.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// mount builds a server with the tenant resolution direct mode uses -- the
// constant resolver that never rejects.
func mount(t *testing.T, s *auth.Store, tenantID string) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	devices.Mount(mux, s, pairing.ConstantTenant(tenantID))
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// mountRejecting builds a server whose resolver always refuses, standing in
// for the relay's agentOnly turning away a caller with no verified agent cert.
func mountRejecting(t *testing.T, s *auth.Store) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	devices.Mount(mux, s, func(*http.Request) (string, bool) { return "", false })
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func TestListReportsTheTenantsDevices(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, err := s.Issue(tenant, "phone", testPubkey)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetFCMToken(tok, "fcm-value"); err != nil {
		t.Fatal(err)
	}
	srv := mount(t, s, tenant)

	resp, err := srv.Client().Get(srv.URL + "/agent/devices")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var body wire.AgentDeviceListResp
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Devices) != 1 {
		t.Fatalf("want 1 device, got %d", len(body.Devices))
	}
	got := body.Devices[0]
	if got.Name != "phone" {
		t.Fatalf("Name = %q, want %q", got.Name, "phone")
	}
	if got.TokenHash == "" || got.CreatedAt == "" {
		t.Fatalf("device is missing the fields revoke needs: %+v", got)
	}
	if !got.HasFCM {
		t.Fatal("HasFCM should be true once an FCM token is registered")
	}
}

// The listing is the operator's only route to a revocable identifier, but it
// must not become a route to the credential itself.
func TestListNeverCarriesTheFCMTokenOrTheBearerToken(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, err := s.Issue(tenant, "phone", testPubkey)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetFCMToken(tok, "fcm-secret-value"); err != nil {
		t.Fatal(err)
	}
	srv := mount(t, s, tenant)

	resp, err := srv.Client().Get(srv.URL + "/agent/devices")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	var raw map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(raw)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{tok, "fcm-secret-value"} {
		if strings.Contains(string(encoded), secret) {
			t.Fatalf("device listing leaked a secret: %s", encoded)
		}
	}
}

func TestRevokeRemovesTheDevice(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, err := s.Issue(tenant, "phone", testPubkey)
	if err != nil {
		t.Fatal(err)
	}
	dev, err := s.Verify(tok)
	if err != nil {
		t.Fatal(err)
	}
	srv := mount(t, s, tenant)

	resp, err := srv.Client().Post(srv.URL+"/agent/devices/"+dev.TokenHash+"/revoke", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if _, err := s.Verify(tok); err == nil {
		t.Fatal("revoked token must stop verifying")
	}
}

func TestRevokeOfAnUnknownDeviceIs404(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	srv := mount(t, s, tenant)

	resp, err := srv.Client().Post(srv.URL+"/agent/devices/nope/revoke", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", resp.StatusCode)
	}
}

// The relay's resolver refuses anything without a verified agent cert; both
// routes must fail closed on that, not fall through to some default tenant.
func TestBothRoutesRefuseAnUnresolvedTenant(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, err := s.Issue(tenant, "phone", testPubkey)
	if err != nil {
		t.Fatal(err)
	}
	dev, err := s.Verify(tok)
	if err != nil {
		t.Fatal(err)
	}
	srv := mountRejecting(t, s)

	listResp, err := srv.Client().Get(srv.URL + "/agent/devices")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = listResp.Body.Close() }()
	if listResp.StatusCode != http.StatusForbidden {
		t.Fatalf("list status = %d, want 403", listResp.StatusCode)
	}

	revokeResp, err := srv.Client().Post(srv.URL+"/agent/devices/"+dev.TokenHash+"/revoke", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = revokeResp.Body.Close() }()
	if revokeResp.StatusCode != http.StatusForbidden {
		t.Fatalf("revoke status = %d, want 403", revokeResp.StatusCode)
	}
	if _, err := s.Verify(tok); err != nil {
		t.Fatalf("a refused revoke must leave the device intact: %v", err)
	}
}
