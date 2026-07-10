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
	"github.com/sodre90/cmux-bridge/internal/wire"
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

	poll := func() wire.PairingCodeStatusResp {
		resp, err := http.Get(srv.URL + "/agent/pairing-code/" + code)
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
	var body wire.PairingCodeInfoResp
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
	var got wire.DevicePairResp
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Token == "" || got.TenantID != tenantID {
		t.Fatalf("unexpected response: %+v", got)
	}
	dev, err := store.Verify(got.Token)
	if err != nil || dev.Name != "my-phone" || dev.DevicePubkey != "device-pubkey-b64" {
		t.Fatalf("unexpected device: %+v (err=%v)", dev, err)
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
