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

	"github.com/sodre90/cmux-bridge/internal/cmux"
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
	// Compression is negotiated both ways. The app asks with ?deflate=1 and
	// arms its side only on seeing the confirming response header, so all four
	// app/bridge version pairings work: an old bridge never sends the header
	// and a new app stays uncompressed, while a new bridge never compresses for
	// an old app that did not ask. Without the confirmation half, a new app
	// against an old bridge would read the JSON's leading '{' as a codec tag and
	// drop every frame.
	//
	// Only meaningful with encryption on: compression rides inside the sealed
	// payload, and the plaintext branch of [writeTerminalFrame] has no tag byte
	// to carry it.
	deflate := s.sessions != nil && r.URL.Query().Get("deflate") == "1"
	var upgradeHeader http.Header
	if deflate {
		upgradeHeader = http.Header{deflateHeader: {"1"}}
	}
	c, err := upgrader.Upgrade(w, r, upgradeHeader)
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
		return s.writeTerminalFrame(c, deviceID, fr, deflate)
	}

	// Initial full replay.
	fr, err := s.fetchReplay(ctx, id)
	if err != nil {
		if ctx.Err() == nil {
			slog.Warn("terminal: initial replay failed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
			closeIfSurfaceGone(c, err)
		}
		return
	}
	fr.Type = "replay"
	if err := write(fr); err != nil {
		slog.Warn("terminal: initial write failed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
		return
	}
	// cmux's top-level seq (and render_grid.state_seq) is always 0, so we can't
	// gate on it — instead we forward whenever the render-grid content changes,
	// ignoring the bookkeeping counters that change on their own (see
	// [gridFingerprint]).
	lastFingerprint := gridFingerprint(fr.Grid)

	// Output poll loop is the sole writer after the initial replay. Besides
	// the ticker, an input nudge (below) triggers an immediate replay: without
	// it, every forwarded keystroke/swipe waited out the remaining tick before
	// its effect became visible, which made remote scrolling feel seconds
	// behind the finger even though the PTY had already scrolled.
	nudge := make(chan struct{}, 1)
	go s.terminalReadLoop(ctx, cancel, c, id, deviceID, write, nudge)

	outage := newReplayOutage(s.replayGrace)

	poll := func() bool {
		next, err := s.fetchReplay(ctx, id)
		if err != nil {
			// A cancelled ctx means the connection is already closing
			// (terminalReadLoop's disconnect handler called cancel,
			// which SIGKILLs any in-flight `cmux rpc` subprocess via
			// exec.CommandContext) -- that's an expected side effect
			// of the disconnect already logged by the read loop, not
			// a genuine RPC failure worth alarming about.
			if ctx.Err() != nil {
				return false
			}
			if cmux.IsNotFound(err) {
				slog.Warn("terminal: surface is gone", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
				closeIfSurfaceGone(c, err)
				return false
			}
			metrics.TerminalReplayFailuresTotal.Add(1)
			if !outage.ongoing() {
				slog.Warn("terminal: replay failing, holding the pane on its last grid",
					"surface_id", id, "grace", s.replayGrace, "err", err)
			}
			if outage.keepWaiting() {
				return true
			}
			metrics.TerminalReplayGaveUpTotal.Add(1)
			slog.Warn("terminal: giving up, cmux answered no replay for the whole grace window",
				"surface_id", id, "grace", s.replayGrace, "err", err)
			return false
		}
		if down := outage.recovered(); down > 0 {
			slog.Info("terminal: replay working again", "surface_id", id, "down_for", down.Round(time.Second))
		}
		fingerprint := gridFingerprint(next.Grid)
		if bytes.Equal(fingerprint, lastFingerprint) {
			return true
		}
		lastFingerprint = fingerprint
		next.Type = "output"
		if err := write(next); err != nil {
			slog.Warn("terminal: output write failed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds(), "err", err)
			return false
		}
		return true
	}
	t := time.NewTicker(s.terminalPoll)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			slog.Info("terminal: closed", "surface_id", id, "dur_ms", time.Since(start).Milliseconds())
			return
		case <-t.C:
			if !poll() {
				return
			}
		case <-nudge:
			// Coalesce any nudges that piled up while this replay was being
			// considered; one immediate refresh serves them all.
			for drained := true; drained; {
				select {
				case <-nudge:
				default:
					drained = false
				}
			}
			if !poll() {
				return
			}
		}
	}
}

// volatileGridFields are the render-grid keys cmux advances on its own clock,
// whether or not anything on screen changed. Both are plain counters the app
// never reads.
//
// They are what made the poll loop's unchanged-grid check dead code: measured
// against a live idle pane (cmux-app-2nj), three consecutive replays 400ms
// apart differed in these two fields and in nothing else -- every other key,
// including all 143KB of scrollback_spans, was byte-identical. So a pane
// sitting at a shell prompt re-sent its whole ~187KB grid four times a second
// to deliver two incrementing integers.
var volatileGridFields = []string{"render_revision", "terminal_theme_revision"}

// gridFingerprint reduces a render grid to a value that compares equal when
// the grid's content is unchanged, by dropping [volatileGridFields]. Only the
// top level is decoded -- every value below it stays raw -- so this costs one
// shallow pass rather than parsing the span arrays.
//
// A grid that will not decode is returned verbatim, which compares exactly as
// it did before this existed. That is the same direction every failure here
// takes: an unrecognised grid, or a future cmux counter not in the list above,
// costs a redundant frame, never a suppressed one. Dropping a field the app
// DOES render would be the unsafe direction, which is why this is a list of
// known-volatile keys rather than a list of known-meaningful ones.
func gridFingerprint(grid json.RawMessage) []byte {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(grid, &fields); err != nil {
		return grid
	}
	for _, k := range volatileGridFields {
		delete(fields, k)
	}
	// json.Marshal sorts map keys, so equal content always yields equal bytes.
	out, err := json.Marshal(fields)
	if err != nil {
		return grid
	}
	return out
}

// terminalReplayGrace bounds how long the poll loop will sit on a stale grid
// waiting for a cmux that cannot answer mobile.terminal.replay.
//
// Chosen from the measured episode lengths (cmux-app-8a0): of the nine multi-
// failure episodes in 28 days, eight spanned under 7 minutes and one spanned 20.
// So this waits out all but the outlier, and the outlier degrades to the old
// behaviour rather than to something worse.
//
// Longer would be defensible too -- an idle socket costs nothing, while giving
// up costs a reconnect and a fresh full replay against the process that is
// already too slow to serve one. What sets an upper bound at all is that
// nothing else can notice a client which vanished mid-outage: the read loop
// only learns of it from a failed read, and the write path only from a write
// there is currently nothing to make.
const terminalReplayGrace = 10 * time.Minute

// replayOutage tracks one run of consecutive replay failures on a single
// terminal socket, so the poll loop can tell a first failure from a continuing
// one and a recovery from an ordinary success.
type replayOutage struct {
	now   func() time.Time
	grace time.Duration
	since time.Time
}

func newReplayOutage(grace time.Duration) *replayOutage {
	return &replayOutage{now: time.Now, grace: grace}
}

func (o *replayOutage) ongoing() bool { return !o.since.IsZero() }

// keepWaiting records a failure and reports whether the socket is still worth
// holding. The first failure starts the outage, so this is what decides when
// the grace has run out.
func (o *replayOutage) keepWaiting() bool {
	if !o.ongoing() {
		o.since = o.now()
	}
	return o.now().Sub(o.since) < o.grace
}

// recovered ends the outage and reports how long it ran. A zero duration means
// there was no outage in progress, which is the ordinary case on every
// successful poll.
func (o *replayOutage) recovered() time.Duration {
	if !o.ongoing() {
		return 0
	}
	down := o.now().Sub(o.since)
	o.since = time.Time{}
	return down
}

// closeWriteTimeout bounds the close control frame's write. Short on purpose:
// the connection is being abandoned either way, so waiting on a peer that has
// already stopped reading buys nothing.
const closeWriteTimeout = time.Second

// closeIfSurfaceGone answers a replay failure that means "this surface does not
// exist" with wire.CloseSurfaceGone, so the client stops reconnecting to an id
// cmux will never have again. Anything else -- a timeout, a cmux restart, a
// transport fault -- is left to close ordinarily and be retried, because it can
// succeed next time.
//
// The close frame goes out via WriteControl, which gorilla permits concurrently
// with the poll loop's data writes, so it does not take writeMu. A failure to
// send it is ignored on purpose: the socket is already going away, and the
// pre-existing behaviour (client retries) is the fallback.
func closeIfSurfaceGone(c *websocket.Conn, err error) {
	if !cmux.IsNotFound(err) {
		return
	}
	_ = c.WriteControl(
		websocket.CloseMessage,
		websocket.FormatCloseMessage(wire.CloseSurfaceGone, ""),
		time.Now().Add(closeWriteTimeout),
	)
}

// deflateHeader is the response header the bridge sets on the 101 to confirm
// it accepted a client's ?deflate=1 request. Mirrored in the app as
// TerminalSocket's DEFLATE_HEADER.
const deflateHeader = "X-Cmux-Deflate"

// writeTerminalFrame sends fr as a plain JSON text frame when encryption is
// disabled (s.sessions == nil), or as a binary e2e-encrypted frame otherwise.
// When deflate is set, the sealed payload carries a wire codec tag and is
// compressed where that helps -- see wire.EncodePayload.
func (s *Server) writeTerminalFrame(c *websocket.Conn, deviceID string, fr wire.TerminalDown, deflate bool) error {
	if s.sessions == nil {
		return c.WriteJSON(fr)
	}
	raw, err := json.Marshal(fr)
	if err != nil {
		return err
	}
	if deflate {
		raw = wire.EncodePayload(raw)
	}
	frame, err := s.sessions.EncryptFrame(deviceID, raw)
	if err != nil {
		return err
	}
	return c.WriteMessage(websocket.BinaryMessage, frame)
}

func (s *Server) terminalReadLoop(ctx context.Context, cancel context.CancelFunc, c *websocket.Conn, id, deviceID string, write func(wire.TerminalDown) error, nudge chan<- struct{}) {
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
			// The PTY just changed (keystroke or page scroll): ask the poll
			// loop for an immediate replay so the user sees the effect now
			// rather than on the next tick. Non-blocking: a flood of inputs
			// leaves at most one pending nudge, which is the point.
			select {
			case nudge <- struct{}{}:
			default:
			}
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
// once cmux itself was busy (cmux-app-69y).
//
// This used to be the only defence against the retry storm -- a failure closed
// the socket, the phone reconnected, and the reconnect issued another full
// replay against the cmux that was already too slow to serve one -- so the
// deadline was lengthened until failures got rare. The poll loop now holds the
// socket through a failure instead (see [terminalReplayGrace]), which removes
// the storm rather than making it rarer, and leaves this as what it says it is:
// how long one replay may take.
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
