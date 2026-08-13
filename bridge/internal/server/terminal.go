package server

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/sodre90/cmux-bridge/internal/httpjson"
	"github.com/sodre90/cmux-bridge/internal/metrics"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

// deviceLogID returns the last 6 hex characters of a device ID (itself the
// full SHA-256 hash of a bearer token, see auth.Device.TokenHash) -- enough
// to correlate log lines for one device without ever logging the full hash,
// mirroring auth.Device.HashSuffix.
func deviceLogID(deviceID string) string {
	if len(deviceID) < 6 {
		return deviceID
	}
	return deviceID[len(deviceID)-6:]
}

func (s *Server) handleTerminal(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if id == "" {
		httpjson.Error(w, http.StatusBadRequest, "missing surface id")
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
			httpjson.Error(w, http.StatusUnauthorized, "unknown_device")
			return
		}
		if _, ok := s.sessions.SharedSecret(deviceID); !ok {
			httpjson.Error(w, http.StatusConflict, "not_paired")
			return
		}
	}
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer func() { _ = c.Close() }()
	start := time.Now()
	slog.Info("terminal: connected", "surface_id", id, "device", deviceLogID(deviceID))
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	if deviceID != "" {
		defer s.sockets.track(deviceID, cancel)()
	}

	// gorilla/websocket allows only one concurrent writer per connection. The
	// poll loop below and terminalReadLoop's ack writes both write to c, so
	// every write goes through this mutex-guarded helper instead of calling
	// writeTerminalFrame directly.
	var writeMu sync.Mutex
	write := func(fr wire.TerminalDown) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
		return s.writeTerminalFrame(c, deviceID, fr)
	}

	// Initial full replay.
	fr, err := s.fetchReplay(ctx, id)
	if err != nil {
		if ctx.Err() == nil {
			slog.Warn("terminal: initial replay failed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
		}
		return
	}
	fr.Type = "replay"
	if err := write(fr); err != nil {
		slog.Warn("terminal: initial write failed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
		return
	}
	// cmux's top-level seq (and render_grid.state_seq) is always 0, so we can't
	// gate on it — instead we forward whenever the render-grid bytes change.
	lastGrid := fr.Grid

	// Read loop (client input) runs in its own goroutine; it only reads.
	go s.terminalReadLoop(ctx, cancel, c, id, deviceID, write)

	// Output poll loop is the sole writer after the initial replay.
	t := time.NewTicker(s.terminalPoll)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			slog.Info("terminal: closed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds())
			return
		case <-t.C:
			next, err := s.fetchReplay(ctx, id)
			if err != nil {
				// A cancelled ctx means the connection is already closing
				// (terminalReadLoop's disconnect handler called cancel,
				// which SIGKILLs any in-flight `cmux rpc` subprocess via
				// exec.CommandContext) -- that's an expected side effect
				// of the disconnect already logged by the read loop, not
				// a genuine RPC failure worth alarming about.
				if ctx.Err() == nil {
					slog.Warn("terminal: poll replay failed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
				}
				return
			}
			if bytes.Equal(next.Grid, lastGrid) {
				continue
			}
			lastGrid = next.Grid
			next.Type = "output"
			if err := write(next); err != nil {
				slog.Warn("terminal: output write failed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
				return
			}
		}
	}
}

// writeTerminalFrame sends fr as a plain JSON text frame when encryption is
// disabled (s.sessions == nil), or as a binary e2e-encrypted frame otherwise.
func (s *Server) writeTerminalFrame(c *websocket.Conn, deviceID string, fr wire.TerminalDown) error {
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

func (s *Server) terminalReadLoop(ctx context.Context, cancel context.CancelFunc, c *websocket.Conn, id, deviceID string, write func(wire.TerminalDown) error) {
	defer cancel()
	for {
		var up wire.TerminalUp
		if s.sessions == nil {
			if err := c.ReadJSON(&up); err != nil {
				slog.Warn("terminal: read loop ended", "surface_id", id, "err", err)
				return
			}
		} else {
			_, raw, err := c.ReadMessage()
			if err != nil {
				slog.Warn("terminal: read loop ended", "surface_id", id, "err", err)
				return
			}
			plain, err := s.sessions.DecryptFrame(deviceID, raw)
			if err != nil {
				slog.Warn("terminal: decrypt failed", "surface_id", id, "device", deviceLogID(deviceID), "err", err)
				metrics.E2EDecryptFailuresTotal.Add("terminal_frame", 1)
				return
			}
			if err := json.Unmarshal(plain, &up); err != nil {
				slog.Warn("terminal: bad frame json", "surface_id", id, "err", err)
				return
			}
		}
		var rpcErr error
		switch up.Type {
		case "input":
			_, rpcErr = s.cmux.Rpc(ctx, "mobile.terminal.input",
				map[string]any{"surface_id": id, "text": up.Text})
		case "paste":
			_, rpcErr = s.cmux.Rpc(ctx, "mobile.terminal.paste",
				map[string]any{"surface_id": id, "text": up.Text})
		case "resize":
			_, rpcErr = s.cmux.Rpc(ctx, "mobile.terminal.viewport",
				map[string]any{"surface_id": id, "columns": up.Columns, "rows": up.Rows})
		default:
			continue
		}
		if up.Seq == 0 {
			continue // no seq set (shouldn't happen from the app) -- nothing to ack.
		}
		if err := write(wire.TerminalDown{Type: "ack", Seq: up.Seq, Ok: rpcErr == nil}); err != nil {
			slog.Warn("terminal: ack write failed", "surface_id", id, "err", err)
			return
		}
	}
}

// replayTimeout is what mobile.terminal.replay gets instead of the cmux
// package's default, because it is categorically heavier than every other
// call the bridge makes: it serialises a whole render grid, measured at
// 1.0-4.45s for 240-390KB per surface against a 5s default, and 6.9-10.4s
// once cmux itself was busy. Failing there is worse than waiting, because
// the phone reconnects on failure and the reconnect issues another replay
// -- the retry storm cost more than the slow call it was avoiding
// (cmux-app-69y).
const replayTimeout = 20 * time.Second

// fetchReplay calls mobile.terminal.replay and returns a wire.TerminalDown
// (Type unset) holding the render grid and dimensions.
func (s *Server) fetchReplay(ctx context.Context, id string) (wire.TerminalDown, error) {
	ctx, cancel := context.WithTimeout(ctx, replayTimeout)
	defer cancel()
	raw, err := s.cmux.Rpc(ctx, "mobile.terminal.replay",
		map[string]any{"surface_id": id})
	if err != nil {
		return wire.TerminalDown{}, err
	}
	var top struct {
		Columns    int             `json:"columns"`
		Rows       int             `json:"rows"`
		Seq        int             `json:"seq"`
		RenderGrid json.RawMessage `json:"render_grid"`
	}
	if err := json.Unmarshal(raw, &top); err != nil {
		return wire.TerminalDown{}, err
	}
	return wire.TerminalDown{
		Grid:    top.RenderGrid,
		Columns: top.Columns,
		Rows:    top.Rows,
		Seq:     int64(top.Seq),
	}, nil
}
