package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

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

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
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
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
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

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(logPath)
		if strings.Contains(string(data), "mobile.terminal.input") &&
			strings.Contains(string(data), "SURF1") &&
			strings.Contains(string(data), "ls") {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	data, _ := os.ReadFile(logPath)
	t.Fatalf("input rpc not dispatched; log:\n%s", data)
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
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
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

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
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
