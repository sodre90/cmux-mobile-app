package server

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"

	"github.com/sodre90/cmux-bridge/internal/httpjson"
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
		httpjson.Error(w, http.StatusBadRequest, "invalid json")
		return
	}
	if !yolo.Valid(req.Mode) {
		httpjson.Error(w, http.StatusBadRequest, "invalid mode")
		return
	}
	if s.yolo == nil {
		httpjson.Error(w, http.StatusServiceUnavailable, "yolo store unavailable")
		return
	}
	if err := s.yolo.SetMode(id, req.Mode); err != nil {
		httpjson.Error(w, http.StatusInternalServerError, "persist failed")
		return
	}
	if req.Mode != "" {
		s.resolvePendingPermission(r.Context(), id, req.Mode)
	}
	httpjson.Write(w, http.StatusOK, map[string]bool{"ok": true})
}

// pendingFeedItem is the subset of a `feed.list --pending_only` item this
// package needs. See android's Dtos.kt PendingFeedItem for the full shape.
type pendingFeedItem struct {
	RequestID string `json:"request_id"`
	Kind      string `json:"kind"`
	CWD       string `json:"cwd"`
	Status    string `json:"status"`
}

// autoResolveYolo checks a NeedsAttention frame's workspace for a non-off
// YOLO mode and, if set, synchronously resolves any matching pending
// permission request before the caller broadcasts/pushes the frame -- a
// permission prompt YOLO is about to silently approve must never also alert
// the phone (that alert would fire on both the agent's own direct-mode push
// and, since the relay's pushmon trusts NeedsAttention with no YOLO
// visibility of its own, the relay-mode push too). Returns whether something
// was actually resolved, so the caller can suppress NeedsAttention.
//
// The mode lookup itself is a cheap local read (no RPC cost at all when YOLO
// is off, the common case); only a non-off mode makes the cmux RPC calls in
// resolvePendingPermission, briefly blocking ingestEvents's single-goroutine
// scan loop for that one frame -- an acceptable tradeoff since YOLO-on
// workspaces are the deliberately opted-in minority.
func (s *Server) autoResolveYolo(ctx context.Context, workspaceID string) bool {
	if s.yolo == nil || workspaceID == "" {
		return false
	}
	mode := s.yolo.Mode(workspaceID)
	if mode == "" {
		return false
	}
	return s.resolvePendingPermission(ctx, workspaceID, mode)
}

// resolvePendingPermission finds any pending "permissionRequest" feed item
// whose cwd matches workspaceID's live working directory and replies to it
// with mode, unblocking the agent without a phone tap. Returns whether at
// least one item was found and successfully replied to.
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
func (s *Server) resolvePendingPermission(ctx context.Context, workspaceID, mode string) bool {
	ws, ok := s.findWorkspace(ctx, workspaceID)
	if !ok || ws.CWD == "" {
		return false
	}
	wantCWD := canonicalPath(ws.CWD)
	raw, err := s.cmux.Rpc(ctx, "feed.list", map[string]any{"pending_only": true})
	if err != nil {
		return false
	}
	var resp struct {
		Items []pendingFeedItem `json:"items"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return false
	}
	resolvedAny := false
	for _, item := range resp.Items {
		if item.Kind != "permissionRequest" || item.Status != "pending" || canonicalPath(item.CWD) != wantCWD {
			continue
		}
		if _, err := s.cmux.Rpc(ctx, "feed.permission.reply", map[string]any{
			"request_id": item.RequestID,
			"mode":       mode,
		}); err == nil {
			resolvedAny = true
		}
	}
	return resolvedAny
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
