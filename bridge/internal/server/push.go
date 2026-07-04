package server

import (
	"context"
	"log"
	"time"
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
