package server

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/sodre90/cmux-bridge/internal/cmux"
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

	armReadDeadline(t, c)
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
	armReadDeadline(t, c)
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(wire.TerminalUp{Type: "input", Text: "ls\r"}); err != nil {
		t.Fatal(err)
	}

	waitForRPCLog(t, logPath, "mobile.terminal.input", "SURF1", "ls")
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
	armReadDeadline(t, c)
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(wire.TerminalUp{Type: "input", Text: "ls\r", Seq: 42}); err != nil {
		t.Fatal(err)
	}

	armReadDeadline(t, c)
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

	armReadDeadline(t, c)
	var down wire.TerminalDown
	if err := c.ReadJSON(&down); err != nil {
		t.Fatal(err)
	}

	if err := c.WriteJSON(wire.TerminalUp{Type: "input", Text: "ls\r", Seq: 7}); err != nil {
		t.Fatal(err)
	}

	armReadDeadline(t, c)
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

	armReadDeadline(t, c)
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
	armReadDeadline(t, c)
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
	armReadDeadline(t, c)
	var replay wire.TerminalDown
	if err := c.ReadJSON(&replay); err != nil {
		t.Fatal(err)
	}
	if replay.Type != "replay" {
		t.Fatalf("first frame must be replay, got %q", replay.Type)
	}

	// The screen content changes on the next poll while seq stays 0. The poll
	// loop must forward it as an output frame rather than freezing on seq.
	armReadDeadline(t, c)
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

// closeGoneProbe stands a WebSocket up and hands closeIfSurfaceGone the given
// error on the server side, returning the close code the client observes.
// gorilla reports a peer that just hangs up as CloseAbnormalClosure (1006),
// which is what "no close frame was sent" looks like from here.
func closeGoneProbe(t *testing.T, serverErr error) int {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer c.Close()
		closeIfSurfaceGone(c, serverErr)
	}))
	defer srv.Close()

	c, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	armReadDeadline(t, c)
	_, _, readErr := c.ReadMessage()
	var closeErr *websocket.CloseError
	if !errors.As(readErr, &closeErr) {
		t.Fatalf("want a close error, got %v", readErr)
	}
	return closeErr.Code
}

// cmux-app-34c. Without this the socket just ended, which is indistinguishable
// from a dropped connection, so the app backed off and reconnected to the same
// dead surface every 5s -- showing a spinner the whole time.
func TestASurfaceCmuxNoLongerHasIsClosedAsGone(t *testing.T) {
	gone := &cmux.RPCError{Method: "mobile.terminal.replay", Code: "not_found", Message: "Terminal surface not found"}

	if got := closeGoneProbe(t, gone); got != wire.CloseSurfaceGone {
		t.Fatalf("close code = %d, want %d", got, wire.CloseSurfaceGone)
	}
}

// Everything else can succeed on the next attempt, so it must keep closing the
// old way and be retried. Telling a live pane it is gone is the worse failure.
func TestARetryableFailureIsNotClosedAsGone(t *testing.T) {
	cases := map[string]error{
		"a different cmux refusal": &cmux.RPCError{Method: "mobile.terminal.replay", Code: "internal", Message: "boom"},
		"cmux unreachable":         errors.New("dial /tmp/cmux.sock: connection refused"),
		"a timeout":                context.DeadlineExceeded,
	}
	for name, err := range cases {
		t.Run(name, func(t *testing.T) {
			if got := closeGoneProbe(t, err); got == wire.CloseSurfaceGone {
				t.Fatalf("%v was closed as surface-gone", err)
			}
		})
	}
}

// The reason string stays empty: the relay is deliberately blind, and a close
// code it can already infer from the connection ending tells it nothing new,
// while text about the surface would.
func TestTheGoneCloseCarriesNoReasonText(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer c.Close()
		closeIfSurfaceGone(c, &cmux.RPCError{Method: "m", Code: "not_found", Message: "Terminal surface not found"})
	}))
	defer srv.Close()

	c, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	armReadDeadline(t, c)
	_, _, readErr := c.ReadMessage()
	var closeErr *websocket.CloseError
	if !errors.As(readErr, &closeErr) {
		t.Fatalf("want a close error, got %v", readErr)
	}
	if closeErr.Text != "" {
		t.Fatalf("close reason = %q, want empty", closeErr.Text)
	}
}

// -- a slow cmux must not tear down a live pane (cmux-app-8a0)

// fakeStallingTerminalScript answers the first replay, then fails the next
// three, then answers again. Mirrors the measured failure: cmux stops
// answering mobile.terminal.replay for a while and then comes back.
const fakeStallingTerminalScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.terminal.replay)
    n=$(grep -c mobile.terminal.replay "$CMUX_FAKE_LOG")
    if [ "$n" -ge 2 ] && [ "$n" -le 4 ]; then
      echo "Error: internal: cmux is busy" >&2
      exit 1
    fi
    cat <<JSON
{"columns":80,"rows":24,"seq":0,"surface_id":"S","workspace_id":"W","render_grid":{"format":"cmux.render-grid.v1","columns":80,"rows":24,"row_spans":[{"row":0,"text":"line-$n"}]}}
JSON
    ;;
  *) echo '{"ok":true}' ;;
esac
`

// fakeDeadTerminalScript answers the first replay and then never answers again.
const fakeDeadTerminalScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.terminal.replay)
    n=$(grep -c mobile.terminal.replay "$CMUX_FAKE_LOG")
    if [ "$n" -ge 2 ]; then
      echo "Error: internal: cmux is busy" >&2
      exit 1
    fi
    cat <<JSON
{"columns":80,"rows":24,"seq":0,"surface_id":"S","workspace_id":"W","render_grid":{"format":"cmux.render-grid.v1","columns":80,"rows":24,"row_spans":[{"row":0,"text":"line-1"}]}}
JSON
    ;;
  *) echo '{"ok":true}' ;;
esac
`

// THE regression. A failed poll used to return false, which ended the handler
// and closed the socket -- so one slow replay dropped a healthy pane, the phone
// reconnected, and the reconnect issued another full replay against the cmux
// that was already too slow to serve one. The grid on screen is still valid, so
// the socket must survive and recover on its own.
func TestASlowReplayDoesNotCloseALivePane(t *testing.T) {
	t.Setenv("CMUX_FAKE_LOG", t.TempDir()+"/cmux.log")
	s, tok := newTestServer(t, fakeStallingTerminalScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	armReadDeadline(t, c)
	var replay wire.TerminalDown
	if err := c.ReadJSON(&replay); err != nil {
		t.Fatal(err)
	}
	if replay.Type != "replay" {
		t.Fatalf("first frame must be replay, got %q", replay.Type)
	}

	// Three replays fail in between. If any of them closed the socket this read
	// returns a close/EOF error instead of the frame from the fifth call.
	armReadDeadline(t, c)
	var out wire.TerminalDown
	if err := c.ReadJSON(&out); err != nil {
		t.Fatalf("socket did not survive the failing replays: %v", err)
	}
	if !strings.Contains(string(out.Grid), "line-5") {
		t.Fatalf("want the frame from after the recovery, got: %s", out.Grid)
	}
}

// The safety valve: holding the pane is bounded, so a cmux that never comes
// back does not keep the socket forever.
func TestAPaneIsGivenUpOnceTheGraceRunsOut(t *testing.T) {
	t.Setenv("CMUX_FAKE_LOG", t.TempDir()+"/cmux.log")
	s, tok := newTestServer(t, fakeDeadTerminalScript)
	s.replayGrace = 300 * time.Millisecond
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsConnect(t, srv.URL, "/terminal/SURF1", tok)
	defer c.Close()

	armReadDeadline(t, c)
	var replay wire.TerminalDown
	if err := c.ReadJSON(&replay); err != nil {
		t.Fatal(err)
	}

	// Must be a CLOSE, not merely silence: a socket that simply stops
	// answering would satisfy "err != nil" via the read deadline and hide an
	// unbounded hold.
	armReadDeadline(t, c)
	_, _, err := c.ReadMessage()
	var closeErr *websocket.CloseError
	if !errors.As(err, &closeErr) {
		t.Fatalf("want the socket closed once the grace elapsed, got %v", err)
	}
}

// -- replayOutage

func outageAt(grace time.Duration, clock *time.Time) *replayOutage {
	return &replayOutage{now: func() time.Time { return *clock }, grace: grace}
}

func TestAnOutageIsNotOngoingUntilSomethingFails(t *testing.T) {
	now := time.Date(2026, 9, 8, 23, 0, 0, 0, time.UTC)
	o := outageAt(time.Minute, &now)

	if o.ongoing() {
		t.Fatal("a fresh outage must not report itself as ongoing")
	}
	if o.recovered() != 0 {
		t.Fatal("recovered must be zero when nothing was wrong -- otherwise every successful poll logs a recovery")
	}
}

func TestThePaneIsHeldUntilTheGraceElapses(t *testing.T) {
	now := time.Date(2026, 9, 8, 23, 0, 0, 0, time.UTC)
	o := outageAt(time.Minute, &now)

	if !o.keepWaiting() {
		t.Fatal("the first failure must never give up -- that is the storm this fixes")
	}
	now = now.Add(59 * time.Second)
	if !o.keepWaiting() {
		t.Fatal("still inside the grace, must keep holding")
	}
	now = now.Add(2 * time.Second)
	if o.keepWaiting() {
		t.Fatal("past the grace, must give up")
	}
}

func TestRecoveryReportsHowLongTheOutageRanAndClearsIt(t *testing.T) {
	now := time.Date(2026, 9, 8, 23, 0, 0, 0, time.UTC)
	o := outageAt(time.Minute, &now)
	o.keepWaiting()

	now = now.Add(30 * time.Second)
	if down := o.recovered(); down != 30*time.Second {
		t.Fatalf("outage length = %v, want 30s", down)
	}
	if o.ongoing() {
		t.Fatal("recovered must end the outage")
	}
}

// A second outage after a recovery gets its own full grace -- otherwise a pane
// that flaps all day would be given up on for a failure it had just recovered
// from.
func TestAFreshOutageAfterRecoveryGetsTheFullGraceAgain(t *testing.T) {
	now := time.Date(2026, 9, 8, 23, 0, 0, 0, time.UTC)
	o := outageAt(time.Minute, &now)
	o.keepWaiting()
	now = now.Add(59 * time.Second)
	o.recovered()

	now = now.Add(time.Hour)
	if !o.keepWaiting() {
		t.Fatal("a new outage must start its grace from scratch")
	}
}
