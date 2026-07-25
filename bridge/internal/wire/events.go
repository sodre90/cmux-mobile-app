// Package wire holds the app-facing wire types shared between the agent-side
// server (internal/server) and the relay (internal/relay). Every type here is
// hand-mirrored in the Android app (model/Dtos.kt) -- any field change must
// land on both sides in the same commit, with the JSON staying byte-identical
// on the wire.
package wire

// EventFrame is the stable, app-facing event pushed over WS /events.
type EventFrame struct {
	Type           string `json:"type"` // "feed" | "notification" | "heartbeat"
	Name           string `json:"name,omitempty"`
	NeedsAttention bool   `json:"needs_attention"`
	FeedID         string `json:"feed_id,omitempty"`
	WorkspaceID    string `json:"workspace_id,omitempty"`
	SurfaceID      string `json:"surface_id,omitempty"`
	Title          string `json:"title,omitempty"`
	// Preview is what the agent is asking for, set by resolveAttention: the
	// pending prompt's own text where cmux has one, else the workspace's
	// agent-status line (e.g. "Claude needs your permission"). Kept separate
	// from Title so a push notification can put the workspace name in the
	// title and this in the body, instead of one combined string in both.
	// The Android EventFrame deliberately has no mirror of this field -- it
	// reaches the app only as push-notification body text, encrypted per
	// device (see PushBody and server.buildEncryptedPush).
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

// PushTitle returns a NeedsAttention frame's notification title: the
// workspace's own live title (set by resolveAttention), so the notification
// shows which workspace at a glance, or a generic fallback when the workspace
// couldn't be resolved.
func (f EventFrame) PushTitle() string {
	if f.Title != "" {
		return f.Title
	}
	return "Agent needs your attention"
}

// PushBody returns a NeedsAttention frame's notification body: what the agent
// is asking (set by resolveAttention) when cmux could tell us, else a phrase
// derived from the underlying Claude Code hook event, else a generic fallback.
func (f EventFrame) PushBody() string {
	if f.Preview != "" {
		return f.Preview
	}
	switch f.Kind {
	case "AskUserQuestion":
		return "Has a question for you"
	case "Notification":
		return "Needs your attention"
	default:
		return "Open cmux to reply"
	}
}
