package server

import (
	"encoding/json"
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/httpjson"
)

type renameWorkspaceRequest struct {
	Title string `json:"title"`
}

// handleRenameWorkspace sets a workspace's persistent display title in cmux
// via its documented workspace.rename RPC (the same one behind cmux's own
// `cmux rename-workspace` CLI command and Cmd+Shift+R shortcut) -- the one
// deliberate mutation the bridge makes to workspace state; see README's
// Security model / "What the app does" for the rest of the read-only stance.
func (s *Server) handleRenameWorkspace(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var req renameWorkspaceRequest
	r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpjson.Error(w, http.StatusBadRequest, "invalid json")
		return
	}
	if req.Title == "" {
		httpjson.Error(w, http.StatusBadRequest, "missing title")
		return
	}
	if _, err := s.cmux.Rpc(r.Context(), "workspace.rename", map[string]any{
		"workspace_id": id,
		"title":        req.Title,
	}); err != nil {
		httpjson.Error(w, http.StatusBadGateway, "cmux rename failed")
		return
	}
	httpjson.Write(w, http.StatusOK, map[string]bool{"ok": true})
}
