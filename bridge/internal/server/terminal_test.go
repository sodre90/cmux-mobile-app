package server

import (
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
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
	var down TerminalDown
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
	var down TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(TerminalUp{Type: "input", Text: "ls\r"}); err != nil {
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
