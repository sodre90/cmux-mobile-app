package server

import (
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/sodre90/cmux-bridge/internal/wire"
)

// fakeTerminalScript answers replay with a canned render-grid and logs every
// invocation's argv to $CMUX_FAKE_LOG so the test can assert input dispatch.
const fakeTerminalScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.terminal.replay)
    cat <<'JSON'
{"columns":80,"rows":24,"seq":1,"surface_id":"S","workspace_id":"W","render_grid":{"format":"cmux.render-grid.v1","columns":80,"rows":24,"row_spans":[]}}
JSON
    ;;
  *) echo '{"ok":true}' ;;
esac
`

// fakeChangingTerminalScript answers replay with content that changes on every
// call while keeping seq constant at 0 — mirroring real cmux, whose top-level
// seq (and render_grid.state_seq) never increments. The poll loop must detect
// the content change itself rather than gating on seq.
const fakeChangingTerminalScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.terminal.replay)
    n=$(grep -c 'mobile.terminal.replay' "$CMUX_FAKE_LOG")
    cat <<JSON
{"columns":80,"rows":24,"seq":0,"surface_id":"S","workspace_id":"W","render_grid":{"format":"cmux.render-grid.v1","columns":80,"rows":24,"row_spans":[{"row":0,"text":"line-$n"}]}}
JSON
    ;;
  *) echo '{"ok":true}' ;;
esac
`

func wsConnect(t *testing.T, srvURL, path, tok string) *websocket.Conn {
	t.Helper()
	u := "ws" + strings.TrimPrefix(srvURL, "http") + path
	c, resp, err := websocket.DefaultDialer.Dial(u, map[string][]string{"Authorization": {"Bearer " + tok}})
	if err != nil {
		code := 0
		if resp != nil {
			code = resp.StatusCode
		}
		t.Fatalf("ws dial %s failed (status %d): %v", path, code, err)
	}
	return c
}

func TestTerminalReplayOnConnect(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeTerminalScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}
	if down.Type != "replay" {
		t.Fatalf("first frame must be replay, got %q", down.Type)
	}
	if down.Columns != 80 || down.Rows != 24 {
		t.Fatalf("dimensions wrong: %+v", down)
	}
	if !strings.Contains(string(down.Grid), "cmux.render-grid.v1") {
		t.Fatalf("grid not passed through: %s", down.Grid)
	}
}

func TestTerminalInputDispatched(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeTerminalScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	// Drain the initial replay frame.
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(wire.TerminalUp{Type: "input", Text: "ls\r"}); err != nil {
		t.Fatal(err)
	}

	// Wait until the fake records the input rpc.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(logPath)
		if strings.Contains(string(data), "mobile.terminal.input") &&
			strings.Contains(string(data), "SURF1") &&
			strings.Contains(string(data), "ls") {
			return // success
		}
		time.Sleep(20 * time.Millisecond)
	}
	data, _ := os.ReadFile(logPath)
	t.Fatalf("input rpc not dispatched; log:\n%s", data)
}

// fakeTerminalFailingInputScript replays fine but fails every
// mobile.terminal.input call, so the read loop's resulting ack carries Ok: false.
const fakeTerminalFailingInputScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.terminal.replay)
    cat <<'JSON'
{"columns":80,"rows":24,"seq":1,"surface_id":"S","workspace_id":"W","render_grid":{"format":"cmux.render-grid.v1","columns":80,"rows":24,"row_spans":[]}}
JSON
    ;;
  mobile.terminal.input)
    echo "boom" >&2
    exit 1
    ;;
  *) echo '{"ok":true}' ;;
esac
`

func TestTerminalInputAcked(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeTerminalScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	// Drain the initial replay frame.
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(wire.TerminalUp{Type: "input", Text: "ls\r", Seq: 42}); err != nil {
		t.Fatal(err)
	}

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var ack wire.TerminalDown
	if err := c.ReadJSON(&ack); err != nil {
		t.Fatalf("expected an ack frame, got: %v", err)
	}
	if ack.Type != "ack" || ack.Seq != 42 || !ack.Ok {
		t.Fatalf("unexpected ack frame: %+v", ack)
	}
}

func TestTerminalInputAckReflectsRpcFailure(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeTerminalFailingInputScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(wire.TerminalUp{Type: "input", Text: "ls\r", Seq: 7}); err != nil {
		t.Fatal(err)
	}

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var ack wire.TerminalDown
	if err := c.ReadJSON(&ack); err != nil {
		t.Fatalf("expected an ack frame, got: %v", err)
	}
	if ack.Type != "ack" || ack.Seq != 7 || ack.Ok {
		t.Fatalf("expected a failed ack (ok=false), got: %+v", ack)
	}
}

func TestTerminalNoAckWhenSeqUnset(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeTerminalScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(wire.TerminalUp{Type: "resize", Columns: 80, Rows: 24}); err != nil {
		t.Fatal(err)
	}
	// Nudge a second, seq'd message through and confirm *its* ack is the very
	// next frame -- proving the unseq'd resize above never produced one.
	if err := c.WriteJSON(wire.TerminalUp{Type: "resize", Columns: 81, Rows: 24, Seq: 1}); err != nil {
		t.Fatal(err)
	}
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var ack wire.TerminalDown
	if err := c.ReadJSON(&ack); err != nil {
		t.Fatalf("expected an ack frame, got: %v", err)
	}
	if ack.Type != "ack" || ack.Seq != 1 {
		t.Fatalf("expected the seq=1 ack directly (no stray ack for the unseq'd resize), got: %+v", ack)
	}
}

func TestTerminalForwardsContentChange(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeChangingTerminalScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	// First frame: the full replay.
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var replay wire.TerminalDown
	if err := c.ReadJSON(&replay); err != nil {
		t.Fatal(err)
	}
	if replay.Type != "replay" {
		t.Fatalf("first frame must be replay, got %q", replay.Type)
	}

	// The screen content changes on the next poll while seq stays 0. The poll
	// loop must forward it as an output frame rather than freezing on seq.
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	var out wire.TerminalDown
	if err := c.ReadJSON(&out); err != nil {
		t.Fatalf("expected an output frame after content change, got: %v", err)
	}
	if out.Type != "output" {
		t.Fatalf("second frame must be output, got %q", out.Type)
	}
	if !strings.Contains(string(out.Grid), "line-") {
		t.Fatalf("output grid missing changed content: %s", out.Grid)
	}
}

func TestTerminalMissingIDRejected(t *testing.T) {
	s, tok := newTestServer(t, fakeTerminalScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	// The {id} pattern won't match an empty segment, so /terminal/ is 404 — a
	// non-upgrade response. Assert the dial fails rather than upgrading.
	u := "ws" + strings.TrimPrefix(srv.URL, "http") + "/terminal/"
	_, _, err := websocket.DefaultDialer.Dial(u, map[string][]string{"Authorization": {"Bearer " + tok}})
	if err == nil {
		t.Fatal("expected dial to fail for empty surface id")
	}
}
