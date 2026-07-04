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

// fanout sends one push per registered device, each carrying only that
// device's own e2e-encrypted payload from f.EncryptedPush (built agent-side
// by buildEncryptedPush) -- the relay never has the shared secrets needed to
// read or build notification content itself (see events.go's
// writeEventFrame, which redacts Title/Preview before this subscription ever
// sees the frame). A device missing from EncryptedPush still gets a push
// with routing metadata only, so the app can show a generic notification
// rather than nothing.
func fanout(tenantID string, store *auth.Store, push Pusher, f server.EventFrame) {
	devices := store.TenantFCMDevices(tenantID)
	if len(devices) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	sent, failed, encrypted := 0, 0, 0
	for _, dev := range devices {
		data := map[string]string{
			"type":         "attention",
			"feed_id":      f.FeedID,
			"workspace_id": f.WorkspaceID,
			"surface_id":   f.SurfaceID,
			"kind":         f.Kind,
			"slot":         "relay",
		}
		if blob, ok := f.EncryptedPush[dev.DeviceID]; ok {
			data["e2e"] = blob
			encrypted++
		}
		if err := push.Send(ctx, dev.FCMToken, "", "", data); err != nil {
			failed++
			log.Printf("relay: attention push failed (tenant=%q kind=%s ws=%s): %v", tenantID, f.Kind, f.WorkspaceID, err)
			continue
		}
		sent++
	}
	log.Printf("relay: attention push (tenant=%q kind=%s ws=%s) sent=%d failed=%d encrypted=%d", tenantID, f.Kind, f.WorkspaceID, sent, failed, encrypted)
}
