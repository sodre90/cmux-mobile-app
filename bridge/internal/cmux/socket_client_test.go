package cmux

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/testutil"
)

const fakeSocketPassword = "test-password"

// shortSocketDir returns a fresh temp dir short enough to hold a Unix socket
// path under sockaddr_un's ~104-byte sun_path limit -- t.TempDir() embeds the
// (often long) test name and reliably blows past that on macOS.
func shortSocketDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "cmuxsock")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return dir
}

// startFakeCmuxSocket runs a minimal stand-in for cmux's control socket:
// requires "auth <password>\n" first, then answers each JSON request line
// with respond's result. Points CMUX_SOCKET_PATH/CMUX_SOCKET_PASSWORD at it
// for the duration of the test.
func startFakeCmuxSocket(t *testing.T, password string, respond func(method string, params json.RawMessage) (result json.RawMessage, errCode, errMsg string)) {
	t.Helper()
	path := filepath.Join(shortSocketDir(t), "cmux.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				r := bufio.NewReader(conn)
				authLine, err := r.ReadString('\n')
				if err != nil {
					return
				}
				if strings.TrimSpace(authLine) != "auth "+password {
					conn.Write([]byte("ERROR: Invalid password\n"))
					return
				}
				conn.Write([]byte("OK: Authenticated\n"))
				for {
					reqLine, err := r.ReadString('\n')
					if err != nil {
						return
					}
					var req struct {
						ID     string          `json:"id"`
						Method string          `json:"method"`
						Params json.RawMessage `json:"params"`
					}
					if err := json.Unmarshal([]byte(reqLine), &req); err != nil {
						return
					}
					result, errCode, errMsg := respond(req.Method, req.Params)
					var resp map[string]any
					if errCode != "" {
						resp = map[string]any{"id": req.ID, "ok": false, "error": map[string]string{"code": errCode, "message": errMsg}}
					} else {
						resp = map[string]any{"id": req.ID, "ok": true, "result": result}
					}
					b, _ := json.Marshal(resp)
					conn.Write(append(b, '\n'))
				}
			}()
		}
	}()
	t.Setenv("CMUX_SOCKET_PATH", path)
	t.Setenv("CMUX_SOCKET_PASSWORD", password)
}

func TestFastPathUsedWhenSocketAvailable(t *testing.T) {
	startFakeCmuxSocket(t, fakeSocketPassword, func(method string, params json.RawMessage) (json.RawMessage, string, string) {
		return json.RawMessage(`{"via":"socket","method":"` + method + `"}`), "", ""
	})
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
echo "subprocess should not have been used" >&2
exit 1
`)
	c := &Client{Bin: bin, FastPath: true}
	out, err := c.Rpc(context.Background(), "mobile.workspace.list", nil)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(out), `"via":"socket"`) {
		t.Fatalf("want response via socket fast path, got %s", out)
	}
}

func TestFastPathFallsBackWhenSocketUnreachable(t *testing.T) {
	t.Setenv("CMUX_SOCKET_PATH", filepath.Join(t.TempDir(), "does-not-exist.sock"))
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
printf '{"via":"subprocess"}'
`)
	c := &Client{Bin: bin, FastPath: true}
	out, err := c.Rpc(context.Background(), "mobile.workspace.list", nil)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(out), `"via":"subprocess"`) {
		t.Fatalf("want fallback to subprocess, got %s", out)
	}
}

func TestFastPathFallsBackWhenAuthRejected(t *testing.T) {
	startFakeCmuxSocket(t, fakeSocketPassword, func(method string, params json.RawMessage) (json.RawMessage, string, string) {
		return json.RawMessage(`{}`), "", ""
	})
	// Override the password the client will actually send so the fake server
	// rejects it -- exercises the auth-failure branch of connectLocked.
	t.Setenv("CMUX_SOCKET_PASSWORD", "wrong-password")
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
printf '{"via":"subprocess"}'
`)
	c := &Client{Bin: bin, FastPath: true}
	out, err := c.Rpc(context.Background(), "mobile.workspace.list", nil)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(out), `"via":"subprocess"`) {
		t.Fatalf("want fallback to subprocess, got %s", out)
	}
}

// TestFastPathCommittedFailureDoesNotFallBack is the safety-critical case:
// once a request has actually been written to cmux's socket, a subsequent
// failure (here, the fake server closes the connection without responding at
// all) must be reported as-is, never retried via the subprocess CLI --
// retrying an ambiguous send through a second transport risks
// double-executing non-idempotent input (e.g. typed keystrokes).
func TestFastPathCommittedFailureDoesNotFallBack(t *testing.T) {
	path := filepath.Join(shortSocketDir(t), "cmux.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		r := bufio.NewReader(conn)
		authLine, _ := r.ReadString('\n')
		if strings.TrimSpace(authLine) != "auth "+fakeSocketPassword {
			conn.Close()
			return
		}
		conn.Write([]byte("OK: Authenticated\n"))
		// Read (and discard) the RPC request, proving it was sent, then hang
		// up without a response -- simulates cmux crashing mid-call.
		r.ReadString('\n')
		conn.Close()
	}()
	t.Setenv("CMUX_SOCKET_PATH", path)
	t.Setenv("CMUX_SOCKET_PASSWORD", fakeSocketPassword)

	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
printf '{"via":"subprocess"}'
`)
	c := &Client{Bin: bin, FastPath: true}
	_, err = c.Rpc(context.Background(), "mobile.terminal.input", map[string]any{"text": "rm -rf /"})
	if err == nil {
		t.Fatal("want an error (the connection was dropped mid-call), got nil")
	}
	if strings.Contains(err.Error(), "subprocess") {
		t.Fatalf("must not have fallen back to the subprocess CLI after a committed send, got %v", err)
	}
}

func TestFastPathReconnectsAfterConnectionDrop(t *testing.T) {
	var calls atomic.Int32
	startFakeCmuxSocket(t, fakeSocketPassword, func(method string, params json.RawMessage) (json.RawMessage, string, string) {
		calls.Add(1)
		return json.RawMessage(`{"via":"socket"}`), "", ""
	})
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
echo "subprocess should not have been used" >&2
exit 1
`)
	c := &Client{Bin: bin, FastPath: true}

	if _, err := c.Rpc(context.Background(), "mobile.workspace.list", nil); err != nil {
		t.Fatalf("first call: %v", err)
	}
	// Forcibly break every pooled connection (simulates cmux restarting,
	// which drops every open connection at once, not just the one this
	// particular call happened to use) and confirm the very next call
	// transparently reconnects rather than staying stuck on a broken one.
	c.fastPathPool().closeAll()
	time.Sleep(20 * time.Millisecond)

	out, err := c.Rpc(context.Background(), "mobile.workspace.list", nil)
	if err != nil {
		t.Fatalf("second call after drop: %v", err)
	}
	if !strings.Contains(string(out), `"via":"socket"`) {
		t.Fatalf("want reconnected socket response, got %s", out)
	}
	if calls.Load() != 2 {
		t.Fatalf("want 2 socket-side calls, got %d", calls.Load())
	}
}

// TestFastPathPoolSlowCallDoesNotBlockConcurrentFastCall is the accept
// criterion for the pool: before this pool existed, every fast-path call
// funneled through one shared socketConn whose mutex was held for the whole
// round trip, so a slow call (e.g. a big replay poll) head-of-line-blocked
// any unrelated fast call (e.g. an input ack) issued concurrently. Confirmed
// against the pre-pool code: a fast call issued 20ms after a 200ms-sleeping
// slow call still took ~180ms to complete. With a multi-connection pool the
// fast call should land on a different, idle connection and complete close
// to its own server-side latency instead of waiting out the slow one.
func TestFastPathPoolSlowCallDoesNotBlockConcurrentFastCall(t *testing.T) {
	release := make(chan struct{})
	startFakeCmuxSocket(t, fakeSocketPassword, func(method string, params json.RawMessage) (json.RawMessage, string, string) {
		if method == "slow" {
			<-release
		}
		return json.RawMessage(`{}`), "", ""
	})
	c := &Client{FastPath: true}

	slowDone := make(chan struct{})
	go func() {
		defer close(slowDone)
		if _, err := c.Rpc(context.Background(), "slow", nil); err != nil {
			t.Error(err)
		}
	}()
	// Give the slow call time to be checked out and blocked server-side
	// before starting the fast one, so this deterministically exercises "an
	// unrelated call started while a slow one is in flight," not a race.
	time.Sleep(20 * time.Millisecond)

	fastStart := time.Now()
	if _, err := c.Rpc(context.Background(), "fast", nil); err != nil {
		t.Fatal(err)
	}
	fastElapsed := time.Since(fastStart)
	close(release)
	<-slowDone

	if fastElapsed > 50*time.Millisecond {
		t.Fatalf("fast call took %v while a slow call was in flight on another pooled connection; want it to complete promptly, not queue behind the slow one", fastElapsed)
	}
}
