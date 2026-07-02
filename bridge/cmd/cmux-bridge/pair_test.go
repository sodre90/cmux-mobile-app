package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/e2e"
)

func testDevicePubkeyB64(t *testing.T) string {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate device key: %v", err)
	}
	return base64.StdEncoding.EncodeToString(priv.PublicKey().Bytes())
}

// fakePairingRelay serves the two agent-facing pairing endpoints pairDevice
// calls. The GET poll handler returns 500 for the first failFirstN polls
// (simulating a transient relay hiccup), reports "redeemed":false until the
// redeemAfter'th poll, and "redeemed":true from then on. polls counts total
// GET calls for the test to assert retry/poll-count behavior.
func fakePairingRelay(t *testing.T, code, devicePubkeyB64 string, redeemAfter, failFirstN int) (*httptest.Server, *int32) {
	t.Helper()
	var polls int32
	mux := http.NewServeMux()
	mux.HandleFunc("POST /agent/pairing-code", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"code": code, "expires_at": "2099-01-01T00:00:00Z", "tenant_id": "fake-tenant-id"})
	})
	mux.HandleFunc("GET /agent/pairing-code/"+code, func(w http.ResponseWriter, r *http.Request) {
		n := int(atomic.AddInt32(&polls, 1))
		if n <= failFirstN {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if n < redeemAfter {
			_ = json.NewEncoder(w).Encode(map[string]any{"redeemed": false})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"redeemed":      true,
			"device_pubkey": devicePubkeyB64,
			"token_hash":    "fake-token-hash",
		})
	})
	return httptest.NewServer(mux), &polls
}

func TestPairDeviceStopsOnRedemption(t *testing.T) {
	devicePub := testDevicePubkeyB64(t)
	srv, polls := fakePairingRelay(t, "CODE1234", devicePub, 2, 0)
	defer srv.Close()

	identity, err := e2e.LoadOrCreateIdentity(filepath.Join(t.TempDir(), "identity.key"))
	if err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))

	err = pairDevice(srv.Client(), srv.URL, "https://phone.example/devices/pair", identity, sessions, io.Discard, 10*time.Millisecond, time.Now().Add(2*time.Second))
	if err != nil {
		t.Fatalf("pairDevice: %v", err)
	}
	if got := atomic.LoadInt32(polls); got < 2 {
		t.Fatalf("expected pairDevice to poll at least twice before redemption, got %d", got)
	}
	if _, ok := sessions.SharedSecret("fake-token-hash"); !ok {
		t.Fatal("expected pairDevice to persist a shared secret for the redeemed device")
	}
}

func TestPairDeviceRetriesOnTransientError(t *testing.T) {
	devicePub := testDevicePubkeyB64(t)
	// Poll #1 and #2 return 500; poll #3 reports redeemed.
	srv, polls := fakePairingRelay(t, "CODE1234", devicePub, 1, 2)
	defer srv.Close()

	identity, err := e2e.LoadOrCreateIdentity(filepath.Join(t.TempDir(), "identity.key"))
	if err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))

	err = pairDevice(srv.Client(), srv.URL, "https://phone.example/devices/pair", identity, sessions, io.Discard, 10*time.Millisecond, time.Now().Add(2*time.Second))
	if err != nil {
		t.Fatalf("pairDevice: %v", err)
	}
	if got := atomic.LoadInt32(polls); got < 3 {
		t.Fatalf("expected pairDevice to survive 2 transient errors and keep polling, got %d polls", got)
	}
}

func TestPairDeviceTimesOut(t *testing.T) {
	devicePub := testDevicePubkeyB64(t)
	// redeemAfter is unreachably far given the short deadline below.
	srv, _ := fakePairingRelay(t, "CODE1234", devicePub, 1000000, 0)
	defer srv.Close()

	identity, err := e2e.LoadOrCreateIdentity(filepath.Join(t.TempDir(), "identity.key"))
	if err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))

	err = pairDevice(srv.Client(), srv.URL, "https://phone.example/devices/pair", identity, sessions, io.Discard, 10*time.Millisecond, time.Now().Add(50*time.Millisecond))
	if err == nil {
		t.Fatal("expected pairDevice to time out")
	}
}

func TestHttpsBaseFromRelayURL(t *testing.T) {
	cases := map[string]string{
		"wss://cmux.example.com/agent/tunnel": "https://cmux.example.com",
		"ws://localhost:8765/agent/tunnel":    "http://localhost:8765",
	}
	for in, want := range cases {
		got, err := httpsBaseFromRelayURL(in)
		if err != nil {
			t.Fatalf("httpsBaseFromRelayURL(%q): %v", in, err)
		}
		if got != want {
			t.Fatalf("httpsBaseFromRelayURL(%q) = %q, want %q", in, got, want)
		}
	}
	if _, err := httpsBaseFromRelayURL("not-a-url-with-bad-scheme://x"); err == nil {
		t.Fatal("expected an error for a non-ws(s) scheme")
	}
}

func TestDevicePairURLFromBootstrap(t *testing.T) {
	got, err := devicePairURLFromBootstrap("https://cmux.example.com:8444/tenants/register")
	if err != nil {
		t.Fatal(err)
	}
	if want := "https://cmux.example.com:8444/devices/pair"; got != want {
		t.Fatalf("devicePairURLFromBootstrap = %q, want %q", got, want)
	}
}
