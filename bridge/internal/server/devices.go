package server

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

type registerReq struct {
	FCMToken string `json:"fcm_token"`
}

// handleDeviceRegister records the caller's FCM registration token against the
// device identified by its bearer token.
func (s *Server) handleDeviceRegister(w http.ResponseWriter, r *http.Request) {
	dev, ok := auth.DeviceFromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	var rq registerReq
	if err := json.NewDecoder(r.Body).Decode(&rq); err != nil || rq.FCMToken == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "missing fcm_token"})
		return
	}
	s.store.SetFCMToken(dev.Token, rq.FCMToken)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// notifyPush sends a push for an attention frame to every registered device. It
// is a no-op when no push backend is configured.
func (s *Server) notifyPush(f EventFrame) {
	if s.push == nil {
		return
	}
	tokens := s.store.FCMTokens()
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
		_ = s.push.Send(ctx, tok, "Agent needs your attention", body, data)
	}
}
