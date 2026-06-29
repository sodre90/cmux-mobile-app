package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"unicode"
)

// Session is the stable, app-facing representation of a cmux workspace/terminal.
type Session struct {
	ID             string `json:"id"`
	CWD            string `json:"cwd"`
	Title          string `json:"title"`
	Kind           string `json:"kind"` // "agent" or "terminal"
	NeedsAttention bool   `json:"needs_attention"`
}

func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	raw, err := s.cmux.Rpc(r.Context(), "mobile.workspace.list", nil)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "cmux unavailable"})
		return
	}
	sessions, err := parseSessions(raw)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "cmux parse error"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": sessions})
}

// parseSessions normalizes a mobile.workspace.list payload into deduped
// Sessions. The payload nests workspace objects under several keys, so we walk
// the whole tree and collect any object that carries both an "id" and a
// "current_directory", deduping by id (first occurrence wins).
func parseSessions(raw []byte) ([]Session, error) {
	var root any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	out := []Session{}
	var walk func(any)
	walk = func(node any) {
		switch v := node.(type) {
		case map[string]any:
			id, hasID := stringField(v, "id")
			cwd, hasCWD := stringField(v, "current_directory")
			if hasID && hasCWD && !seen[id] {
				seen[id] = true
				title := cleanTitle(firstString(v, "preview", "title"))
				if title == "" {
					title = cwd
				}
				out = append(out, Session{
					ID:    id,
					CWD:   cwd,
					Title: title,
					Kind:  classifyKind(title),
				})
			}
			for _, child := range v {
				walk(child)
			}
		case []any:
			for _, child := range v {
				walk(child)
			}
		}
	}
	walk(root)
	return out, nil
}

func stringField(m map[string]any, key string) (string, bool) {
	if val, ok := m[key].(string); ok && val != "" {
		return val, true
	}
	return "", false
}

func firstString(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if val, ok := m[k].(string); ok && val != "" {
			return val
		}
	}
	return ""
}

// cleanTitle strips a leading status glyph (e.g. spinner "⠂", "✳") plus the
// following space that cmux prepends to agent titles.
func cleanTitle(t string) string {
	t = strings.TrimSpace(t)
	r := []rune(t)
	if len(r) >= 2 && r[1] == ' ' && !isTitleRune(r[0]) {
		return strings.TrimSpace(string(r[2:]))
	}
	return t
}

func isTitleRune(r rune) bool {
	return unicode.IsLetter(r) || unicode.IsDigit(r) || r == '~' || r == '/'
}

// classifyKind treats shell-prompt-looking titles as terminals and everything
// else as agent workspaces.
func classifyKind(title string) string {
	if strings.HasPrefix(title, "~/") || strings.HasPrefix(title, "/") {
		return "terminal"
	}
	// user@host:path style prompt.
	if at := strings.IndexByte(title, '@'); at > 0 {
		if colon := strings.IndexByte(title[at:], ':'); colon > 0 {
			return "terminal"
		}
	}
	return "agent"
}

func writeJSON(w http.ResponseWriter, code int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(body)
}
