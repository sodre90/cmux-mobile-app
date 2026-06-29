package server

import (
	"encoding/json"
	"net/http"
)

// FeedReply answers an agent prompt. Params is forwarded to cmux verbatim and
// the required request_id is injected, so the precise cmux param names live in
// the client and need not be hardcoded here.
//
// NOTE: the exact reply param keys (beyond request_id) should be confirmed
// against a live prompt; until then the client sends cmux-native params under
// "params".
type FeedReply struct {
	Kind      string         `json:"kind"`       // "permission" | "question" | "exitPlan"
	RequestID string         `json:"request_id"` // required by cmux
	Params    map[string]any `json:"params,omitempty"`
}

func feedMethod(kind string) (string, bool) {
	switch kind {
	case "permission":
		return "feed.permission.reply", true
	case "question":
		return "feed.question.reply", true
	case "exitPlan":
		return "feed.exit_plan.reply", true
	}
	return "", false
}

func (s *Server) handleFeedReply(w http.ResponseWriter, r *http.Request) {
	var fr FeedReply
	if err := json.NewDecoder(r.Body).Decode(&fr); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	method, ok := feedMethod(fr.Kind)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unknown kind"})
		return
	}
	if fr.RequestID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "missing request_id"})
		return
	}
	params := map[string]any{}
	for k, v := range fr.Params {
		params[k] = v
	}
	params["request_id"] = fr.RequestID

	if _, err := s.cmux.Rpc(r.Context(), method, params); err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "cmux reply failed"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
