package server

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"unicode"

	"github.com/sodre90/cmux-bridge/internal/httpjson"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

// findWorkspace fetches the live workspace list and returns the one matching
// id, if any. Used by callers that need a single workspace's live fields
// (CWD, title, preview) without re-deriving the whole list themselves.
func (s *Server) findWorkspace(ctx context.Context, id string) (wire.Workspace, bool) {
	raw, err := s.cmux.Rpc(ctx, "mobile.workspace.list", nil)
	if err != nil {
		return wire.Workspace{}, false
	}
	workspaces, err := parseWorkspaces(raw)
	if err != nil {
		return wire.Workspace{}, false
	}
	for _, ws := range workspaces {
		if ws.ID == id {
			return ws, true
		}
	}
	return wire.Workspace{}, false
}

func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	raw, err := s.cmux.Rpc(r.Context(), "mobile.workspace.list", nil)
	if err != nil {
		httpjson.Error(w, http.StatusBadGateway, "cmux unavailable")
		return
	}
	workspaces, err := parseWorkspaces(raw)
	if err != nil {
		httpjson.Error(w, http.StatusBadGateway, "cmux parse error")
		return
	}
	if s.yolo != nil {
		for i := range workspaces {
			workspaces[i].YoloMode = s.yolo.Mode(workspaces[i].ID)
		}
	}
	httpjson.Write(w, http.StatusOK, map[string]any{"workspaces": workspaces})
}

// parseWorkspaces normalizes a mobile.workspace.list payload into Workspaces.
// cmux nests workspace objects under several keys (top-level "workspaces" and
// inside "groups"), so we walk the whole tree and collect any object that
// carries a "terminals" array — that array is what distinguishes a workspace
// from its nested terminal surfaces, which fixes the old flattening that swept
// panes in as their own (unstreamable) entries. Deduped by id (first wins).
// maxWorkspaceWalkDepth bounds parseWorkspaces' recursive tree walk. cmux is
// a trusted local process, so this guards against a corrupted/malformed
// response nesting deeply enough to exhaust the goroutine stack, not against
// a hostile cmux.
const maxWorkspaceWalkDepth = 64

func parseWorkspaces(raw []byte) ([]wire.Workspace, error) {
	var root any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	out := []wire.Workspace{}
	var walk func(any, int)
	walk = func(node any, depth int) {
		if depth > maxWorkspaceWalkDepth {
			return
		}
		switch v := node.(type) {
		case map[string]any:
			id, hasID := stringField(v, "id")
			terms, hasTerms := v["terminals"].([]any)
			if hasID && hasTerms && !seen[id] {
				seen[id] = true
				cwd, _ := stringField(v, "current_directory")
				hasUnread, _ := v["has_unread"].(bool)
				preview := firstString(v, "preview")
				out = append(out, wire.Workspace{
					ID: id,
					// Canonicalized so it matches feed.list's cwd byte-for-byte
					// (see canonicalizeFeedCWDs) -- cmux reports the two
					// symlinked differently for the same location.
					CWD:         canonicalPath(cwd),
					Title:       cleanTitle(firstString(v, "title")),
					Preview:     preview,
					HasUnread:   hasUnread,
					Attention:   classifyAttention(preview),
					CustomColor: firstString(v, "custom_color"),
					Terminals:   parsePanes(terms),
				})
			}
			for _, child := range v {
				walk(child, depth+1)
			}
		case []any:
			for _, child := range v {
				walk(child, depth+1)
			}
		}
	}
	walk(root, 0)
	return out, nil
}

// parsePanes maps a workspace's "terminals" array into wire.TerminalPanes.
// Panes without an id are skipped; an empty array yields an empty (non-nil)
// slice.
func parsePanes(terms []any) []wire.TerminalPane {
	panes := []wire.TerminalPane{}
	for _, t := range terms {
		m, ok := t.(map[string]any)
		if !ok {
			continue
		}
		id, hasID := stringField(m, "id")
		if !hasID {
			continue
		}
		cwd, _ := stringField(m, "current_directory")
		title := cleanTitle(firstString(m, "title"))
		focused, _ := m["is_focused"].(bool)
		ready, _ := m["is_ready"].(bool)
		panes = append(panes, wire.TerminalPane{
			ID:      id,
			CWD:     cwd,
			Title:   title,
			Focused: focused,
			Ready:   ready,
			Kind:    classifyKind(title),
		})
	}
	return panes
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

// classifyAttention flags workspaces whose preview is one of cmux's agent-status
// strings so the app can color them: "permission" when the agent is blocked on a
// permission prompt, "input" when it is idle waiting for the user. Matching is
// case-insensitive and substring-based so it also covers non-Claude agents
// ("Codex needs your permission", …). Any other preview yields "".
func classifyAttention(preview string) string {
	p := strings.ToLower(preview)
	switch {
	case strings.Contains(p, "needs your permission"):
		return "permission"
	case strings.Contains(p, "waiting for your input"):
		return "input"
	default:
		return ""
	}
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
