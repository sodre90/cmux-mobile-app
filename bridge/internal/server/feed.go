package server

import (
	"encoding/json"
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/httpjson"
)

// FeedReply answers an agent prompt. Params is forwarded to cmux verbatim and
// the required request_id is injected, so the precise cmux param names live in
// the client and need not be hardcoded here.
//
// NOTE: the exact reply param keys (beyond request_id) should be confirmed
// against a live prompt; until then the client sends cmux-native params under
// "params".
type FeedReply struct {
	// Kind is cmux's own feed.list item kind. "permissionRequest" is confirmed
	// live (cmux rpc feed.list); "exitPlan" is not yet confirmed against a
	// real exit-plan prompt and may need correcting the same way
	// "permission" did.
	Kind      string         `json:"kind"`       // "permissionRequest" | "question" | "exitPlan"
	RequestID string         `json:"request_id"` // required by cmux
	Params    map[string]any `json:"params,omitempty"`
}

func feedMethod(kind string) (string, bool) {
	switch kind {
	case "permissionRequest":
		return "feed.permission.reply", true
	case "question":
		return "feed.question.reply", true
	case "exitPlan":
		return "feed.exit_plan.reply", true
	}
	return "", false
}

// handleFeedPending returns the agent's pending blocking prompts by forwarding
// cmux's feed.list with pending_only. The result is passed through as-is so
// the app receives the full question structure (request_id, questions[].options[],
// question_multi_select) it needs to render choices and reply -- except each
// item's "cwd", which is rewritten to its canonical form (see
// canonicalizeFeedCWDs) so it matches /sessions' Workspace.CWD byte-for-byte.
func (s *Server) handleFeedPending(w http.ResponseWriter, r *http.Request) {
	raw, err := s.cmux.Rpc(r.Context(), "feed.list", map[string]any{"pending_only": true})
	if err != nil {
		httpjson.Error(w, http.StatusBadGateway, "cmux feed.list failed")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(canonicalizeFeedCWDs(raw))
}

// canonicalizeFeedCWDs rewrites each pending item's "cwd" to its
// symlink-resolved form, mirroring parseWorkspaces' canonicalization of
// Workspace.CWD. cmux's feed.list and mobile.workspace.list disagree on
// symlinks (e.g. /tmp/foo vs /private/tmp/foo -- see
// resolvePendingPermission's doc comment, which hit this live), and the
// app's own cwd-based item-to-workspace matching (pendingItemTarget in
// SessionsLogic.kt) needs both sides normalized the same way to have any
// chance of matching. Falls back to the raw bytes unchanged on any parse
// failure -- a shape cmux might change shouldn't break the primary read.
func canonicalizeFeedCWDs(raw []byte) []byte {
	var root map[string]any
	if err := json.Unmarshal(raw, &root); err != nil {
		return raw
	}
	items, ok := root["items"].([]any)
	if !ok {
		return raw
	}
	for _, it := range items {
		item, ok := it.(map[string]any)
		if !ok {
			continue
		}
		if cwd, ok := item["cwd"].(string); ok && cwd != "" {
			item["cwd"] = canonicalPath(cwd)
		}
	}
	out, err := json.Marshal(root)
	if err != nil {
		return raw
	}
	return out
}

func (s *Server) handleFeedReply(w http.ResponseWriter, r *http.Request) {
	var fr FeedReply
	r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
	if err := json.NewDecoder(r.Body).Decode(&fr); err != nil {
		httpjson.Error(w, http.StatusBadRequest, "invalid json")
		return
	}
	method, ok := feedMethod(fr.Kind)
	if !ok {
		httpjson.Error(w, http.StatusBadRequest, "unknown kind")
		return
	}
	if fr.RequestID == "" {
		httpjson.Error(w, http.StatusBadRequest, "missing request_id")
		return
	}
	params := map[string]any{}
	for k, v := range fr.Params {
		params[k] = v
	}
	params["request_id"] = fr.RequestID

	if _, err := s.cmux.Rpc(r.Context(), method, params); err != nil {
		httpjson.Error(w, http.StatusBadGateway, "cmux reply failed")
		return
	}
	httpjson.Write(w, http.StatusOK, map[string]bool{"ok": true})
}
