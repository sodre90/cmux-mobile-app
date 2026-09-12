package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
