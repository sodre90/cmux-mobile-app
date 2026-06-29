package server

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// EventFrame is the stable, app-facing event pushed over WS /events.
type EventFrame struct {
	Type           string `json:"type"` // "feed" | "notification" | "heartbeat"
	Name           string `json:"name,omitempty"`
	NeedsAttention bool   `json:"needs_attention"`
	FeedID         string `json:"feed_id,omitempty"`
	WorkspaceID    string `json:"workspace_id,omitempty"`
	SurfaceID      string `json:"surface_id,omitempty"`
	Title          string `json:"title,omitempty"`
	Kind           string `json:"kind,omitempty"`
}

// classify maps a raw cmux event frame to an EventFrame. The bool is false for
// frames the app should not see (acks, surface/pane/workspace churn).
func classify(m map[string]any) (EventFrame, bool) {
	switch str(m, "type") {
	case "ack":
		return EventFrame{}, false
	case "heartbeat":
		return EventFrame{Type: "heartbeat"}, true
	}
	name := str(m, "name")
	payload, _ := m["payload"].(map[string]any)
	wsID := firstNonEmpty(str(m, "workspace_id"), str(payload, "workspace_id"))
	surfID := firstNonEmpty(str(m, "surface_id"), str(payload, "surface_id"))

	switch str(m, "category") {
	case "feed":
		kind := str(payload, "kind")
		status := str(payload, "status")
		return EventFrame{
			Type:           "feed",
			Name:           name,
			Kind:           kind,
			FeedID:         str(payload, "id"),
			WorkspaceID:    firstNonEmpty(str(payload, "workstream_id"), wsID),
			SurfaceID:      surfID,
			Title:          str(payload, "title"),
			NeedsAttention: needsAttention(kind, status),
		}, true
	case "notification":
		// Note: cmux redacts notification title/body in the event stream, so we
		// forward these as informational only and do not set NeedsAttention.
		return EventFrame{
			Type:        "notification",
			Name:        name,
			WorkspaceID: wsID,
			SurfaceID:   surfID,
			Title:       str(payload, "title"),
		}, true
	}
	return EventFrame{}, false
}

// needsAttention reports whether a feed item represents a blocking agent prompt
// awaiting the user.
func needsAttention(kind, status string) bool {
	if status != "pending" {
		return false
	}
	switch kind {
	case "permissionRequest", "question", "exitPlan":
		return true
	}
	return false
}

// ingestEvents reads NDJSON cmux event frames from r, classifies each, and
// broadcasts the ones the app should see.
func (s *Server) ingestEvents(ctx context.Context, r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for sc.Scan() {
		if ctx.Err() != nil {
			return
		}
		var m map[string]any
		if err := json.Unmarshal(sc.Bytes(), &m); err != nil {
			continue
		}
		if f, ok := classify(m); ok {
			s.hub.broadcast(f)
			if f.NeedsAttention {
				go s.notifyPush(f)
			}
		}
	}
}

// RunEvents keeps a `cmux events --reconnect` stream flowing into the hub. It is
// started once by `serve`. When push is configured, attention frames also fan
// out to FCM.
func (s *Server) RunEvents(ctx context.Context) {
	for ctx.Err() == nil {
		cmd, pipe, err := s.cmux.Events(ctx,
			"--category", "feed", "--category", "notification", "--reconnect")
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}
		s.ingestEvents(ctx, pipe)
		_ = cmd.Wait()
		if ctx.Err() == nil {
			time.Sleep(time.Second)
		}
	}
}

// ---- WebSocket fan-out hub ----

type hub struct {
	mu    sync.Mutex
	conns map[chan EventFrame]bool
}

func newHub() *hub { return &hub{conns: map[chan EventFrame]bool{}} }

func (h *hub) register() chan EventFrame {
	ch := make(chan EventFrame, 32)
	h.mu.Lock()
	h.conns[ch] = true
	h.mu.Unlock()
	return ch
}

func (h *hub) unregister(ch chan EventFrame) {
	h.mu.Lock()
	if h.conns[ch] {
		delete(h.conns, ch)
		close(ch)
	}
	h.mu.Unlock()
}

func (h *hub) broadcast(f EventFrame) {
	h.mu.Lock()
	for ch := range h.conns {
		select {
		case ch <- f:
		default: // drop for a slow client rather than block the stream
		}
	}
	h.mu.Unlock()
}

var upgrader = websocket.Upgrader{
	CheckOrigin: func(*http.Request) bool { return true }, // auth is token + mTLS, not Origin
}

func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()
	ch := s.hub.register()
	defer s.hub.unregister(ch)

	// Drain/await client close so a dead socket unblocks the writer.
	go func() {
		for {
			if _, _, err := c.ReadMessage(); err != nil {
				c.Close()
				return
			}
		}
	}()

	for f := range ch {
		_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if err := c.WriteJSON(f); err != nil {
			return
		}
	}
}

// ---- small helpers ----

func str(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
