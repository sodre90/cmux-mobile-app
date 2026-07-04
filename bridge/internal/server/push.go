package server

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// Pusher sends an FCM data message to one registration token. push.Sender
// (internal/push) satisfies it -- see internal/relay.Pusher for the
// identical shape used relay-side; the two are duck-typed independently
// since relay and server are separate packages with no reason to share an
// interface type across a package boundary that otherwise has none.
type Pusher interface {
	Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
}

// maybeSendPush fans a NeedsAttention frame out to every FCM token
// registered in this agent's own local device store (direct-mode pairs
// only -- the relay's separate store/pushmon subscription handles
// relay-paired devices completely independently and is untouched by this).
// No-op with zero store/network cost when direct mode or FCM aren't
// configured, the common case for an agent that hasn't opted into either.
func (s *Server) maybeSendPush(ctx context.Context, f EventFrame) {
	if s.pusher == nil || s.store == nil {
		return
	}
	tokens := s.store.TenantFCMTokens(s.directTenantID)
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
	sendCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	for _, tok := range tokens {
		if err := s.pusher.Send(sendCtx, tok, "Agent needs your attention", body, data); err != nil {
			log.Printf("agent: direct-mode push failed (kind=%s ws=%s): %v", f.Kind, f.WorkspaceID, err)
		}
	}
}

type registerDeviceRequest struct {
	FCMToken string `json:"fcm_token"`
}

// handleRegisterDevice stores a device's FCM registration token in this
// agent's own local store, keyed by the caller's own bearer token (NOT
// X-Device-ID, which carries a hash -- SetFCMToken hashes its argument
// itself; see auth.BearerToken's use here and identically at
// internal/relay/relay.go's handleRegister). Mounted only on
// DirectHandler()'s route set (see direct.go) -- never on TrustedHandler(),
// whose relay-tunneled requests have no real per-device bearer validation
// at the agent, so auth.BearerToken(r) would be meaningless there.
func (s *Server) handleRegisterDevice(w http.ResponseWriter, r *http.Request) {
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "not_available"})
		return
	}
	var rq registerDeviceRequest
	r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
	if err := json.NewDecoder(r.Body).Decode(&rq); err != nil || rq.FCMToken == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "missing fcm_token"})
		return
	}
	if !s.store.SetFCMToken(auth.BearerToken(r), rq.FCMToken) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
