package server

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"

	"github.com/sodre90/cmux-bridge/internal/yolo"
)

type setYoloModeRequest struct {
	Mode string `json:"mode"`
}

// handleSetYoloMode persists a workspace's opt-in auto-reply mode for
// permission prompts. When turning a mode on, it immediately resolves any
// permission already pending for that workspace, so enabling e.g. Bypass
// unsticks an already-blocked workspace without waiting for the next event.
func (s *Server) handleSetYoloMode(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var req setYoloModeRequest
	r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	if !yolo.Valid(req.Mode) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid mode"})
		return
	}
	if s.yolo == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "yolo store unavailable"})
		return
	}
	if err := s.yolo.SetMode(id, req.Mode); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "persist failed"})
		return
	}
	if req.Mode != "" {
		s.resolvePendingPermission(r.Context(), id, req.Mode)
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// pendingFeedItem is the subset of a `feed.list --pending_only` item this
// package needs. See android's Dtos.kt PendingFeedItem for the full shape.
type pendingFeedItem struct {
	RequestID string `json:"request_id"`
	Kind      string `json:"kind"`
	CWD       string `json:"cwd"`
	Status    string `json:"status"`
}

// maybeAutoResolve checks a NeedsAttention frame's workspace for a non-off
// YOLO mode and, if set, resolves any pending permission in the background.
// The mode lookup itself is a cheap local read (no RPC cost at all when YOLO
// is off, the common case); only a non-off mode spawns the goroutine that
// makes cmux RPC calls, so ingestEvents's single-goroutine scan loop is never
// blocked waiting on cmux for a workspace that isn't opted in.
func (s *Server) maybeAutoResolve(ctx context.Context, workspaceID string) {
	if s.yolo == nil || workspaceID == "" {
		return
	}
	mode := s.yolo.Mode(workspaceID)
	if mode == "" {
		return
	}
	go s.resolvePendingPermission(ctx, workspaceID, mode)
}

// resolvePendingPermission finds any pending "permissionRequest" feed item
// whose cwd matches workspaceID's live working directory and replies to it
// with mode, unblocking the agent without a phone tap.
//
// Pending items are keyed by workstream_id -- the agent's own session ID
// (e.g. "claude-<uuid>"), confirmed live to be a different ID space than
// cmux's workspace ID -- so correlation is done on cwd instead, the one field
// both a pending item and a workspace carry in common.
//
// mobile.workspace.list's current_directory and feed.list's cwd disagree on
// symlinks -- confirmed live, e.g. workspace.list reports "/tmp/foo" while
// the same workspace's pending item reports "/private/tmp/foo" (macOS
// resolves /tmp -> /private/tmp). Comparing raw strings would silently drop
// every match for a workspace under /tmp, /var, /etc, or any other
// symlinked path, so both sides are canonicalized before comparing.
func (s *Server) resolvePendingPermission(ctx context.Context, workspaceID, mode string) {
	ws, ok := s.findWorkspace(ctx, workspaceID)
	if !ok || ws.CWD == "" {
		return
	}
	wantCWD := canonicalPath(ws.CWD)
	raw, err := s.cmux.Rpc(ctx, "feed.list", map[string]any{"pending_only": true})
	if err != nil {
		return
	}
	var resp struct {
		Items []pendingFeedItem `json:"items"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return
	}
	for _, item := range resp.Items {
		if item.Kind != "permissionRequest" || item.Status != "pending" || canonicalPath(item.CWD) != wantCWD {
			continue
		}
		_, _ = s.cmux.Rpc(ctx, "feed.permission.reply", map[string]any{
			"request_id": item.RequestID,
			"mode":       mode,
		})
	}
}

// canonicalPath resolves symlinks so paths reported through different cmux
// RPCs can be compared for equality; a path that no longer exists (or any
// other resolution failure) is returned unchanged rather than dropped.
func canonicalPath(p string) string {
	if resolved, err := filepath.EvalSymlinks(p); err == nil {
		return resolved
	}
	return p
}
