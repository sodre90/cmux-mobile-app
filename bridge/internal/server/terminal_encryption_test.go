package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"

	"github.com/gorilla/websocket"

	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/testutil"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

func wsConnectEncrypted(t *testing.T, srvURL, path, relayTok, deviceID string) *websocket.Conn {
	t.Helper()
	u := "ws" + strings.TrimPrefix(srvURL, "http") + path
	h := http.Header{"X-Relay-Token": {relayTok}, "X-Device-ID": {deviceID}}
	c, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err != nil {
		code := 0
		if resp != nil {
			code = resp.StatusCode
		}
		t.Fatalf("ws dial %s failed (status %d): %v", path, code, err)
	}
	return c
}

func TestTerminalReplayEncryptedWhenSessionsSet(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	bin := testutil.WriteFakeCmux(t, fakeTerminalScript)
	s := New(&cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	c := wsConnectEncrypted(t, srv.URL, "/terminal/SURF1", relayTok, deviceID)
	defer c.Close()

	armReadDeadline(t, c)
	msgType, raw, err := c.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	if msgType != websocket.BinaryMessage {
		t.Fatalf("want a binary (encrypted) frame, got message type %d", msgType)
	}
	counter, plain, err := e2e.DecodeFrame(secret, e2e.DirAgentToDevice, raw)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	if counter != 0 {
		t.Fatalf("want first frame counter 0, got %d", counter)
	}
	var down wire.TerminalDown
	if err := json.Unmarshal(plain, &down); err != nil {
		t.Fatalf("unmarshal decrypted frame: %v", err)
	}
	if down.Type != "replay" || down.Columns != 80 || down.Rows != 24 {
		t.Fatalf("unexpected decrypted frame: %+v", down)
	}
}

func TestTerminalInputDispatchedWhenEncrypted(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	bin := testutil.WriteFakeCmux(t, fakeTerminalScript)
	s := New(&cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	c := wsConnectEncrypted(t, srv.URL, "/terminal/SURF1", relayTok, deviceID)
	defer c.Close()

	// Drain the initial encrypted replay frame.
	armReadDeadline(t, c)
	if _, _, err := c.ReadMessage(); err != nil {
		t.Fatal(err)
	}

	upBytes, err := json.Marshal(wire.TerminalUp{Type: "input", Text: "ls\r"})
	if err != nil {
		t.Fatal(err)
	}
	frame, err := e2e.EncodeFrame(secret, e2e.DirDeviceToAgent, 0, upBytes)
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	if err := c.WriteMessage(websocket.BinaryMessage, frame); err != nil {
		t.Fatal(err)
	}

	waitForRPCLog(t, logPath, "mobile.terminal.input", "SURF1", "ls")
}

func TestTerminalInputAckedWhenEncrypted(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	bin := testutil.WriteFakeCmux(t, fakeTerminalScript)
	s := New(&cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	c := wsConnectEncrypted(t, srv.URL, "/terminal/SURF1", relayTok, deviceID)
	defer c.Close()

	// Drain the initial encrypted replay frame (agent->device counter 0).
	armReadDeadline(t, c)
	if _, _, err := c.ReadMessage(); err != nil {
		t.Fatal(err)
	}

	upBytes, err := json.Marshal(wire.TerminalUp{Type: "input", Text: "ls\r", Seq: 9})
	if err != nil {
		t.Fatal(err)
	}
	frame, err := e2e.EncodeFrame(secret, e2e.DirDeviceToAgent, 0, upBytes)
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	if err := c.WriteMessage(websocket.BinaryMessage, frame); err != nil {
		t.Fatal(err)
	}

	armReadDeadline(t, c)
	_, raw, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("expected an encrypted ack frame, got: %v", err)
	}
	_, plain, err := e2e.DecodeFrame(secret, e2e.DirAgentToDevice, raw)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	var ack wire.TerminalDown
	if err := json.Unmarshal(plain, &ack); err != nil {
		t.Fatalf("unmarshal decrypted ack: %v", err)
	}
	if ack.Type != "ack" || ack.Seq != 9 || !ack.Ok {
		t.Fatalf("unexpected ack frame: %+v", ack)
	}
}

func TestTerminalRejectsMissingDeviceIDWhenEncrypted(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, fakeTerminalScript)
	s := New(&cmux.Client{Bin: bin}, nil)
	sessions, _, _ := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	u := "ws" + strings.TrimPrefix(srv.URL, "http") + "/terminal/SURF1"
	h := http.Header{"X-Relay-Token": {relayTok}}
	_, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err == nil {
		t.Fatal("expected dial to fail without X-Device-ID once encryption is enabled")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401, got %v", resp)
	}
}

// dialTerminal is wsConnectEncrypted plus the handshake response, so a test can
// assert what the bridge answered about compression on the 101 itself.
func dialTerminal(t *testing.T, srvURL, path, relayTok, deviceID string) (*websocket.Conn, *http.Response) {
	t.Helper()
	u := "ws" + strings.TrimPrefix(srvURL, "http") + path
	h := http.Header{"X-Relay-Token": {relayTok}, "X-Device-ID": {deviceID}}
	c, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err != nil {
		t.Fatalf("ws dial %s failed: %v", path, err)
	}
	return c, resp
}

func newEncryptedTerminalServer(t *testing.T, script string) (*httptest.Server, string, []byte) {
	t.Helper()
	t.Setenv("CMUX_FAKE_LOG", t.TempDir()+"/cmux.log")
	bin := testutil.WriteFakeCmux(t, script)
	s := New(&cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)
	srv := httptest.NewServer(s.TrustedHandler("relay-secret"))
	t.Cleanup(srv.Close)
	return srv, deviceID, secret
}

// readFrame decrypts one frame and, when compression was negotiated, strips
// the codec tag -- the app's side of wire.EncodePayload.
func readFrame(t *testing.T, c *websocket.Conn, secret []byte, counter uint64, deflate bool) (byte, wire.TerminalDown) {
	t.Helper()
	armReadDeadline(t, c)
	_, raw, err := c.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	n, plain, err := e2e.DecodeFrame(secret, e2e.DirAgentToDevice, raw)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	if n != counter {
		t.Fatalf("want frame counter %d, got %d", counter, n)
	}
	var tag byte
	if deflate {
		tag = plain[0]
		if plain, err = wire.DecodePayload(plain); err != nil {
			t.Fatalf("DecodePayload: %v", err)
		}
	}
	var down wire.TerminalDown
	if err := json.Unmarshal(plain, &down); err != nil {
		t.Fatalf("unmarshal frame: %v", err)
	}
	return tag, down
}

func TestTerminalCompressesOnlyWhenTheClientAsks(t *testing.T) {
	srv, deviceID, secret := newEncryptedTerminalServer(t, fakeTerminalScript)

	c, resp := dialTerminal(t, srv.URL, "/terminal/SURF1", "relay-secret", deviceID)
	defer c.Close()
	if got := resp.Header.Get(deflateHeader); got != "" {
		t.Fatalf("bridge confirmed compression to a client that never asked: %q", got)
	}
	// Untagged: the payload must unmarshal straight from the sealed bytes.
	if _, down := readFrame(t, c, secret, 0, false); down.Type != "replay" {
		t.Fatalf("want an untagged replay frame, got %+v", down)
	}
}

func TestTerminalConfirmsCompressionAndTagsItsFrames(t *testing.T) {
	srv, deviceID, secret := newEncryptedTerminalServer(t, fakeTerminalScript)

	c, resp := dialTerminal(t, srv.URL, "/terminal/SURF1?deflate=1", "relay-secret", deviceID)
	defer c.Close()
	if got := resp.Header.Get(deflateHeader); got != "1" {
		t.Fatalf("bridge did not confirm compression on the 101: %q", got)
	}
	tag, down := readFrame(t, c, secret, 0, true)
	if down.Type != "replay" || down.Columns != 80 || down.Rows != 24 {
		t.Fatalf("unexpected frame through the codec: %+v", down)
	}
	if tag != wire.PayloadIdentity && tag != wire.PayloadDeflate {
		t.Fatalf("unknown codec tag %d", tag)
	}
}

// An ack is far too small for deflate to shrink, so it must go out as identity
// -- a negotiated stream must never make a frame bigger than it was.
func TestASmallFrameStaysUncompressedOnANegotiatedStream(t *testing.T) {
	srv, deviceID, secret := newEncryptedTerminalServer(t, fakeTerminalScript)

	c, _ := dialTerminal(t, srv.URL, "/terminal/SURF1?deflate=1", "relay-secret", deviceID)
	defer c.Close()
	readFrame(t, c, secret, 0, true) // drain the replay

	upBytes, err := json.Marshal(wire.TerminalUp{Type: "input", Text: "ls\r", Seq: 7})
	if err != nil {
		t.Fatal(err)
	}
	frame, err := e2e.EncodeFrame(secret, e2e.DirDeviceToAgent, 0, upBytes)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.WriteMessage(websocket.BinaryMessage, frame); err != nil {
		t.Fatal(err)
	}
	for counter := uint64(1); counter < 6; counter++ {
		tag, down := readFrame(t, c, secret, counter, true)
		if down.Type != "ack" {
			continue // an output frame raced in front of the ack
		}
		if down.Seq != 7 {
			t.Fatalf("want ack for seq 7, got %d", down.Seq)
		}
		if tag != wire.PayloadIdentity {
			t.Fatalf("an ack must not be deflated, got tag %d", tag)
		}
		return
	}
	t.Fatal("no ack frame arrived")
}

// A pane whose scrollback never changes but whose visible rows do -- the
// ordinary case for an agent printing output below a long history.
const fakeStickyScrollbackScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.terminal.replay)
    n=$(grep -c 'mobile.terminal.replay' "$CMUX_FAKE_LOG")
    cat <<JSON
{"columns":80,"rows":24,"seq":0,"surface_id":"S","workspace_id":"W","render_grid":{"format":"cmux.render-grid.v1","columns":80,"rows":24,"scrollback_rows":2,"scrollback_spans":[{"row":0,"text":"history"}],"terminal_theme":{"bg":"#000"},"row_spans":[{"row":0,"text":"line-$n"}]}}
JSON
    ;;
  *) echo '{"ok":true}' ;;
esac
`

func TestOutputFramesOmitAnUnchangedScrollback(t *testing.T) {
	srv, deviceID, secret := newEncryptedTerminalServer(t, fakeStickyScrollbackScript)

	c, resp := dialTerminal(t, srv.URL, "/terminal/SURF1?delta=1", "relay-secret", deviceID)
	defer c.Close()
	if got := resp.Header.Get(deltaHeader); got != "1" {
		t.Fatalf("bridge did not confirm delta frames on the 101: %q", got)
	}

	// The replay must be whole: it is what a reconnecting app rebuilds from.
	_, replay := readFrame(t, c, secret, 0, false)
	if replay.Type != "replay" {
		t.Fatalf("want replay first, got %q", replay.Type)
	}
	if !strings.Contains(string(replay.Grid), "scrollback_spans") {
		t.Fatal("the replay frame must carry the scrollback in full")
	}
	if len(replay.Unchanged) != 0 {
		t.Fatalf("a replay frame must omit nothing, got %v", replay.Unchanged)
	}

	_, out := readFrame(t, c, secret, 1, false)
	if out.Type != "output" {
		t.Fatalf("want an output frame, got %q", out.Type)
	}
	if strings.Contains(string(out.Grid), "scrollback_spans") {
		t.Fatalf("unchanged scrollback was repeated: %s", out.Grid)
	}
	if strings.Contains(string(out.Grid), "terminal_theme") {
		t.Fatalf("unchanged theme was repeated: %s", out.Grid)
	}
	// The visible rows did change, so they must still be there.
	if !strings.Contains(string(out.Grid), "line-") {
		t.Fatalf("output frame lost its row spans: %s", out.Grid)
	}
	want := map[string]bool{"scrollback_spans": true, "terminal_theme": true}
	if len(out.Unchanged) != len(want) {
		t.Fatalf("want %d omitted blocks named, got %v", len(want), out.Unchanged)
	}
	for _, k := range out.Unchanged {
		if !want[k] {
			t.Fatalf("unexpected block named unchanged: %q", k)
		}
	}
}

// The regression that makes the handshake necessary: an app that never asked
// must keep getting whole grids, or its scrollback silently empties.
func TestOutputFramesStayWholeWithoutTheDeltaHandshake(t *testing.T) {
	srv, deviceID, secret := newEncryptedTerminalServer(t, fakeStickyScrollbackScript)

	c, resp := dialTerminal(t, srv.URL, "/terminal/SURF1", "relay-secret", deviceID)
	defer c.Close()
	if got := resp.Header.Get(deltaHeader); got != "" {
		t.Fatalf("bridge confirmed delta frames to a client that never asked: %q", got)
	}
	readFrame(t, c, secret, 0, false) // replay

	_, out := readFrame(t, c, secret, 1, false)
	if out.Type != "output" {
		t.Fatalf("want an output frame, got %q", out.Type)
	}
	if !strings.Contains(string(out.Grid), "scrollback_spans") {
		t.Fatalf("an un-negotiated client lost its scrollback: %s", out.Grid)
	}
	if len(out.Unchanged) != 0 {
		t.Fatalf("an un-negotiated client must never be told about omissions, got %v", out.Unchanged)
	}
}

// This test used to assert that styles and modes could never be sticky,
// because they "decide what is on screen now". That reason was wrong: strip
// omits a block only while its bytes are unchanged, so a client carrying them
// forward holds what the bridge holds, and cmux-app-8wk made both sticky for
// 28% of a compressed frame. What is left is the part that was right.
//
// row_spans and cursor stay out because stickiness cannot pay there -- they
// differ on nearly every frame, so the comparison would run each time and
// almost never save anything.
func TestTheBlocksThatChangeEveryFrameAreNeverSticky(t *testing.T) {
	for _, field := range []string{"row_spans", "cursor"} {
		if slices.Contains(stickyGridFields, field) {
			t.Errorf("%q changes on nearly every frame; making it sticky costs more than it saves", field)
		}
	}
}

// Wire-format lockstep: every name the bridge can put in `unchanged` needs a
// branch in the app's RenderGrid.mergedOnto, or the app decodes the absent
// block as empty and silently loses it -- a blank scrollback, an unstyled
// screen, or arrow keys that stop matching the pane's mode.
//
// Go cannot see the Kotlin, so this pins the list instead. Adding a block here
// without adding UnchangedBlock.X and a mergedOnto branch in the same commit
// fails this test, which is the reminder.
func TestTheStickyListMatchesWhatTheAppCanCarry(t *testing.T) {
	appCanCarry := []string{
		"scrollback_spans",      // UnchangedBlock.SCROLLBACK_SPANS
		"terminal_theme",        // not modelled by the app at all, so safe to drop
		"terminal_config_theme", // likewise
		"styles",                // UnchangedBlock.STYLES
		"modes",                 // UnchangedBlock.MODES
	}
	for _, sticky := range stickyGridFields {
		if !slices.Contains(appCanCarry, sticky) {
			t.Errorf("%q is omitted by the bridge but the app has no way to carry it forward", sticky)
		}
	}
	for _, known := range appCanCarry {
		if !slices.Contains(stickyGridFields, known) {
			t.Errorf("%q is listed here but no longer sticky -- update this list with the change", known)
		}
	}
}

func TestTheDeltaEncoderRepeatsABlockThatChanged(t *testing.T) {
	d := newDeltaEncoder()
	first := json.RawMessage(`{"scrollback_spans":[{"row":0,"text":"a"}],"row_spans":[]}`)
	if _, omitted := d.strip(first); len(omitted) != 0 {
		t.Fatalf("nothing can be omitted from the first frame, got %v", omitted)
	}
	// Same scrollback -> omitted.
	if _, omitted := d.strip(first); len(omitted) != 1 || omitted[0] != "scrollback_spans" {
		t.Fatalf("want scrollback omitted on a repeat, got %v", omitted)
	}
	// Changed scrollback -> sent again, and remembered at its new value.
	grown := json.RawMessage(`{"scrollback_spans":[{"row":0,"text":"a"},{"row":1,"text":"b"}],"row_spans":[]}`)
	got, omitted := d.strip(grown)
	if len(omitted) != 0 {
		t.Fatalf("a changed scrollback must be re-sent, got %v", omitted)
	}
	if !strings.Contains(string(got), `"b"`) {
		t.Fatalf("the changed scrollback is missing from the frame: %s", got)
	}
	if _, omitted := d.strip(grown); len(omitted) != 1 {
		t.Fatalf("the new value must now be the one remembered, got %v", omitted)
	}
}

// styles and modes were deliberately excluded from stickyGridFields until
// cmux-app-8wk on the theory that a carried-over copy could go stale. It cannot
// -- strip omits a block only while its bytes are unchanged -- and together
// they are 28% of a compressed frame.
func TestStylesAndModesAreCarriedLikeAnyOtherStickyBlock(t *testing.T) {
	d := newDeltaEncoder()
	grid := json.RawMessage(`{"styles":[{"id":1}],"modes":[{"code":1,"on":true}],"row_spans":[]}`)
	if _, omitted := d.strip(grid); len(omitted) != 0 {
		t.Fatalf("nothing can be omitted from the first frame, got %v", omitted)
	}
	got, omitted := d.strip(grid)
	if len(omitted) != 2 {
		t.Fatalf("want both styles and modes omitted on a repeat, got %v", omitted)
	}
	for _, want := range []string{"styles", "modes"} {
		if !slices.Contains(omitted, want) {
			t.Errorf("%s was not omitted: %v", want, omitted)
		}
		if strings.Contains(string(got), `"`+want+`"`) {
			t.Errorf("%s was named unchanged but still sent: %s", want, got)
		}
	}
}

// The property that makes carrying them safe: the moment either changes, it is
// in the frame again. A pane that leaves application-cursor mode must not have
// the app spelling arrows against the old modes.
func TestAChangedModeIsSentAgainRatherThanCarried(t *testing.T) {
	d := newDeltaEncoder()
	on := json.RawMessage(`{"modes":[{"code":1,"on":true}],"row_spans":[]}`)
	d.strip(on)
	if _, omitted := d.strip(on); len(omitted) != 1 {
		t.Fatalf("an unchanged mode set should be omitted, got %v", omitted)
	}
	off := json.RawMessage(`{"modes":[{"code":1,"on":false}],"row_spans":[]}`)
	got, omitted := d.strip(off)
	if len(omitted) != 0 {
		t.Fatalf("a changed mode set must be re-sent, got %v", omitted)
	}
	if !strings.Contains(string(got), `"on":false`) {
		t.Fatalf("the new mode value is missing from the frame: %s", got)
	}
}

func TestTheDeltaEncoderPassesAnUndecodableGridThrough(t *testing.T) {
	d := newDeltaEncoder()
	bad := json.RawMessage(`not json`)
	got, omitted := d.strip(bad)
	if !bytes.Equal(got, bad) || omitted != nil {
		t.Fatalf("an undecodable grid must pass through whole, got %s / %v", got, omitted)
	}
}
