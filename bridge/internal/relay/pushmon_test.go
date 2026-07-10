package relay

import (
	"context"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

type fakePusher struct {
	mu     sync.Mutex
	tokens []string
	calls  []map[string]string
}

func (p *fakePusher) Send(_ context.Context, tok, _, _ string, data map[string]string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.tokens = append(p.tokens, tok)
	p.calls = append(p.calls, data)
	return nil
}

func TestMonitorAgentPushesAttention(t *testing.T) {
	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}

	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/events" || r.Header.Get("X-Relay-Token") != "tok" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			ws, err := up.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			_ = ws.WriteJSON(wire.EventFrame{
				Type: "feed", NeedsAttention: true, FeedID: "F1",
				Kind: "permissionRequest", Title: "Run rm -rf?",
			})
			time.Sleep(500 * time.Millisecond)
		}))
	}()

	store, err := auth.Open(t.TempDir() + "/d.db")
	if err != nil {
		t.Fatal(err)
	}
	tenant, _ := store.CreateTenant()
	tok, _ := store.Issue(tenant, "phone", "test-device-pubkey-b64")
	store.SetFCMToken(tok, "fcm-123")

	fp := &fakePusher{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go MonitorAgent(ctx, tenant, relaySess, "tok", store, fp)

	waitFor(t, func() bool {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		return len(fp.calls) > 0
	})
	fp.mu.Lock()
	defer fp.mu.Unlock()
	got := fp.calls[0]
	if got["type"] != "attention" || got["feed_id"] != "F1" || got["kind"] != "permissionRequest" {
		t.Fatalf("unexpected push data: %+v", got)
	}
	if got["slot"] != "relay" {
		t.Fatalf("want slot=relay, got %+v", got)
	}
	if _, ok := got["e2e"]; ok {
		t.Fatalf("frame carried no EncryptedPush entry for this device -- e2e key must be absent, got %+v", got)
	}
}

// TestMonitorAgentForwardsEncryptedPushForKnownDevice proves fanout looks up
// the receiving device's own entry in the frame's EncryptedPush map (built
// agent-side, see server.buildEncryptedPush) and forwards only that opaque
// ciphertext -- the relay itself never computes or sees plaintext
// title/body, satisfying the blind-relay guarantee even though it's the one
// actually calling FCM.
func TestMonitorAgentForwardsEncryptedPushForKnownDevice(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/d.db")
	if err != nil {
		t.Fatal(err)
	}
	tenant, _ := store.CreateTenant()
	tok, _ := store.Issue(tenant, "phone", "test-device-pubkey-b64")
	store.SetFCMToken(tok, "fcm-123")
	devices := store.TenantFCMDevices(tenant)
	if len(devices) != 1 {
		t.Fatalf("expected exactly one FCM device, got %+v", devices)
	}
	deviceID := devices[0].DeviceID

	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}

	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/events" || r.Header.Get("X-Relay-Token") != "tok" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			ws, err := up.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			_ = ws.WriteJSON(wire.EventFrame{
				Type: "feed", NeedsAttention: true, FeedID: "F1",
				Kind:          "permissionRequest",
				EncryptedPush: map[string]string{deviceID: "cipher-blob"},
			})
			time.Sleep(500 * time.Millisecond)
		}))
	}()

	fp := &fakePusher{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go MonitorAgent(ctx, tenant, relaySess, "tok", store, fp)

	waitFor(t, func() bool {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		return len(fp.calls) > 0
	})
	fp.mu.Lock()
	defer fp.mu.Unlock()
	if got := fp.calls[0]["e2e"]; got != "cipher-blob" {
		t.Fatalf("want e2e=cipher-blob, got %+v", fp.calls[0])
	}
}

func TestMonitorAgentScopesPushToOwnTenant(t *testing.T) {
	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}

	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/events" || r.Header.Get("X-Relay-Token") != "tok" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			ws, err := up.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			_ = ws.WriteJSON(wire.EventFrame{
				Type: "feed", NeedsAttention: true, FeedID: "F1",
				Kind: "permissionRequest", Title: "Run rm -rf?",
			})
			time.Sleep(500 * time.Millisecond)
		}))
	}()

	store, err := auth.Open(t.TempDir() + "/d.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantA, _ := store.CreateTenant()
	tenantB, _ := store.CreateTenant()
	tokA, _ := store.Issue(tenantA, "phone-a", "test-device-pubkey-a")
	tokB, _ := store.Issue(tenantB, "phone-b", "test-device-pubkey-b")
	store.SetFCMToken(tokA, "fcm-a")
	store.SetFCMToken(tokB, "fcm-b")

	fp := &fakePusher{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// tenantA's agent session fires the attention event; tenantB's FCM token
	// must never receive it.
	go MonitorAgent(ctx, tenantA, relaySess, "tok", store, fp)

	waitFor(t, func() bool {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		return len(fp.tokens) > 0
	})
	fp.mu.Lock()
	defer fp.mu.Unlock()
	for _, tok := range fp.tokens {
		if tok != "fcm-a" {
			t.Fatalf("push reached a token outside tenantA: %q", tok)
		}
	}
}
