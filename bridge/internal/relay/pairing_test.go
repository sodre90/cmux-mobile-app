package relay

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/wire"
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

func TestDevicePairRedeemsCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `","device_pubkey":"device-pubkey-b64","name":"my-phone"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var got wire.DevicePairResp
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Token == "" {
		t.Fatal("expected a non-empty device token")
	}
	if got.TenantID != tenantID {
		t.Fatalf("TenantID = %q, want %q", got.TenantID, tenantID)
	}
	dev, err := store.Verify(got.Token)
	if err != nil {
		t.Fatalf("returned token should verify: %v", err)
	}
	if dev.Name != "my-phone" || dev.DevicePubkey != "device-pubkey-b64" || dev.TenantID != tenantID {
		t.Fatalf("unexpected device: %+v", dev)
	}
}

// TestDevicePairRateLimitedPerIP proves the accept criterion for
// docs/improvement-guide.md §6.4: a legitimate pairing attempt succeeds, and
// a second attempt from the same source within devicePairMinInterval is
// throttled with the same 429/rate_limited shape as /tenants/register's
// existing per-IP limiter -- even though the second code is itself
// perfectly valid, proving the limiter (not code validation) is what blocks
// it.
func TestDevicePairRateLimitedPerIP(t *testing.T) {
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

	pair := func(code, devicePubkey string) (status int, body string) {
		reqBody := `{"code":"` + code + `","device_pubkey":"` + devicePubkey + `","name":"my-phone"}`
		resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(reqBody))
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatal(err)
		}
		return resp.StatusCode, string(b)
	}

	code1, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if status, _ := pair(code1, "device-pubkey-b64"); status != http.StatusOK {
		t.Fatalf("first pairing attempt: want 200, got %d", status)
	}

	code2, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	status, body := pair(code2, "device-pubkey-b64-2")
	if status != http.StatusTooManyRequests {
		t.Fatalf("second pairing attempt from the same IP within the interval (a fresh, otherwise-valid code): want 429, got %d", status)
	}
	if !strings.Contains(body, "rate_limited") {
		t.Fatalf("body = %q, want it to contain rate_limited", body)
	}
}

func TestDevicePairDefaultsName(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `","device_pubkey":"device-pubkey-b64"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var got wire.DevicePairResp
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	dev, err := store.Verify(got.Token)
	if err != nil || dev.Name != "phone" {
		t.Fatalf("expected default name %q, got device: %+v err=%v", "phone", dev, err)
	}
}

func TestDevicePairRejectsUnknownCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"bogus","device_pubkey":"x"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("want 410, got %d", resp.StatusCode)
	}
}

func TestDevicePairRejectsNoDevicePubkey(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for missing device_pubkey, got %d", resp.StatusCode)
	}
}

func TestDevicePairWorksWithNoClientCert(t *testing.T) {
	// /devices/pair must be reachable by a phone presenting no client cert at
	// all (X-Client-Cert-Cn absent) -- this is the whole point of the
	// self-service flow. notAgent/auth.Require must never gate this route.
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `","device_pubkey":"device-pubkey-b64"}`
	req, _ := http.NewRequest("POST", srv.URL+"/devices/pair", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	// Deliberately no X-Client-Cert-Cn header.
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200 with no client cert, got %d", resp.StatusCode)
	}
}

func TestNewPairingCodeRejectsUnverifiedCert(t *testing.T) {
	// The regression test for the cross-tenant impersonation gap: once
	// ssl_verify_client is optional, nginx forwards X-Client-Cert-CN for any
	// presented certificate, verified or not. A correct CN with no (or a
	// failed) X-Client-Cert-Verify must still be rejected.
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

	for _, verify := range []string{"", "FAILED:self-signed certificate", "NONE"} {
		req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
		req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
		if verify != "" {
			req.Header.Set("X-Client-Cert-Verify", verify)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("X-Client-Cert-Verify=%q: want 403 for an unverified cert with a correct CN, got %d", verify, resp.StatusCode)
		}
	}
}

func TestHandleTunnelRejectsUnverifiedAgentCert(t *testing.T) {
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

	req, _ := http.NewRequest("GET", srv.URL+"/agent/tunnel", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	// Deliberately no X-Client-Cert-Verify: SUCCESS -- a spoofed self-signed
	// cert with the right CN must not be enough to open a tunnel.
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for an unverified agent cert, got %d", resp.StatusCode)
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

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", strings.NewReader(`{"agent_pubkey":"agent-pubkey-b64"}`))
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var body wire.PairingCodeResp
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

func TestNewPairingCodeRejectsMissingAgentPubkey(t *testing.T) {
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
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for missing agent_pubkey, got %d", resp.StatusCode)
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
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
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

	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	poll := func() wire.PairingCodeStatusResp {
		req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
		req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
		req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("want 200, got %d", resp.StatusCode)
		}
		var body wire.PairingCodeStatusResp
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
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
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
	code, err := store.NewPairingCode(tenantA, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantB)
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
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
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
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

func TestPairingCodeInfoReturnsAgentPubkey(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	// Deliberately no X-Client-Cert-Cn -- a phone pairing manually has no
	// cert yet, same as /devices/pair itself.
	resp, err := http.Get(srv.URL + "/devices/pair-info/" + code)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var body wire.PairingCodeInfoResp
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.AgentPubkey != "agent-pubkey-b64" {
		t.Fatalf("AgentPubkey = %q, want %q", body.AgentPubkey, "agent-pubkey-b64")
	}
	if body.TenantID != tenantID {
		t.Fatalf("TenantID = %q, want %q", body.TenantID, tenantID)
	}
	if body.ExpiresAt == "" {
		t.Fatal("expected a non-empty ExpiresAt")
	}
}

func TestPairingCodeInfoRejectsUnknownCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
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

func TestPairingCodeInfoRejectsExpiredCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", -time.Second) // already expired
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/devices/pair-info/" + code)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("want 410 for an expired code, got %d", resp.StatusCode)
	}
}

func TestPairingCodeInfoRejectsRedeemedCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, "agent-pubkey-b64", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, ok := store.RedeemPairingCode(code, "phone", "device-pubkey-b64"); !ok {
		t.Fatal("redeem should succeed")
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/devices/pair-info/" + code)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("want 410 for an already-redeemed code, got %d", resp.StatusCode)
	}
}
