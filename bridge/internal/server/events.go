package server

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"path/filepath"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/sodre90/cmux-bridge/internal/backoff"
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
	// Preview is the workspace's live status preview (e.g. "Claude needs your
	// permission"), set by enrichTitle -- kept separate from Title so a push
	// notification can put the workspace name in the title and this in the
	// body, instead of one combined string in both.
	Preview string `json:"preview,omitempty"`
	Kind    string `json:"kind,omitempty"`
	// EncryptedPush holds, per paired deviceID, an e2e-encrypted {title,body}
	// push payload (see buildEncryptedPush in push.go) -- populated by
	// ingestEvents for NeedsAttention frames so relay-mode push (which reads
	// this same frame over a plaintext internal subscription, see
	// writeEventFrame) never needs the real Title/Preview to build a
	// notification. Keyed by the same deviceID e2e.Store.EncryptFrame uses.
	EncryptedPush map[string]string `json:"encrypted_push,omitempty"`
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
		hookEvent := str(payload, "hook_event_name")
		return EventFrame{
			Type:           "feed",
			Name:           name,
			Kind:           hookEvent,
			FeedID:         firstNonEmpty(str(m, "id"), str(payload, "id")),
			WorkspaceID:    firstNonEmpty(str(payload, "workspace_id"), wsID),
			SurfaceID:      surfID,
			Title:          attentionLabel(payload),
			NeedsAttention: needsAttention(hookEvent, str(payload, "phase")),
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

// needsAttention reports whether a cmux feed item is a blocking agent prompt
// awaiting the user. cmux carries the Claude Code hook in payload.hook_event_name
// and emits each item twice (phase "received" then "completed"); we alert once,
// on "received". Notification covers permission prompts and idle "waiting for
// input"; AskUserQuestion is an explicit blocking choice.
func needsAttention(hookEvent, phase string) bool {
	if phase != "received" {
		return false
	}
	switch hookEvent {
	case "Notification", "AskUserQuestion":
		return true
	}
	return false
}

// attentionLabel returns the best human label for a feed item. cmux redacts the
// prompt text, so the cwd basename ("cmux-app") tells the user which agent is
// waiting; a real payload title is preferred if cmux ever provides one. This is
// only the cheap, synchronous fallback used before enrichTitle's workspace
// lookup runs (or if that lookup fails).
func attentionLabel(payload map[string]any) string {
	if t := str(payload, "title"); t != "" {
		return t
	}
	if cwd := str(payload, "cwd"); cwd != "" {
		return filepath.Base(cwd)
	}
	return ""
}

// enrichTitle upgrades an attention frame's Title with the workspace's live
// title, and sets Preview to its live status preview (e.g. "Claude is
// waiting for your input") — the richest context cmux exposes for a prompt
// whose actual text it redacts. On any lookup failure the cheap cwd-basename
// Title classify already set is left as-is, and Preview stays empty.
func (s *Server) enrichTitle(ctx context.Context, f *EventFrame) {
	if f.WorkspaceID == "" {
		return
	}
	ws, ok := s.findWorkspace(ctx, f.WorkspaceID)
	if !ok {
		return
	}
	if ws.Title != "" {
		f.Title = ws.Title
	}
	f.Preview = ws.Preview
}

// ingestEvents reads NDJSON cmux event frames from r, classifies each, and
// broadcasts the ones the app should see. Relay-side push (internal/relay's
// pushmon) subscribes to this same /events stream over a plaintext internal
// connection (see writeEventFrame) -- enrichTitle's real Title/Preview never
// reach it directly; buildEncryptedPush turns them into per-device
// ciphertexts first, which is the only form of this content the relay (or
// this agent's own direct-mode push) ever forwards to FCM.
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
			if f.NeedsAttention {
				s.enrichTitle(ctx, &f)
				// Give YOLO a chance to silently resolve this before anyone is
				// alerted: if it does, clear NeedsAttention so neither this
				// agent's own push nor the relay's pushmon (which trusts this
				// flag with no YOLO visibility of its own) ever fires for it.
				if s.autoResolveYolo(ctx, f.WorkspaceID) {
					f.NeedsAttention = false
				} else {
					f.EncryptedPush = s.buildEncryptedPush(f)
					s.maybeSendPush(ctx, f)
				}
			}
			s.hub.broadcast(f)
		}
	}
	if err := sc.Err(); err != nil {
		// Most commonly bufio.ErrTooLong: a single event line exceeded the
		// scanner's 4MB cap. That kills the whole stream (Scan stops, not just
		// the one line), so RunEvents' caller-side restart-with-backoff is what
		// actually recovers -- this log exists so that isn't silent.
		log.Printf("server: events stream ended abnormally: %v", err)
	}
}

// RunEvents keeps a `cmux events --reconnect` stream flowing into the hub. It is
// started once by the agent's tunnel handler. The relay subscribes to this same
// /events stream over the tunnel to drive FCM push (internal/relay/pushmon.go).
func (s *Server) RunEvents(ctx context.Context) {
	retry := backoff.New(time.Second, 30*time.Second)
	for ctx.Err() == nil {
		cmd, pipe, err := s.cmux.Events(ctx,
			"--category", "feed", "--category", "notification", "--reconnect")
		if err != nil {
			time.Sleep(retry.Next())
			continue
		}
		s.ingestEvents(ctx, pipe)
		_ = cmd.Wait()
		if ctx.Err() == nil {
			time.Sleep(retry.Next())
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
	var deviceID string
	if s.sessions != nil {
		// A present X-Device-ID means this is a device subscriber (the
		// relay's proxy Director injects it on every device-originated
		// request) -- gate on it being paired and encrypt its frames below.
		// A missing X-Device-ID means this is the relay's own internal
		// push-monitor subscription (internal/relay/pushmon.go dials
		// directly over the tunnel with only X-Relay-Token, which
		// RequireRelayToken has already checked by this point in
		// TrustedHandler) -- serve it plaintext so FCM fan-out keeps
		// working. This exposes no more than the relay already saw before
		// e2e encryption existed; only device-bound frames are e2e.
		deviceID = r.Header.Get("X-Device-ID")
		if deviceID != "" {
			if _, ok := s.sessions.SharedSecret(deviceID); !ok {
				http.Error(w, "not_paired", http.StatusConflict)
				return
			}
		}
	}
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
		if err := s.writeEventFrame(c, deviceID, f); err != nil {
			return
		}
	}
}

// writeEventFrame sends f as a plain JSON text frame when encryption is
// disabled (s.sessions == nil) or the caller is the relay's own internal
// push-monitor subscription (deviceID == ""), or as a binary e2e-encrypted
// frame otherwise. In a deployment with e2e enabled, the relay's own
// subscription additionally gets Title and Preview redacted -- a blind relay
// must never learn a tenant's workspace name or live status text. It still
// gets EncryptedPush's per-device ciphertexts and the routing metadata
// (kind, ids) it needs to fan out push notifications. When s.sessions == nil
// there is no device/relay distinction or blindness guarantee being made at
// all (a bare test/dev configuration), so nothing is redacted.
func (s *Server) writeEventFrame(c *websocket.Conn, deviceID string, f EventFrame) error {
	if s.sessions == nil {
		return c.WriteJSON(f)
	}
	if deviceID == "" {
		f.Title = ""
		f.Preview = ""
		return c.WriteJSON(f)
	}
	raw, err := json.Marshal(f)
	if err != nil {
		return err
	}
	frame, err := s.sessions.EncryptFrame(deviceID, raw)
	if err != nil {
		return err
	}
	return c.WriteMessage(websocket.BinaryMessage, frame)
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
