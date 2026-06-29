package server

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
)

// TerminalDown is a server->client terminal message. Grid carries the cmux
// render-grid object (format "cmux.render-grid.v1") verbatim; the app renders it
// as a styled cell grid.
type TerminalDown struct {
	Type    string          `json:"type"` // "replay" | "output"
	Grid    json.RawMessage `json:"grid,omitempty"`
	Columns int             `json:"columns,omitempty"`
	Rows    int             `json:"rows,omitempty"`
	Seq     int             `json:"seq,omitempty"`
}

// TerminalUp is a client->server terminal message.
type TerminalUp struct {
	Type    string `json:"type"` // "input" | "paste" | "resize"
	Text    string `json:"text,omitempty"`
	Columns int    `json:"columns,omitempty"`
	Rows    int    `json:"rows,omitempty"`
}

func (s *Server) handleTerminal(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if id == "" {
		http.Error(w, "missing surface id", http.StatusBadRequest)
		return
	}
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	// Initial full replay.
	fr, err := s.fetchReplay(ctx, id)
	if err != nil {
		return
	}
	fr.Type = "replay"
	_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := c.WriteJSON(fr); err != nil {
		return
	}
	last := fr.Seq

	// Read loop (client input) runs in its own goroutine; it only reads.
	go s.terminalReadLoop(ctx, cancel, c, id)

	// Output poll loop is the sole writer after the initial replay.
	t := time.NewTicker(s.terminalPoll)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			next, err := s.fetchReplay(ctx, id)
			if err != nil {
				return
			}
			if next.Seq == last {
				continue
			}
			last = next.Seq
			next.Type = "output"
			_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.WriteJSON(next); err != nil {
				return
			}
		}
	}
}

func (s *Server) terminalReadLoop(ctx context.Context, cancel context.CancelFunc, c interface {
	ReadJSON(any) error
}, id string) {
	defer cancel()
	for {
		var up TerminalUp
		if err := c.ReadJSON(&up); err != nil {
			return
		}
		switch up.Type {
		case "input":
			_, _ = s.cmux.Rpc(ctx, "mobile.terminal.input",
				map[string]any{"surface_id": id, "text": up.Text})
		case "paste":
			_, _ = s.cmux.Rpc(ctx, "mobile.terminal.paste",
				map[string]any{"surface_id": id, "text": up.Text})
		case "resize":
			_, _ = s.cmux.Rpc(ctx, "mobile.terminal.viewport",
				map[string]any{"surface_id": id, "columns": up.Columns, "rows": up.Rows})
		}
	}
}

// fetchReplay calls mobile.terminal.replay and returns a TerminalDown (Type
// unset) holding the render grid and dimensions.
func (s *Server) fetchReplay(ctx context.Context, id string) (TerminalDown, error) {
	raw, err := s.cmux.Rpc(ctx, "mobile.terminal.replay",
		map[string]any{"surface_id": id})
	if err != nil {
		return TerminalDown{}, err
	}
	var top struct {
		Columns    int             `json:"columns"`
		Rows       int             `json:"rows"`
		Seq        int             `json:"seq"`
		RenderGrid json.RawMessage `json:"render_grid"`
	}
	if err := json.Unmarshal(raw, &top); err != nil {
		return TerminalDown{}, err
	}
	return TerminalDown{
		Grid:    top.RenderGrid,
		Columns: top.Columns,
		Rows:    top.Rows,
		Seq:     top.Seq,
	}, nil
}
