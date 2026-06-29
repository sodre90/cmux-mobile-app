package relay

import (
	"context"
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
// blocking prompts out to FCM. It returns when ctx is cancelled or the session
// dies. relayToken authenticates to the agent's trusted handler.
func MonitorAgent(ctx context.Context, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) {
	if push == nil {
		return
	}
	for ctx.Err() == nil {
		if err := subscribeOnce(sess, relayToken, store, push); err != nil && sess.IsClosed() {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

func subscribeOnce(sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) error {
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
			fanout(store, push, f)
		}
	}
}

func fanout(store *auth.Store, push Pusher, f server.EventFrame) {
	tokens := store.FCMTokens()
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
		"kind":         f.Kind,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, tok := range tokens {
		_ = push.Send(ctx, tok, "Agent needs your attention", body, data)
	}
}
