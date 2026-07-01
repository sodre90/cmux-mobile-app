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
	"github.com/sodre90/cmux-bridge/internal/server"
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
			_ = ws.WriteJSON(server.EventFrame{
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
	tok, _ := store.Issue(tenant, "phone")
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
			_ = ws.WriteJSON(server.EventFrame{
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
	tokA, _ := store.Issue(tenantA, "phone-a")
	tokB, _ := store.Issue(tenantB, "phone-b")
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
