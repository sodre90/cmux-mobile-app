package server

import (
	"bytes"
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
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
	var deviceID string
	if s.sessions != nil {
		// Status codes match internal/server/encryption.go's
		// encryptionMiddleware: a missing header is 401 unknown_device, a
		// present-but-unrecognized device id is 409 not_paired (see the
		// spec's error-handling section) — the two point the app at
		// different recovery UX.
		deviceID = r.Header.Get("X-Device-ID")
		if deviceID == "" {
			http.Error(w, "unknown_device", http.StatusUnauthorized)
			return
		}
		if _, ok := s.sessions.SharedSecret(deviceID); !ok {
			http.Error(w, "not_paired", http.StatusConflict)
			return
		}
	}
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()
	start := time.Now()
	log.Printf("terminal %s: connected", id)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	// Initial full replay.
	fr, err := s.fetchReplay(ctx, id)
	if err != nil {
		log.Printf("terminal %s: initial replay failed after %s: %v", id, time.Since(start), err)
		return
	}
	fr.Type = "replay"
	_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := s.writeTerminalFrame(c, deviceID, fr); err != nil {
		log.Printf("terminal %s: initial write failed after %s: %v", id, time.Since(start), err)
		return
	}
	// cmux's top-level seq (and render_grid.state_seq) is always 0, so we can't
	// gate on it — instead we forward whenever the render-grid bytes change.
	lastGrid := fr.Grid

	// Read loop (client input) runs in its own goroutine; it only reads.
	go s.terminalReadLoop(ctx, cancel, c, id, deviceID)

	// Output poll loop is the sole writer after the initial replay.
	t := time.NewTicker(s.terminalPoll)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			log.Printf("terminal %s: closed after %s", id, time.Since(start))
			return
		case <-t.C:
			next, err := s.fetchReplay(ctx, id)
			if err != nil {
				log.Printf("terminal %s: poll replay failed after %s: %v", id, time.Since(start), err)
				return
			}
			if bytes.Equal(next.Grid, lastGrid) {
				continue
			}
			lastGrid = next.Grid
			next.Type = "output"
			_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := s.writeTerminalFrame(c, deviceID, next); err != nil {
				log.Printf("terminal %s: output write failed after %s: %v", id, time.Since(start), err)
				return
			}
		}
	}
}

// writeTerminalFrame sends fr as a plain JSON text frame when encryption is
// disabled (s.sessions == nil), or as a binary e2e-encrypted frame otherwise.
func (s *Server) writeTerminalFrame(c *websocket.Conn, deviceID string, fr TerminalDown) error {
	if s.sessions == nil {
		return c.WriteJSON(fr)
	}
	raw, err := json.Marshal(fr)
	if err != nil {
		return err
	}
	frame, err := s.sessions.EncryptFrame(deviceID, raw)
	if err != nil {
		return err
	}
	return c.WriteMessage(websocket.BinaryMessage, frame)
}

func (s *Server) terminalReadLoop(ctx context.Context, cancel context.CancelFunc, c *websocket.Conn, id, deviceID string) {
	defer cancel()
	for {
		var up TerminalUp
		if s.sessions == nil {
			if err := c.ReadJSON(&up); err != nil {
				log.Printf("terminal %s: read loop ended: %v", id, err)
				return
			}
		} else {
			_, raw, err := c.ReadMessage()
			if err != nil {
				log.Printf("terminal %s: read loop ended: %v", id, err)
				return
			}
			plain, err := s.sessions.DecryptFrame(deviceID, raw)
			if err != nil {
				log.Printf("terminal %s: decrypt failed: %v", id, err)
				return
			}
			if err := json.Unmarshal(plain, &up); err != nil {
				log.Printf("terminal %s: bad frame json: %v", id, err)
				return
			}
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
