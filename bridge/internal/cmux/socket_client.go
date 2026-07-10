package cmux

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// socketConn is a persistent, authenticated connection to the cmux app's
// control socket, speaking the same newline-delimited-JSON v2 RPC protocol
// used by `events.stream` (see docs/events.md): one JSON request line in,
// one JSON response line out, `{"id","method","params"}` -> `{"id","ok",
// "result"|"error"}`. The generic request/response shape and the plain-text
// `auth <password>` handshake it requires first are not in cmux's published
// docs (only events.stream's request is documented) -- reverse-engineered
// live against a running cmux by connecting directly and reading its
// self-describing errors ("Authentication required. Send auth <password>
// first."). Confirmed live: reusing one authenticated connection costs
// ~2-3ms per call, versus ~130ms for `cmux rpc`'s fresh process-spawn (plus
// its own socket connect/auth) on every single call.
type socketConn struct {
	mu     sync.Mutex
	conn   net.Conn
	reader *bufio.Reader
	nextID uint64
}

const socketIOTimeout = 5 * time.Second

// resolveSocketPath mirrors the cmux CLI's own precedence: CMUX_SOCKET_PATH
// first. Without it, `cmux --help` says the CLI "defaults to
// ~/.local/state/cmux/cmux.sock and auto-discovers tagged/debug sockets" --
// that auto-discovery algorithm isn't documented, so this instead reads
// last-socket-path, the file cmux itself writes with the path of the socket
// it most recently bound (confirmed live to match CMUX_SOCKET_PATH exactly
// when both are present).
func resolveSocketPath() (string, error) {
	if p := os.Getenv("CMUX_SOCKET_PATH"); p != "" {
		return p, nil
	}
	stateDir, err := cmuxStateDir()
	if err != nil {
		return "", err
	}
	if b, err := os.ReadFile(filepath.Join(stateDir, "last-socket-path")); err == nil {
		if p := strings.TrimSpace(string(b)); p != "" {
			return p, nil
		}
	}
	return filepath.Join(stateDir, "cmux.sock"), nil
}

// resolveSocketPassword mirrors "--password takes precedence, then
// CMUX_SOCKET_PASSWORD env var, then password saved in Settings" (per `cmux
// --help`'s Socket Auth section): the flat file cmux itself writes is the
// only password-shaped state present, so it's treated as that third tier.
func resolveSocketPassword() (string, error) {
	if p := os.Getenv("CMUX_SOCKET_PASSWORD"); p != "" {
		return p, nil
	}
	stateDir, err := cmuxStateDir()
	if err != nil {
		return "", err
	}
	b, err := os.ReadFile(filepath.Join(stateDir, "socket-control-password"))
	if err != nil {
		return "", fmt.Errorf("read socket-control-password: %w", err)
	}
	return strings.TrimSpace(string(b)), nil
}

func cmuxStateDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home dir: %w", err)
	}
	return filepath.Join(home, ".local", "state", "cmux"), nil
}

// connectLocked dials and authenticates a fresh connection. Caller holds mu.
func (sc *socketConn) connectLocked() error {
	path, err := resolveSocketPath()
	if err != nil {
		return err
	}
	password, err := resolveSocketPassword()
	if err != nil {
		return err
	}
	conn, err := net.DialTimeout("unix", path, socketIOTimeout)
	if err != nil {
		return fmt.Errorf("dial %s: %w", path, err)
	}
	if err := conn.SetDeadline(time.Now().Add(socketIOTimeout)); err != nil {
		_ = conn.Close()
		return fmt.Errorf("set deadline: %w", err)
	}
	if _, err := conn.Write([]byte("auth " + password + "\n")); err != nil {
		_ = conn.Close()
		return fmt.Errorf("write auth: %w", err)
	}
	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil {
		_ = conn.Close()
		return fmt.Errorf("read auth response: %w", err)
	}
	if !strings.HasPrefix(line, "OK:") {
		_ = conn.Close()
		return fmt.Errorf("auth rejected: %s", strings.TrimSpace(line))
	}
	sc.conn = conn
	sc.reader = reader
	return nil
}

func (sc *socketConn) closeLocked() {
	if sc.conn != nil {
		_ = sc.conn.Close()
		sc.conn = nil
		sc.reader = nil
	}
}

// close drops the current connection (if any), forcing the next rpc call to
// reconnect from scratch. Exported within the package for tests that need to
// simulate the connection dying underneath the client.
func (sc *socketConn) close() {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	sc.closeLocked()
}

type socketRequest struct {
	ID     string `json:"id"`
	Method string `json:"method"`
	Params any    `json:"params,omitempty"`
}

type socketResponse struct {
	ID     string          `json:"id"`
	OK     bool            `json:"ok"`
	Result json.RawMessage `json:"result"`
	Error  *struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// rpc sends one request over the persistent connection, (re)connecting first
// if needed. The returned committed flag distinguishes two failure classes:
// false means nothing was sent to cmux for this call (connect/auth itself
// failed), so it's safe for the caller to retry via another transport; true
// means the request was written (and may have already taken effect on
// cmux's side) even though this call is still reporting failure, so the
// caller must treat it as final -- typed input isn't idempotent, and retrying
// an ambiguous send through a different transport risks double-executing it.
func (sc *socketConn) rpc(ctx context.Context, method string, params any) (result json.RawMessage, committed bool, err error) {
	sc.mu.Lock()
	defer sc.mu.Unlock()

	if sc.conn == nil {
		if err := sc.connectLocked(); err != nil {
			return nil, false, err
		}
	}

	deadline := time.Now().Add(socketIOTimeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	_ = sc.conn.SetDeadline(deadline)

	// Unblock the read/write below promptly if ctx is cancelled mid-flight
	// (e.g. the owning WebSocket connection closes) instead of waiting out
	// the full timeout.
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			_ = sc.conn.SetDeadline(time.Now())
		case <-done:
		}
	}()

	id := strconv.FormatUint(atomic.AddUint64(&sc.nextID, 1), 10)
	req, err := json.Marshal(socketRequest{ID: id, Method: method, Params: params})
	if err != nil {
		return nil, false, fmt.Errorf("marshal params: %w", err)
	}

	if _, err := sc.conn.Write(append(req, '\n')); err != nil {
		sc.closeLocked()
		return nil, true, fmt.Errorf("cmux socket rpc %s: write: %w", method, err)
	}

	line, err := sc.reader.ReadString('\n')
	if err != nil {
		sc.closeLocked()
		return nil, true, fmt.Errorf("cmux socket rpc %s: read: %w", method, err)
	}

	var resp socketResponse
	if err := json.Unmarshal([]byte(line), &resp); err != nil {
		sc.closeLocked()
		return nil, true, fmt.Errorf("cmux socket rpc %s: decode response: %w", method, err)
	}
	if resp.ID != id {
		sc.closeLocked()
		return nil, true, fmt.Errorf("cmux socket rpc %s: response id mismatch: want %s got %s", method, id, resp.ID)
	}
	if !resp.OK {
		code, msg := "unknown", "rpc failed"
		if resp.Error != nil {
			code, msg = resp.Error.Code, resp.Error.Message
		}
		return nil, true, fmt.Errorf("cmux rpc %s: %s: %s", method, code, msg)
	}
	return resp.Result, true, nil
}
