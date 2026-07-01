package relay

import (
	"context"
	"log"
	"net"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/server"
)

// Pusher delivers an attention push to a single device token. push.Sender
// satisfies it.
type Pusher interface {
	Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
}

// MonitorAgent subscribes to the agent's /events over the tunnel and fans
// blocking prompts out to FCM, scoped to tenantID's own devices only. It
// returns when ctx is cancelled or the session dies. relayToken authenticates
// to the agent's trusted handler.
func MonitorAgent(ctx context.Context, tenantID string, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) {
	if push == nil {
		return
	}
	for ctx.Err() == nil {
		if err := subscribeOnce(tenantID, sess, relayToken, store, push); err != nil && sess.IsClosed() {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

func subscribeOnce(tenantID string, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) error {
	d := websocket.Dialer{
		NetDial: func(_, _ string) (net.Conn, error) { return sess.Open() },
	}
	ws, _, err := d.Dial("ws://agent/events", http.Header{"X-Relay-Token": {relayToken}})
	if err != nil {
		return err
	}
	defer ws.Close()
	for {
		var f server.EventFrame
		if err := ws.ReadJSON(&f); err != nil {
			return err
		}
		if f.NeedsAttention {
			fanout(tenantID, store, push, f)
		}
	}
}

func fanout(tenantID string, store *auth.Store, push Pusher, f server.EventFrame) {
	tokens := store.TenantFCMTokens(tenantID)
	if len(tokens) == 0 {
		return
	}
	body := f.Title
	if body == "" {
		body = f.Kind
	}
	data := map[string]string{
		"type":         "attention",
		"feed_id":      f.FeedID,
		"workspace_id": f.WorkspaceID,
		"surface_id":   f.SurfaceID,
		"kind":         f.Kind,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	sent, failed := 0, 0
	for _, tok := range tokens {
		if err := push.Send(ctx, tok, "Agent needs your attention", body, data); err != nil {
			failed++
			log.Printf("relay: attention push failed (tenant=%q kind=%s ws=%s): %v", tenantID, f.Kind, f.WorkspaceID, err)
			continue
		}
		sent++
	}
	log.Printf("relay: attention push (tenant=%q kind=%s label=%q ws=%s) sent=%d failed=%d", tenantID, f.Kind, body, f.WorkspaceID, sent, failed)
}
