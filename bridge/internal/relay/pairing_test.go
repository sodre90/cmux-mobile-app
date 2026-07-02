package relay

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

func TestNewPairingCodeRequiresAgentCN(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=phone")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for a non-agent CN, got %d", resp.StatusCode)
	}
}

func TestNewPairingCodeIssuesCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
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

func TestNewPairingCodeRejectsRevokedTenant(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	store.RevokeTenant(tenantID)
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for a revoked tenant, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusPendingThenRedeemed(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	poll := func() pairingCodeStatusResp {
		req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
		req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
		resp, err := http.DefaultClient.Do(req)
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

func TestPairingCodeStatusUnknownCodeIs404(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/bogus", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusScopedToOwnTenant(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantA, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tenantB, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantA, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantB)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("tenant B must not see tenant A's pairing code, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusRequiresAgentCN(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=phone")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for a non-agent CN, got %d", resp.StatusCode)
	}
}
