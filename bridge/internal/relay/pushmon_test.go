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
	mu    sync.Mutex
	calls []map[string]string
}

func (p *fakePusher) Send(_ context.Context, _, _, _ string, data map[string]string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
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

	store, err := auth.Open(t.TempDir() + "/d.json")
	if err != nil {
		t.Fatal(err)
	}
	tok, _ := store.Issue("phone")
	store.SetFCMToken(tok, "fcm-123")

	fp := &fakePusher{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go MonitorAgent(ctx, relaySess, "tok", store, fp)

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
