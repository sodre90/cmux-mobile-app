# cmux Relay (v2 rendezvous topology) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the Mac-resident `cmux-bridge` into a Mac **agent** that dials out and a home-server **relay**, so the Android app reaches cmux from anywhere through one nginx-mTLS endpoint, multiplexed over a single yamux-over-WSS tunnel.

**Architecture:** A new `cmux-relay` binary on the home server accepts the Mac agent's persistent outbound WSS (`/agent/tunnel`), wraps it as a `net.Conn`, and runs **yamux** over it. The relay reverse-proxies each authenticated app request as a fresh yamux stream; the Mac agent serves its **existing** `server.Handler` over the session via `http.Serve`. The relay owns device auth, pairing, and FCM push; the agent owns cmux. App offline = no session → `503 agent_offline`.

**Tech Stack:** Go 1.26, `github.com/hashicorp/yamux` (new), `github.com/gorilla/websocket` (existing), `httputil.ReverseProxy`, `BurntSushi/toml`, `golang.org/x/oauth2`.

## Global Constraints

- **The app HTTP/WS contract is preserved at the relay's app edge** (`GET /sessions`, `WS /events`, `WS /terminal/{id}`, `POST /feed/{id}/reply`, `POST /devices/register`). The Android app changes only its base URL.
- **One edge, one CA:** Mac agent and app devices both connect via the same nginx mTLS endpoint on the home domain:443, each presenting a client cert signed by the existing client CA. The Mac connects as a remote client over the domain.
- **Single Mac (v1):** the relay holds exactly one agent session; a new tunnel replaces (and closes) the prior one.
- **Relay replaces the Mac-direct remote path.**
- **One new dependency only:** `github.com/hashicorp/yamux`.
- **Single Go module** `github.com/sodre90/cmux-bridge` (the `bridge/` tree), shipping two binaries.
- **Test discipline:** no real network egress, no real cmux (fake cmux binary via `testutil.WriteFakeCmux`), no real FCM (a `Pusher` fake). Matches the existing suite.
- **Mac-offline (v1) = clear offline state.** No Wake-on-LAN, no queueing.
- **Defense in depth:** the relay injects `X-Relay-Token: <shared-secret>` on every proxied request; the agent rejects requests lacking it.
- Commits authored solely by the human (`sodre90 <erdos.peter.bme@gmail.com>`); **no AI co-author trailers.**

**Run tests with:** `go -C /Users/perdos/prj/cmux-app/bridge test ./...` (the `-C` flag avoids a `cd`).

---

## File structure

```
bridge/
  go.mod / go.sum                      # + github.com/hashicorp/yamux
  cmd/
    cmux-bridge/                       # Mac AGENT
      main.go                          # subcommands: agent | serve | version  (MODIFY)
      agent.go                         # NEW: dial loop, loadTLS, dialAndServe, backoff
      commands.go                      # pair/devices REMOVED (MODIFY)
    cmux-relay/                        # NEW binary
      main.go                          # subcommands: serve | pair | devices | version
      serve.go                         # relay wiring: config, store, pusher, session hook
      commands.go                      # pair/devices (MOVED here)
  internal/
    tunnel/
      conn.go                          # NEW: *websocket.Conn → net.Conn adapter
      conn_test.go
      tunnel.go                        # NEW: Dial (agent) / Accept (relay) → *yamux.Session
      tunnel_test.go
    relay/
      registry.go                      # NEW: single-session registry
      registry_test.go
      proxy.go                         # NEW: reverse proxy over yamux + 503 + writeJSONErr
      proxy_test.go
      relay.go                         # NEW: mux, CN routing, tunnel accept, register
      relay_test.go                    # end-to-end tunnel+relay+trusted server
      pushmon.go                       # NEW: relay /events subscription → FCM
      pushmon_test.go
    server/
      server.go                        # MODIFY: Handler() delegates to routes()
      trusted.go                       # NEW: RequireRelayToken, routes(), TrustedHandler
      trusted_test.go
    config/
      config.go                        # MODIFY: + AgentCN, RelayToken on Config
      agent.go                         # NEW: AgentConfig + LoadAgent
      agent_test.go
  deploy/
    cmux-relay.service                 # NEW systemd unit
    nginx-cmux-relay.conf              # NEW home-server vhost
    relay.example.toml                 # NEW
    agent.example.toml                 # NEW
  README.md                            # MODIFY: agent vs relay, agent cert steps
```

Bridge JSON / Go types reused unchanged: `server.EventFrame`, `server.Session`, `auth.Store`, `push.Sender`.

---

### Task 1: yamux dependency + WebSocket→net.Conn adapter

**Files:**
- Modify: `bridge/go.mod`, `bridge/go.sum`
- Create: `bridge/internal/tunnel/conn.go`, `bridge/internal/tunnel/conn_test.go`

**Interfaces:**
- Produces: an unexported `*wsConn` implementing `net.Conn` over binary WebSocket messages; constructor `newWSConn(ws *websocket.Conn) *wsConn`.

- [ ] **Step 1: Add the dependency**

Run: `go -C /Users/perdos/prj/cmux-app/bridge get github.com/hashicorp/yamux@v0.1.2`
Expected: `go.mod`/`go.sum` updated with `github.com/hashicorp/yamux v0.1.2`.

- [ ] **Step 2: Write the failing test**

`bridge/internal/tunnel/conn_test.go`:
```go
package tunnel

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

func TestWSConnRoundTrip(t *testing.T) {
	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		_, _ = io.Copy(newWSConn(ws), newWSConn(ws)) // echo bytes back
	}))
	defer srv.Close()

	u := "ws" + strings.TrimPrefix(srv.URL, "http")
	ws, _, err := websocket.DefaultDialer.Dial(u, nil)
	if err != nil {
		t.Fatal(err)
	}
	nc := newWSConn(ws)
	msg := []byte("hello yamux over websocket")
	if _, err := nc.Write(msg); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len(msg))
	if _, err := io.ReadFull(nc, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != string(msg) {
		t.Fatalf("round trip got %q", buf)
	}
}
```

> Note: wrapping the same `ws` in two `newWSConn` for `io.Copy` is fine — both share the one gorilla conn; reads and writes are serialized by the adapter's mutexes.

- [ ] **Step 3: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/tunnel/ -run TestWSConnRoundTrip`
Expected: FAIL — `undefined: newWSConn`.

- [ ] **Step 4: Implement the adapter**

`bridge/internal/tunnel/conn.go`:
```go
// Package tunnel carries the cmux relay's Mac↔relay link: a single WebSocket
// (so it traverses nginx mTLS on :443) wrapped as a net.Conn and multiplexed
// with yamux. The agent runs http.Serve over the session; the relay opens one
// stream per proxied request.
package tunnel

import (
	"io"
	"net"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// wsConn adapts a gorilla *websocket.Conn (message framed) to a net.Conn (byte
// stream) by carrying bytes in binary WebSocket messages. yamux runs on top.
// yamux drives all reads from one goroutine and all writes from one goroutine;
// the mutexes are belt-and-suspenders.
type wsConn struct {
	ws  *websocket.Conn
	rmu sync.Mutex
	wmu sync.Mutex
	r   io.Reader // current message reader, or nil between messages
}

func newWSConn(ws *websocket.Conn) *wsConn { return &wsConn{ws: ws} }

func (c *wsConn) Read(p []byte) (int, error) {
	c.rmu.Lock()
	defer c.rmu.Unlock()
	for {
		if c.r != nil {
			n, err := c.r.Read(p)
			if err == io.EOF {
				c.r = nil
				if n > 0 {
					return n, nil
				}
				continue
			}
			return n, err
		}
		mt, r, err := c.ws.NextReader()
		if err != nil {
			return 0, err
		}
		if mt != websocket.BinaryMessage {
			continue
		}
		c.r = r
	}
}

func (c *wsConn) Write(p []byte) (int, error) {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	if err := c.ws.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wsConn) Close() error                      { return c.ws.Close() }
func (c *wsConn) LocalAddr() net.Addr               { return c.ws.LocalAddr() }
func (c *wsConn) RemoteAddr() net.Addr              { return c.ws.RemoteAddr() }
func (c *wsConn) SetReadDeadline(t time.Time) error  { return c.ws.SetReadDeadline(t) }
func (c *wsConn) SetWriteDeadline(t time.Time) error { return c.ws.SetWriteDeadline(t) }

func (c *wsConn) SetDeadline(t time.Time) error {
	if err := c.ws.SetReadDeadline(t); err != nil {
		return err
	}
	return c.ws.SetWriteDeadline(t)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/tunnel/ -run TestWSConnRoundTrip`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add bridge/go.mod bridge/go.sum bridge/internal/tunnel/conn.go bridge/internal/tunnel/conn_test.go
git commit -m "feat(relay): websocket-to-net.Conn adapter for the tunnel"
```

---

### Task 2: Tunnel sessions — Dial (agent) and Accept (relay)

**Files:**
- Create: `bridge/internal/tunnel/tunnel.go`, `bridge/internal/tunnel/tunnel_test.go`

**Interfaces:**
- Consumes: `newWSConn` (Task 1).
- Produces:
  ```go
  func Accept(w http.ResponseWriter, r *http.Request) (*yamux.Session, error) // relay: WSS upgrade → yamux.Client
  func Dial(ctx context.Context, relayURL string, tlsCfg *tls.Config, header http.Header) (*yamux.Session, error) // agent: WSS dial → yamux.Server
  ```
  Convention: agent = `yamux.Server` (accepts streams via `http.Serve`); relay = `yamux.Client` (opens streams). Both roles can open/accept; this pairing is required for yamux to agree on stream-id parity.

- [ ] **Step 1: Write the failing test**

`bridge/internal/tunnel/tunnel_test.go`:
```go
package tunnel

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSessionStreamRoundTrip(t *testing.T) {
	// Relay side: accept the tunnel, open a stream, write "ping".
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sess, err := Accept(w, r)
		if err != nil {
			return
		}
		st, err := sess.Open()
		if err != nil {
			return
		}
		_, _ = st.Write([]byte("ping"))
		_ = st.Close()
	}))
	defer srv.Close()

	u := "ws" + strings.TrimPrefix(srv.URL, "http")
	sess, err := Dial(context.Background(), u, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()

	st, err := sess.Accept()
	if err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(st, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "ping" {
		t.Fatalf("got %q", buf)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/tunnel/ -run TestSessionStreamRoundTrip`
Expected: FAIL — `undefined: Accept` / `undefined: Dial`.

- [ ] **Step 3: Implement**

`bridge/internal/tunnel/tunnel.go`:
```go
package tunnel

import (
	"context"
	"crypto/tls"
	"io"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"
)

func yamuxCfg() *yamux.Config {
	c := yamux.DefaultConfig()
	c.EnableKeepAlive = true
	c.KeepAliveInterval = 15 * time.Second
	c.ConnectionWriteTimeout = 10 * time.Second
	c.LogOutput = io.Discard
	return c
}

var acceptUpgrader = websocket.Upgrader{
	CheckOrigin: func(*http.Request) bool { return true }, // auth is mTLS + CN, not Origin
}

// Accept upgrades an inbound agent request to a WebSocket and returns a yamux
// session on which the relay opens one stream per proxied request.
func Accept(w http.ResponseWriter, r *http.Request) (*yamux.Session, error) {
	ws, err := acceptUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return nil, err
	}
	return yamux.Client(newWSConn(ws), yamuxCfg())
}

// Dial opens the agent's outbound WebSocket to relayURL (wss://…/agent/tunnel),
// presenting the client cert in tlsCfg, and returns a yamux session the agent
// serves with http.Serve.
func Dial(ctx context.Context, relayURL string, tlsCfg *tls.Config, header http.Header) (*yamux.Session, error) {
	d := websocket.Dialer{
		TLSClientConfig:  tlsCfg,
		HandshakeTimeout: 15 * time.Second,
	}
	ws, _, err := d.DialContext(ctx, relayURL, header)
	if err != nil {
		return nil, err
	}
	return yamux.Server(newWSConn(ws), yamuxCfg())
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/tunnel/`
Expected: PASS (both tunnel tests).

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/tunnel/tunnel.go bridge/internal/tunnel/tunnel_test.go
git commit -m "feat(relay): yamux tunnel Dial/Accept over WSS"
```

---

### Task 3: Server trusted mode (skip Bearer, require X-Relay-Token)

**Files:**
- Modify: `bridge/internal/server/server.go` (Handler delegates to `routes`)
- Create: `bridge/internal/server/trusted.go`, `bridge/internal/server/trusted_test.go`

**Interfaces:**
- Consumes: existing handlers `handleSessions/handleEvents/handleTerminal/handleFeedReply/handleDeviceRegister`, `auth.Require`.
- Produces:
  ```go
  func RequireRelayToken(token string, next http.Handler) http.Handler
  func (s *Server) routes(wrap func(http.Handler) http.Handler, includeRegister bool) http.Handler
  func (s *Server) TrustedHandler(relayToken string) http.Handler
  ```
  `TrustedHandler` mounts every route except `/devices/register`, each gated by `RequireRelayToken` instead of the device bearer.

- [ ] **Step 1: Write the failing test**

`bridge/internal/server/trusted_test.go`:
```go
package server

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestTrustedHandlerRelayToken(t *testing.T) {
	script := "#!/bin/sh\ncat <<'JSON'\n" + fakeWorkspaceList + "\nJSON\n"
	s, _ := newTestServer(t, script)
	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	// No relay token → 401.
	resp, err := http.Get(srv.URL + "/sessions")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 without relay token, got %d", resp.StatusCode)
	}

	// Correct relay token → 200, no device bearer needed.
	req, _ := http.NewRequest("GET", srv.URL+"/sessions", nil)
	req.Header.Set("X-Relay-Token", relayTok)
	resp2, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("want 200 with relay token, got %d", resp2.StatusCode)
	}

	// /devices/register is not mounted in trusted mode → 404.
	req3, _ := http.NewRequest("POST", srv.URL+"/devices/register", nil)
	req3.Header.Set("X-Relay-Token", relayTok)
	resp3, err := http.DefaultClient.Do(req3)
	if err != nil {
		t.Fatal(err)
	}
	resp3.Body.Close()
	if resp3.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404 for register in trusted mode, got %d", resp3.StatusCode)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/server/ -run TestTrustedHandlerRelayToken`
Expected: FAIL — `s.TrustedHandler undefined`.

- [ ] **Step 3: Implement `trusted.go` and refactor `Handler`**

`bridge/internal/server/trusted.go`:
```go
package server

import (
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// RequireRelayToken gates a handler behind a static shared token sent by the
// relay as X-Relay-Token. On the agent this replaces device-bearer auth: the
// relay is the device gatekeeper, and the only reachable path to the agent is
// the mutually-authenticated tunnel.
func RequireRelayToken(token string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if token == "" || r.Header.Get("X-Relay-Token") != token {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}
		next.ServeHTTP(w, r)
	})
}

// routes wires the API onto a mux using wrap as the per-route middleware. When
// includeRegister is false (agent/trusted mode), /devices/register is omitted —
// the relay owns device registration.
func (s *Server) routes(wrap func(http.Handler) http.Handler, includeRegister bool) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /sessions", wrap(http.HandlerFunc(s.handleSessions)))
	mux.Handle("GET /events", wrap(http.HandlerFunc(s.handleEvents)))
	mux.Handle("GET /terminal/{id}", wrap(http.HandlerFunc(s.handleTerminal)))
	mux.Handle("POST /feed/{id}/reply", wrap(http.HandlerFunc(s.handleFeedReply)))
	if includeRegister {
		mux.Handle("POST /devices/register", wrap(http.HandlerFunc(s.handleDeviceRegister)))
	}
	return mux
}

// TrustedHandler is the handler the Mac agent serves over the relay tunnel:
// device-bearer auth is replaced by the relay-token check and /devices/register
// is dropped (the relay handles it).
func (s *Server) TrustedHandler(relayToken string) http.Handler {
	return s.routes(func(h http.Handler) http.Handler {
		return RequireRelayToken(relayToken, h)
	}, false)
}

// authWrap is the production device-bearer middleware used by Handler.
func (s *Server) authWrap(h http.Handler) http.Handler { return auth.Require(s.store, h) }
```

Replace the body of `Handler()` in `bridge/internal/server/server.go` (currently lines 47–56) with:
```go
// Handler returns the fully-wired HTTP handler (device-bearer auth on every
// route; the public edge adds mTLS in front).
func (s *Server) Handler() http.Handler {
	return s.routes(s.authWrap, true)
}
```
Then remove the now-unused `auth` import from `server.go` **only if** `go build` complains (it will, since `auth.Require` moved to `trusted.go`; delete the `"github.com/sodre90/cmux-bridge/internal/auth"` import line from `server.go`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/server/`
Expected: PASS (new test plus all existing server tests, which still exercise `Handler()`).

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/server.go bridge/internal/server/trusted.go bridge/internal/server/trusted_test.go
git commit -m "feat(relay): server trusted mode for tunnel-served requests"
```

---

### Task 4: Config split — AgentConfig + relay fields

**Files:**
- Modify: `bridge/internal/config/config.go` (add `AgentCN`, `RelayToken`)
- Create: `bridge/internal/config/agent.go`, `bridge/internal/config/agent_test.go`

**Interfaces:**
- Produces:
  ```go
  // Config (existing, now the RELAY config) gains:
  //   AgentCN    string `toml:"agent_cn"`
  //   RelayToken string `toml:"relay_token"`
  type AgentConfig struct {
      CmuxBin    string `toml:"cmux_bin"`
      RelayURL   string `toml:"relay_url"`
      ClientCert string `toml:"client_cert"`
      ClientKey  string `toml:"client_key"`
      CACert     string `toml:"ca_cert"`
      RelayToken string `toml:"relay_token"`
  }
  func LoadAgent(path string) (AgentConfig, error)
  ```
  `LoadAgent` defaults `CmuxBin` to `"cmux"`, expands `~/` in the three cert paths, and returns defaults when the file is missing.

- [ ] **Step 1: Write the failing test**

`bridge/internal/config/agent_test.go`:
```go
package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadAgentParses(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
relay_url   = "wss://cmux.example.com/agent/tunnel"
client_cert = "/c/agent.crt"
client_key  = "/c/agent.key"
ca_cert     = "/c/ca.crt"
relay_token = "secret"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.RelayURL != "wss://cmux.example.com/agent/tunnel" {
		t.Fatalf("relay_url = %q", cfg.RelayURL)
	}
	if cfg.RelayToken != "secret" || cfg.ClientCert != "/c/agent.crt" {
		t.Fatalf("unexpected cfg: %+v", cfg)
	}
	if cfg.CmuxBin != "cmux" {
		t.Fatalf("CmuxBin default = %q, want cmux", cfg.CmuxBin)
	}
}

func TestLoadAgentMissingFileDefaults(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.CmuxBin != "cmux" {
		t.Fatalf("CmuxBin default = %q", cfg.CmuxBin)
	}
}

func TestConfigRelayFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "relay.toml")
	if err := os.WriteFile(path, []byte("agent_cn=\"mac-agent\"\nrelay_token=\"secret\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AgentCN != "mac-agent" || cfg.RelayToken != "secret" {
		t.Fatalf("relay fields not parsed: %+v", cfg)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/config/ -run 'TestLoadAgent|TestConfigRelay'`
Expected: FAIL — `undefined: LoadAgent`, `cfg.AgentCN undefined`.

- [ ] **Step 3: Implement**

Add to the `Config` struct in `bridge/internal/config/config.go` (after `FCMCredentials`):
```go
	// AgentCN is the client-cert CN the relay trusts as the Mac agent.
	AgentCN string `toml:"agent_cn"`
	// RelayToken is the shared secret the relay injects and the agent checks.
	RelayToken string `toml:"relay_token"`
```

`bridge/internal/config/agent.go`:
```go
package config

import (
	"errors"
	"fmt"
	"io/fs"
	"os"

	"github.com/BurntSushi/toml"
)

// AgentConfig is the Mac agent's configuration. The agent dials the relay and
// serves the cmux handler over the tunnel; it holds no device secrets.
type AgentConfig struct {
	CmuxBin    string `toml:"cmux_bin"`
	RelayURL   string `toml:"relay_url"`
	ClientCert string `toml:"client_cert"`
	ClientKey  string `toml:"client_key"`
	CACert     string `toml:"ca_cert"`
	RelayToken string `toml:"relay_token"`
}

func agentDefaults() AgentConfig { return AgentConfig{CmuxBin: "cmux"} }

// LoadAgent reads the agent TOML at path. A missing file yields defaults.
func LoadAgent(path string) (AgentConfig, error) {
	cfg := agentDefaults()
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return cfg, nil
		}
		return cfg, fmt.Errorf("read agent config %s: %w", path, err)
	}
	if err := toml.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("parse agent config %s: %w", path, err)
	}
	if cfg.CmuxBin == "" {
		cfg.CmuxBin = "cmux"
	}
	cfg.ClientCert = expandHome(cfg.ClientCert)
	cfg.ClientKey = expandHome(cfg.ClientKey)
	cfg.CACert = expandHome(cfg.CACert)
	return cfg, nil
}
```
(`expandHome` already exists in `config.go` and is reused.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/config/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/config/config.go bridge/internal/config/agent.go bridge/internal/config/agent_test.go
git commit -m "feat(relay): split config into agent and relay shapes"
```

---

### Task 5: Single-session registry

**Files:**
- Create: `bridge/internal/relay/registry.go`, `bridge/internal/relay/registry_test.go`

**Interfaces:**
- Produces:
  ```go
  type Registry struct{ /* unexported */ }
  func NewRegistry() *Registry
  func (r *Registry) Set(sess *yamux.Session, stop func()) // replaces + closes prior, calls prior stop
  func (r *Registry) Current() *yamux.Session             // nil if none or closed
  func (r *Registry) Clear(sess *yamux.Session)           // removes sess if still current
  ```

- [ ] **Step 1: Write the failing test**

`bridge/internal/relay/registry_test.go`:
```go
package relay

import (
	"net"
	"testing"
	"time"

	"github.com/hashicorp/yamux"
)

func mkSession(t *testing.T) *yamux.Session {
	t.Helper()
	c1, c2 := net.Pipe()
	t.Cleanup(func() { c1.Close(); c2.Close() })
	go func() { _, _ = yamux.Client(c2, nil) }() // peer end keeps the pipe alive
	s, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestRegistryReplaceClosesOld(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	stopped := make(chan struct{})
	r.Set(s1, func() { close(stopped) })

	s2 := mkSession(t)
	r.Set(s2, nil)

	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("prior stop func not called on replace")
	}
	if !s1.IsClosed() {
		t.Fatal("prior session should be closed on replace")
	}
	if r.Current() != s2 {
		t.Fatal("Current should be s2")
	}
}

func TestRegistryClearOnlyIfCurrent(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	r.Set(s1, nil)
	other := mkSession(t)
	r.Clear(other) // not current → no-op
	if r.Current() != s1 {
		t.Fatal("Clear of a non-current session should be a no-op")
	}
	r.Clear(s1)
	if r.Current() != nil {
		t.Fatal("Current should be nil after clearing the active session")
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/ -run TestRegistry`
Expected: FAIL — `undefined: NewRegistry`.

- [ ] **Step 3: Implement**

`bridge/internal/relay/registry.go`:
```go
package relay

import (
	"sync"

	"github.com/hashicorp/yamux"
)

// Registry holds the single active agent tunnel session (v1: one Mac). A new
// session replaces and closes the previous one.
type Registry struct {
	mu   sync.Mutex
	sess *yamux.Session
	stop func() // cancels work bound to sess (e.g. the push monitor)
}

func NewRegistry() *Registry { return &Registry{} }

// Set installs sess as current, closing any prior session and calling its stop
// func. stop may be nil.
func (r *Registry) Set(sess *yamux.Session, stop func()) {
	r.mu.Lock()
	old, oldStop := r.sess, r.stop
	r.sess, r.stop = sess, stop
	r.mu.Unlock()

	if oldStop != nil {
		oldStop()
	}
	if old != nil {
		_ = old.Close()
	}
}

// Current returns the active session, or nil when none is connected or it has
// closed.
func (r *Registry) Current() *yamux.Session {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.sess != nil && r.sess.IsClosed() {
		return nil
	}
	return r.sess
}

// Clear removes sess if it is still the current session.
func (r *Registry) Clear(sess *yamux.Session) {
	r.mu.Lock()
	var stop func()
	if r.sess == sess {
		stop, r.stop, r.sess = r.stop, nil, nil
	}
	r.mu.Unlock()
	if stop != nil {
		stop()
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/ -run TestRegistry`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/relay/registry.go bridge/internal/relay/registry_test.go
git commit -m "feat(relay): single agent-session registry"
```

---

### Task 6: Reverse proxy over yamux + offline 503

**Files:**
- Create: `bridge/internal/relay/proxy.go`, `bridge/internal/relay/proxy_test.go`

**Interfaces:**
- Consumes: `Registry` (Task 5).
- Produces:
  ```go
  var ErrAgentOffline = errors.New("agent offline")
  func newProxy(reg *Registry, relayToken string) *httputil.ReverseProxy
  func writeJSONErr(w http.ResponseWriter, code int, msg string) // shared in package relay
  ```
  The proxy's transport opens a fresh yamux stream per dial; missing session → `ErrAgentOffline` → `503 {"error":"agent_offline"}`. The director injects `X-Relay-Token`.

- [ ] **Step 1: Write the failing test**

`bridge/internal/relay/proxy_test.go`:
```go
package relay

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/hashicorp/yamux"
)

func TestProxyOfflineReturns503(t *testing.T) {
	p := newProxy(NewRegistry(), "tok")
	rr := httptest.NewRecorder()
	p.ServeHTTP(rr, httptest.NewRequest("GET", "http://relay/sessions", nil))
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("want 503, got %d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "agent_offline") {
		t.Fatalf("body = %q", rr.Body.String())
	}
}

func TestProxyForwardsAndInjectsRelayToken(t *testing.T) {
	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}
	gotTok := make(chan string, 1)
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotTok <- r.Header.Get("X-Relay-Token")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("ok"))
		}))
	}()

	reg := NewRegistry()
	reg.Set(relaySess, nil)
	p := newProxy(reg, "relay-secret")

	rr := httptest.NewRecorder()
	p.ServeHTTP(rr, httptest.NewRequest("GET", "http://relay/sessions", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("want 200, got %d (%s)", rr.Code, rr.Body.String())
	}
	if tok := <-gotTok; tok != "relay-secret" {
		t.Fatalf("X-Relay-Token not injected: %q", tok)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/ -run TestProxy`
Expected: FAIL — `undefined: newProxy`.

- [ ] **Step 3: Implement**

`bridge/internal/relay/proxy.go`:
```go
package relay

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httputil"
)

// ErrAgentOffline is returned by the proxy transport when no agent session is
// registered.
var ErrAgentOffline = errors.New("agent offline")

func writeJSONErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(`{"error":"` + msg + `"}`))
}

// newProxy builds the reverse proxy that forwards an app request over a fresh
// yamux stream to the agent, injecting the relay token. Offline (no session) is
// surfaced as 503 agent_offline; other transport failures as 502.
func newProxy(reg *Registry, relayToken string) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		Director: func(req *http.Request) {
			req.URL.Scheme = "http"
			req.URL.Host = "agent" // ignored by the stream dialer below
			req.Header.Set("X-Relay-Token", relayToken)
		},
		Transport: &http.Transport{
			DialContext: func(_ context.Context, _, _ string) (net.Conn, error) {
				sess := reg.Current()
				if sess == nil {
					return nil, ErrAgentOffline
				}
				return sess.Open()
			},
		},
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			if errors.Is(err, ErrAgentOffline) {
				writeJSONErr(w, http.StatusServiceUnavailable, "agent_offline")
				return
			}
			writeJSONErr(w, http.StatusBadGateway, "agent_error")
		},
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/ -run TestProxy`
Expected: PASS. (If `TestProxyForwardsAndInjectsRelayToken` is flaky over `net.Pipe`, it is a test-harness issue, not a code bug; rerun. The end-to-end Task 7 test uses a real WSS listener.)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/relay/proxy.go bridge/internal/relay/proxy_test.go
git commit -m "feat(relay): reverse proxy over yamux with offline 503"
```

---

### Task 7: Relay mux — CN routing, tunnel accept, device register, end-to-end

**Files:**
- Create: `bridge/internal/relay/relay.go`, `bridge/internal/relay/relay_test.go`

**Interfaces:**
- Consumes: `Registry`, `newProxy`, `writeJSONErr` (Tasks 5–6), `tunnel.Accept` (Task 2), `auth.Require`/`auth.DeviceFromContext`/`auth.Store`, `server.TrustedHandler` (Task 3, used in the test).
- Produces:
  ```go
  type Relay struct{ /* unexported */ }
  func New(store *auth.Store, agentCN, relayToken string) *Relay
  func (r *Relay) SetSessionHook(f func(ctx context.Context, sess *yamux.Session))
  func (r *Relay) Handler() http.Handler
  func parseCN(dn string) string // exported-for-test? keep unexported, test in-package
  ```
  Routes: `/agent/tunnel` (CN must equal agentCN) accepts the tunnel and registers the session; `POST /devices/register` stores the FCM token; all else → device bearer → proxy. The agent CN is rejected on non-tunnel routes.

- [ ] **Step 1: Write the failing tests**

`bridge/internal/relay/relay_test.go`:
```go
package relay

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/testutil"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

func TestParseCN(t *testing.T) {
	cases := map[string]string{
		"CN=mac-agent":                "mac-agent",
		"CN=mac-agent,O=home":         "mac-agent",
		"/CN=mac-agent/O=home":        "mac-agent",
		"O=home,CN=mac-agent":         "mac-agent",
		"O=home":                      "",
		"":                            "",
	}
	for dn, want := range cases {
		if got := parseCN(dn); got != want {
			t.Errorf("parseCN(%q)=%q want %q", dn, got, want)
		}
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition not met in time")
}

func TestRelayEndToEndSessions(t *testing.T) {
	// Agent side: trusted handler backed by a fake cmux returning one workspace.
	const ws = `{"workspaces":[{"id":"E43BBF04","current_directory":"/x","preview":"u@h:~/x"}]}`
	bin := testutil.WriteFakeCmux(t, "#!/bin/sh\ncat <<'JSON'\n"+ws+"\nJSON\n")
	agentSrv := server.New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	const relayTok = "relay-secret"
	trusted := agentSrv.TrustedHandler(relayTok)

	// Relay with its own device store.
	relayStore, err := auth.Open(t.TempDir() + "/r.json")
	if err != nil {
		t.Fatal(err)
	}
	devTok, _ := relayStore.Issue("phone")
	rl := New(relayStore, "mac-agent", relayTok)
	relayHTTP := httptest.NewServer(rl.Handler())
	defer relayHTTP.Close()

	// Agent dials /agent/tunnel; we set X-Client-Cert-CN as nginx would.
	u := "ws" + strings.TrimPrefix(relayHTTP.URL, "http") + "/agent/tunnel"
	sess, err := tunnel.Dial(context.Background(), u, nil, http.Header{"X-Client-Cert-Cn": {"CN=mac-agent"}})
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()
	go func() { _ = http.Serve(sess, trusted) }()

	waitFor(t, func() bool { return rl.Current() != nil })

	// Device GET /sessions through the relay.
	req, _ := http.NewRequest("GET", relayHTTP.URL+"/sessions", nil)
	req.Header.Set("Authorization", "Bearer "+devTok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200 through relay, got %d", resp.StatusCode)
	}
	var body struct {
		Sessions []map[string]any `json:"sessions"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Sessions) != 1 {
		t.Fatalf("want 1 session, got %d", len(body.Sessions))
	}

	// A device with no bearer is rejected before reaching the agent.
	bad, _ := http.Get(relayHTTP.URL + "/sessions")
	if bad.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 without bearer, got %d", bad.StatusCode)
	}
	bad.Body.Close()
}

func TestRelayTunnelRejectsWrongCN(t *testing.T) {
	rl := New(nil, "mac-agent", "tok")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()
	// A non-agent CN on /agent/tunnel → 403 (no WebSocket upgrade).
	req, _ := http.NewRequest("GET", srv.URL+"/agent/tunnel", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=phone")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for wrong CN, got %d", resp.StatusCode)
	}
}
```

> Header canonicalization note: Go's `http.Header` canonicalizes `X-Relay-Token` and `X-Client-Cert-CN` to `X-Client-Cert-Cn`. The relay reads via `req.Header.Get`, which canonicalizes the key, so either spelling works; the test uses the canonical form to be explicit.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/ -run TestRelay`
Expected: FAIL — `undefined: New`, `rl.Current undefined`, `undefined: parseCN`.

- [ ] **Step 3: Implement**

`bridge/internal/relay/relay.go`:
```go
package relay

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httputil"
	"strings"

	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

// Relay is the home-server rendezvous: it accepts the Mac agent's tunnel and
// reverse-proxies authenticated app requests over it.
type Relay struct {
	store      *auth.Store
	reg        *Registry
	agentCN    string
	relayToken string
	proxy      *httputil.ReverseProxy
	onSession  func(context.Context, *yamux.Session)
}

// New builds a Relay. store may be nil only in tests that never hit auth routes.
func New(store *auth.Store, agentCN, relayToken string) *Relay {
	reg := NewRegistry()
	return &Relay{
		store:      store,
		reg:        reg,
		agentCN:    agentCN,
		relayToken: relayToken,
		proxy:      newProxy(reg, relayToken),
	}
}

// SetSessionHook registers a callback invoked (in its own goroutine) for each
// accepted agent session; its context is cancelled when the session ends.
func (r *Relay) SetSessionHook(f func(context.Context, *yamux.Session)) { r.onSession = f }

// Current exposes the active agent session (nil if offline) — used in tests.
func (r *Relay) Current() *yamux.Session { return r.reg.Current() }

// parseCN extracts the CN attribute from an RFC2253 ("CN=foo,O=bar") or legacy
// slash ("/CN=foo/O=bar") distinguished name.
func parseCN(dn string) string {
	for _, part := range strings.FieldsFunc(dn, func(r rune) bool { return r == ',' || r == '/' }) {
		part = strings.TrimSpace(part)
		if strings.HasPrefix(part, "CN=") {
			return strings.TrimPrefix(part, "CN=")
		}
	}
	return ""
}

func (r *Relay) clientCN(req *http.Request) string {
	return parseCN(req.Header.Get("X-Client-Cert-CN"))
}

func (r *Relay) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/agent/tunnel", r.handleTunnel)
	mux.Handle("POST /devices/register", r.notAgent(auth.Require(r.store, http.HandlerFunc(r.handleRegister))))
	mux.Handle("/", r.notAgent(auth.Require(r.store, r.proxy)))
	return mux
}

// notAgent rejects requests bearing the agent CN on non-tunnel routes.
func (r *Relay) notAgent(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if r.agentCN != "" && r.clientCN(req) == r.agentCN {
			writeJSONErr(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, req)
	})
}

func (r *Relay) handleTunnel(w http.ResponseWriter, req *http.Request) {
	if r.agentCN == "" || r.clientCN(req) != r.agentCN {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	sess, err := tunnel.Accept(w, req)
	if err != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	r.reg.Set(sess, cancel)
	if r.onSession != nil {
		go r.onSession(ctx, sess)
	}
	<-sess.CloseChan() // block until the tunnel dies
	r.reg.Clear(sess)
	cancel()
}

type registerReq struct {
	FCMToken string `json:"fcm_token"`
}

func (r *Relay) handleRegister(w http.ResponseWriter, req *http.Request) {
	dev, ok := auth.DeviceFromContext(req.Context())
	if !ok {
		writeJSONErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var rq registerReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.FCMToken == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing fcm_token")
		return
	}
	r.store.SetFCMToken(dev.Token, rq.FCMToken)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"ok":true}`))
}
```

> `auth.Require(nil, …)` is only constructed in `TestRelayTunnelRejectsWrongCN`, whose request is rejected by `notAgent`/`handleTunnel` before any `store.Verify` call, so the nil store is never dereferenced.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/`
Expected: PASS (all relay tests, including the end-to-end tunnel round-trip).

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/relay/relay.go bridge/internal/relay/relay_test.go
git commit -m "feat(relay): mux with CN routing, tunnel accept, device register"
```

---

### Task 8: Relay push monitor (relay's own /events subscription)

**Files:**
- Create: `bridge/internal/relay/pushmon.go`, `bridge/internal/relay/pushmon_test.go`

**Interfaces:**
- Consumes: `auth.Store`, `server.EventFrame`, a `*yamux.Session`.
- Produces:
  ```go
  type Pusher interface {
      Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
  }
  func MonitorAgent(ctx context.Context, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher)
  ```
  `MonitorAgent` dials the agent's `/events` over a yamux stream and, on each `needs_attention` frame, sends an `type=attention` FCM message to every registered device token. No-op when `push == nil`.

- [ ] **Step 1: Write the failing test**

`bridge/internal/relay/pushmon_test.go`:
```go
package relay

import (
	"context"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/server"
)

type fakePusher struct {
	mu    sync.Mutex
	calls []map[string]string
}

func (p *fakePusher) Send(_ context.Context, _, _, _ string, data map[string]string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls = append(p.calls, data)
	return nil
}

func TestMonitorAgentPushesAttention(t *testing.T) {
	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}

	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/events" || r.Header.Get("X-Relay-Token") != "tok" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			ws, err := up.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			_ = ws.WriteJSON(server.EventFrame{
				Type: "feed", NeedsAttention: true, FeedID: "F1",
				Kind: "permissionRequest", Title: "Run rm -rf?",
			})
			time.Sleep(500 * time.Millisecond)
		}))
	}()

	store, err := auth.Open(t.TempDir() + "/d.json")
	if err != nil {
		t.Fatal(err)
	}
	tok, _ := store.Issue("phone")
	store.SetFCMToken(tok, "fcm-123")

	fp := &fakePusher{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go MonitorAgent(ctx, relaySess, "tok", store, fp)

	waitFor(t, func() bool {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		return len(fp.calls) > 0
	})
	fp.mu.Lock()
	defer fp.mu.Unlock()
	got := fp.calls[0]
	if got["type"] != "attention" || got["feed_id"] != "F1" || got["kind"] != "permissionRequest" {
		t.Fatalf("unexpected push data: %+v", got)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/ -run TestMonitorAgent`
Expected: FAIL — `undefined: MonitorAgent`.

- [ ] **Step 3: Implement**

`bridge/internal/relay/pushmon.go`:
```go
package relay

import (
	"context"
	"net"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/server"
)

// Pusher delivers an attention push to a single device token. push.Sender
// satisfies it.
type Pusher interface {
	Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
}

// MonitorAgent subscribes to the agent's /events over the tunnel and fans
// blocking prompts out to FCM. It returns when ctx is cancelled or the session
// dies. relayToken authenticates to the agent's trusted handler.
func MonitorAgent(ctx context.Context, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) {
	if push == nil {
		return
	}
	for ctx.Err() == nil {
		if err := subscribeOnce(ctx, sess, relayToken, store, push); err != nil && sess.IsClosed() {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

func subscribeOnce(_ context.Context, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) error {
	d := websocket.Dialer{
		NetDial: func(_, _ string) (net.Conn, error) { return sess.Open() },
	}
	ws, _, err := d.Dial("ws://agent/events", http.Header{"X-Relay-Token": {relayToken}})
	if err != nil {
		return err
	}
	defer ws.Close()
	for {
		var f server.EventFrame
		if err := ws.ReadJSON(&f); err != nil {
			return err
		}
		if f.NeedsAttention {
			fanout(store, push, f)
		}
	}
}

func fanout(store *auth.Store, push Pusher, f server.EventFrame) {
	tokens := store.FCMTokens()
	if len(tokens) == 0 {
		return
	}
	body := f.Title
	if body == "" {
		body = f.Kind
	}
	data := map[string]string{
		"type":         "attention",
		"feed_id":      f.FeedID,
		"workspace_id": f.WorkspaceID,
		"kind":         f.Kind,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, tok := range tokens {
		_ = push.Send(ctx, tok, "Agent needs your attention", body, data)
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./internal/relay/`
Expected: PASS (all relay tests).

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/relay/pushmon.go bridge/internal/relay/pushmon_test.go
git commit -m "feat(relay): FCM push monitor over the agent events stream"
```

---

### Task 9: `cmux-relay` binary (serve / pair / devices)

**Files:**
- Create: `bridge/cmd/cmux-relay/main.go`, `bridge/cmd/cmux-relay/serve.go`, `bridge/cmd/cmux-relay/commands.go`

**Interfaces:**
- Consumes: `config.Load`, `auth.Open`/`auth.Store`, `relay.New`/`relay.Handler`/`relay.SetSessionHook`/`relay.MonitorAgent`, `push.Sender`.
- Produces: a buildable `cmux-relay` with `serve`, `pair`, `devices`, `version`. `serve` wires the session hook to start `MonitorAgent` per session.

- [ ] **Step 1: Implement `main.go`**

`bridge/cmd/cmux-relay/main.go`:
```go
package main

import (
	"fmt"
	"os"

	"github.com/sodre90/cmux-bridge/internal/version"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "serve":
		os.Exit(runServe(os.Args[2:]))
	case "pair":
		os.Exit(runPair(os.Args[2:]))
	case "devices":
		os.Exit(runDevices(os.Args[2:]))
	case "version", "--version", "-v":
		fmt.Println("cmux-relay", version.String())
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-relay <serve|pair|devices|version> [flags]")
}
```

- [ ] **Step 2: Implement `serve.go`**

`bridge/cmd/cmux-relay/serve.go`:
```go
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/hashicorp/yamux"
	"golang.org/x/oauth2/google"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/relay"
)

const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

func defaultConfigPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "config.toml"
	}
	return filepath.Join(home, ".config", "cmux-relay", "config.toml")
}

func loadStore(cfgPath string) (config.Config, *auth.Store, error) {
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return cfg, nil, err
	}
	store, err := auth.Open(cfg.TokenStore)
	if err != nil {
		return cfg, nil, err
	}
	return cfg, store, nil
}

func runServe(args []string) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, store, err := loadStore(*cfgPath)
	if err != nil {
		log.Printf("serve: %v", err)
		return 1
	}

	rl := relay.New(store, cfg.AgentCN, cfg.RelayToken)

	var pusher relay.Pusher
	if cfg.FCMCredentials != "" && cfg.FCMProjectID != "" {
		if p, err := newPusher(cfg); err != nil {
			log.Printf("serve: push disabled: %v", err)
		} else {
			pusher = p
			log.Printf("serve: FCM push enabled for project %s", cfg.FCMProjectID)
		}
	}
	if pusher != nil {
		rl.SetSessionHook(func(ctx context.Context, sess *yamux.Session) {
			relay.MonitorAgent(ctx, sess, cfg.RelayToken, store, pusher)
		})
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	httpSrv := &http.Server{Addr: cfg.Listen, Handler: rl.Handler()}
	go func() {
		<-ctx.Done()
		shutCtx, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = httpSrv.Shutdown(shutCtx)
	}()

	log.Printf("cmux-relay listening on %s (agent CN %q)", cfg.Listen, cfg.AgentCN)
	if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Printf("serve: %v", err)
		return 1
	}
	return 0
}

func newPusher(cfg config.Config) (*push.Sender, error) {
	key, err := os.ReadFile(cfg.FCMCredentials)
	if err != nil {
		return nil, fmt.Errorf("read fcm credentials: %w", err)
	}
	creds, err := google.CredentialsFromJSON(context.Background(), key, fcmScope)
	if err != nil {
		return nil, fmt.Errorf("parse fcm credentials: %w", err)
	}
	return &push.Sender{
		ProjectID: cfg.FCMProjectID,
		Token: func(context.Context) (string, error) {
			tok, err := creds.TokenSource.Token()
			if err != nil {
				return "", err
			}
			return tok.AccessToken, nil
		},
	}, nil
}
```

- [ ] **Step 3: Implement `commands.go`** (pair/devices moved from `cmd/cmux-bridge/commands.go`)

`bridge/cmd/cmux-relay/commands.go`:
```go
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"time"
)

func runPair(args []string) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	name := fs.String("name", "phone", "a label for this device")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := loadStore(*cfgPath)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	tok, err := store.Issue(*name)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	fmt.Printf("\nDevice token for %q (paste into the app once):\n\n    %s\n\n", *name, tok)
	fmt.Println("Keep it secret. Revoke later with: cmux-relay devices revoke <token>")
	return 0
}

func runDevices(args []string) int {
	fs := flag.NewFlagSet("devices", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := loadStore(*cfgPath)
	if err != nil {
		log.Printf("devices: %v", err)
		return 1
	}
	rest := fs.Args()
	switch {
	case len(rest) == 0 || rest[0] == "list":
		devs := store.List()
		if len(devs) == 0 {
			fmt.Println("no paired devices")
			return 0
		}
		for _, d := range devs {
			fmt.Printf("%-16s  token=%s  fcm=%v  created=%s\n",
				d.Name, d.Token, d.FCM != "", d.Created.Format(time.RFC3339))
		}
		return 0
	case rest[0] == "revoke":
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "usage: cmux-relay devices revoke <token>")
			return 2
		}
		if store.Revoke(rest[1]) {
			fmt.Println("revoked")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such token")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-relay devices [list|revoke <token>]")
		return 2
	}
}
```

- [ ] **Step 4: Build and test the whole module**

Run: `go -C /Users/perdos/prj/cmux-app/bridge build ./... && go -C /Users/perdos/prj/cmux-app/bridge test ./...`
Expected: build succeeds; all packages pass.

- [ ] **Step 5: Commit**

```bash
git add bridge/cmd/cmux-relay
git commit -m "feat(relay): cmux-relay binary (serve/pair/devices)"
```

---

### Task 10: Agent mode in `cmux-bridge`

**Files:**
- Create: `bridge/cmd/cmux-bridge/agent.go`, `bridge/cmd/cmux-bridge/agent_test.go`
- Modify: `bridge/cmd/cmux-bridge/main.go` (add `agent` subcommand), `bridge/cmd/cmux-bridge/commands.go` (remove `runPair`/`runDevices`, moved to the relay)

**Interfaces:**
- Consumes: `config.LoadAgent`, `tunnel.Dial`, `server.New`/`server.TrustedHandler`/`server.RunEvents`, `cmux.Client`.
- Produces:
  ```go
  func runAgent(args []string) int
  func loadTLS(certPath, keyPath, caPath string) (*tls.Config, error)
  func dialAndServe(ctx context.Context, relayURL string, tlsCfg *tls.Config, handler http.Handler) error
  func nextBackoff(d time.Duration) time.Duration // doubles, capped at 30s
  ```

- [ ] **Step 1: Write the failing test**

`bridge/cmd/cmux-bridge/agent_test.go`:
```go
package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNextBackoffCaps(t *testing.T) {
	d := time.Second
	for i := 0; i < 10; i++ {
		d = nextBackoff(d)
	}
	if d != 30*time.Second {
		t.Fatalf("backoff should cap at 30s, got %v", d)
	}
	if got := nextBackoff(time.Second); got != 2*time.Second {
		t.Fatalf("nextBackoff(1s)=%v want 2s", got)
	}
}

func writeSelfSigned(t *testing.T) (certPath, keyPath string) {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "mac-agent"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	certPath = filepath.Join(dir, "c.pem")
	keyPath = filepath.Join(dir, "k.pem")
	cf, _ := os.Create(certPath)
	_ = pem.Encode(cf, &pem.Block{Type: "CERTIFICATE", Bytes: der})
	cf.Close()
	kf, _ := os.Create(keyPath)
	_ = pem.Encode(kf, &pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(priv)})
	kf.Close()
	return certPath, keyPath
}

func TestLoadTLS(t *testing.T) {
	cert, key := writeSelfSigned(t)
	cfg, err := loadTLS(cert, key, cert) // self-signed: cert doubles as its CA
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Certificates) != 1 {
		t.Fatalf("want 1 client cert, got %d", len(cfg.Certificates))
	}
	if cfg.RootCAs == nil {
		t.Fatal("want RootCAs set from ca_cert")
	}
}

func TestLoadTLSMissingFileErrors(t *testing.T) {
	if _, err := loadTLS("/no/cert", "/no/key", "/no/ca"); err == nil {
		t.Fatal("want error for missing cert files")
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C /Users/perdos/prj/cmux-app/bridge test ./cmd/cmux-bridge/ -run 'TestNextBackoff|TestLoadTLS'`
Expected: FAIL — `undefined: nextBackoff` / `undefined: loadTLS`.

- [ ] **Step 3: Implement `agent.go`**

`bridge/cmd/cmux-bridge/agent.go`:
```go
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

func defaultAgentConfigPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "agent.toml"
	}
	return filepath.Join(home, ".config", "cmux-bridge", "agent.toml")
}

func loadTLS(certPath, keyPath, caPath string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, err
	}
	caPEM, err := os.ReadFile(caPath)
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, err
	}
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		RootCAs:      pool,
		MinVersion:   tls.VersionTLS12,
	}, nil
}

func nextBackoff(d time.Duration) time.Duration {
	d *= 2
	if d > 30*time.Second {
		return 30 * time.Second
	}
	return d
}

// dialAndServe runs one tunnel lifecycle: dial the relay, then serve the handler
// over the yamux session until it dies. Returns when the session ends.
func dialAndServe(ctx context.Context, relayURL string, tlsCfg *tls.Config, handler http.Handler) error {
	sess, err := tunnel.Dial(ctx, relayURL, tlsCfg, nil)
	if err != nil {
		return err
	}
	defer sess.Close()
	return http.Serve(sess, handler)
}

func runAgent(args []string) int {
	fs := flag.NewFlagSet("agent", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultAgentConfigPath(), "path to agent.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, err := config.LoadAgent(*cfgPath)
	if err != nil {
		log.Printf("agent: %v", err)
		return 1
	}
	if cfg.RelayURL == "" {
		log.Printf("agent: relay_url is required")
		return 1
	}
	tlsCfg, err := loadTLS(cfg.ClientCert, cfg.ClientKey, cfg.CACert)
	if err != nil {
		log.Printf("agent: tls: %v", err)
		return 1
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	srv := server.New(config.Config{}, &cmux.Client{Bin: cfg.CmuxBin}, nil)
	go srv.RunEvents(ctx)
	handler := srv.TrustedHandler(cfg.RelayToken)

	backoff := time.Second
	for ctx.Err() == nil {
		log.Printf("agent: dialing relay %s", cfg.RelayURL)
		if err := dialAndServe(ctx, cfg.RelayURL, tlsCfg, handler); err != nil {
			log.Printf("agent: tunnel ended: %v", err)
		}
		if ctx.Err() != nil {
			break
		}
		time.Sleep(backoff)
		backoff = nextBackoff(backoff)
	}
	return 0
}
```

- [ ] **Step 4: Wire the subcommand and remove moved commands**

In `bridge/cmd/cmux-bridge/main.go`, add a case to the switch:
```go
	case "agent":
		os.Exit(runAgent(os.Args[2:]))
```
and update the usage line to:
```go
	fmt.Fprintln(os.Stderr, "usage: cmux-bridge <agent|serve|version> [flags]")
```
Remove the `pair` and `devices` cases from the switch (they now live in `cmux-relay`).

In `bridge/cmd/cmux-bridge/commands.go`, delete `runPair` and `runDevices` and any imports they alone used (`time` may still be used by `runServe`; remove only if `go build` flags it as unused).

- [ ] **Step 5: Run tests + build**

Run: `go -C /Users/perdos/prj/cmux-app/bridge build ./... && go -C /Users/perdos/prj/cmux-app/bridge test ./...`
Expected: build succeeds; all packages pass.

- [ ] **Step 6: Commit**

```bash
git add bridge/cmd/cmux-bridge
git commit -m "feat(relay): agent mode dials out and serves cmux over the tunnel"
```

---

### Task 11: Deploy artifacts + README

**Files:**
- Create: `bridge/deploy/cmux-relay.service`, `bridge/deploy/nginx-cmux-relay.conf`, `bridge/deploy/relay.example.toml`, `bridge/deploy/agent.example.toml`
- Modify: `bridge/README.md`

- [ ] **Step 1: systemd unit** — `bridge/deploy/cmux-relay.service`:
```ini
[Unit]
Description=cmux relay (rendezvous for the Mac agent and app devices)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/cmux-relay serve --config /etc/cmux-relay/config.toml
Restart=always
RestartSec=2
# Binds loopback only; nginx is the public edge.
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/cmux-relay
StateDirectory=cmux-relay

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 2: nginx vhost** — `bridge/deploy/nginx-cmux-relay.conf`:
```nginx
# Home-server vhost for cmux-relay. Terminates mutual TLS for BOTH the Mac agent
# and app devices on your public DNS name, and proxies to the loopback relay.
#
# This map must live in the http{} context:
#   map $http_upgrade $connection_upgrade { default upgrade; '' close; }

server {
    listen 443 ssl;
    http2 on;
    server_name cmux.example.com;            # your home-server DNS name

    ssl_certificate     /etc/nginx/certs/cmux/server.crt;
    ssl_certificate_key /etc/nginx/certs/cmux/server.key;

    # Mutual TLS: agent + device certs are signed by this CA.
    ssl_client_certificate /etc/nginx/certs/cmux/client-ca.crt;
    ssl_verify_client      on;

    client_max_body_size 256k;

    # The relay distinguishes the Mac agent from app devices by client-cert CN.
    # Set (never trust an inbound) X-Client-Cert-CN from the verified cert DN.
    location / {
        proxy_pass http://127.0.0.1:8765;

        proxy_http_version 1.1;
        proxy_set_header Upgrade          $http_upgrade;
        proxy_set_header Connection       $connection_upgrade;
        proxy_set_header Host             $host;
        proxy_set_header X-Forwarded-For  $remote_addr;
        proxy_set_header X-Client-Cert-CN $ssl_client_s_dn;   # relay parses CN=…

        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}
```

- [ ] **Step 3: Example configs**

`bridge/deploy/relay.example.toml`:
```toml
# cmux-relay config (home server). Copy to /etc/cmux-relay/config.toml.
listen      = "127.0.0.1:8765"                 # loopback; nginx is the edge
token_store = "/var/lib/cmux-relay/devices.json"
agent_cn    = "mac-agent"                       # client-cert CN trusted as the Mac
relay_token = "CHANGE_ME_long_random_secret"    # shared with the Mac agent

# Push (optional): leave both empty to disable.
# fcm_project_id  = "your-firebase-project-id"
# fcm_credentials = "/etc/cmux-relay/fcm-service-account.json"
```

`bridge/deploy/agent.example.toml`:
```toml
# cmux-bridge agent config (Mac). Copy to ~/.config/cmux-bridge/agent.toml.
relay_url   = "wss://cmux.example.com/agent/tunnel"
client_cert = "~/.config/cmux-bridge/agent.crt"   # CN=mac-agent, signed by client CA
client_key  = "~/.config/cmux-bridge/agent.key"
ca_cert     = "~/.config/cmux-bridge/server-ca.crt"  # CA that signed the nginx server cert
relay_token = "CHANGE_ME_long_random_secret"      # must match the relay
cmux_bin    = "cmux"
```

- [ ] **Step 4: Update `bridge/README.md`**

Replace the "Architecture", "Run as a LaunchAgent", "Pair a device", and "Edge: nginx mutual TLS" sections to describe the two binaries. Add:
- The v2 diagram (app + Mac agent → nginx mTLS on the home domain → relay → tunnel → agent → cmux).
- **Relay (home server):** install `cmux-relay`, `/etc/cmux-relay/config.toml` from `relay.example.toml`, the systemd unit, and `nginx-cmux-relay.conf`. Pair devices with `cmux-relay pair --name phone`.
- **Agent (Mac):** run `cmux-bridge agent`; the launchd plist's `ProgramArguments` become `["…/cmux-bridge","agent"]`; `~/.config/cmux-bridge/agent.toml` from `agent.example.toml`.
- **Agent client cert** (new), signed by the same client CA as device certs:
  ```bash
  openssl req -newkey rsa:2048 -nodes -keyout agent.key -out agent.csr -subj "/CN=mac-agent"
  openssl x509 -req -in agent.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out agent.crt -days 825 -sha256
  ```
- A note that the Android app's base URL now points at the home domain, and that a `503 agent_offline` means the Mac agent is not connected.

- [ ] **Step 5: Build sanity + commit**

Run: `go -C /Users/perdos/prj/cmux-app/bridge build ./... && go -C /Users/perdos/prj/cmux-app/bridge vet ./...`
Expected: clean.

```bash
git add bridge/deploy bridge/README.md
git commit -m "docs(relay): deploy artifacts, agent cert, README for v2 topology"
```

---

## Self-review

**Spec coverage:**
- App contract preserved at relay edge → Tasks 6–7 (proxy + mux). ✅
- One edge / CN routing → Task 7 (`parseCN`, `notAgent`, `handleTunnel`). ✅
- Single Mac (replace-on-new) → Task 5 (`Registry`). ✅
- yamux-over-WSS tunnel → Tasks 1–2. ✅
- Trusted mode + `X-Relay-Token` → Task 3 (server) + Task 6 (injection) + Task 7 (e2e). ✅
- Token store + pairing moved to relay → Task 9. ✅
- FCM push moved to relay (own /events monitor) → Task 8 + Task 9 wiring. ✅
- Config split → Task 4. ✅
- Agent dial-out + backoff → Task 10. ✅
- Mac-offline = 503 → Task 6 (`ErrAgentOffline`) + Task 7 (e2e bearer/offline). ✅
- Deploy (systemd, nginx, agent cert) → Task 11. ✅
- No real network/cmux/FCM in tests → all tests use httptest/net.Pipe, `testutil.WriteFakeCmux`, `fakePusher`. ✅

**Placeholder scan:** none — every code step is complete and compilable.

**Type consistency:** `Registry.Set/Current/Clear`, `newProxy(reg, relayToken)`, `Relay.New(store, agentCN, relayToken)`/`Handler`/`Current`/`SetSessionHook`, `MonitorAgent(ctx, sess, relayToken, store, push)`, `server.TrustedHandler(relayToken)`, `RequireRelayToken(token, next)`, `config.LoadAgent`/`AgentConfig`, `tunnel.Dial/Accept`, agent `loadTLS/dialAndServe/nextBackoff` — names and signatures match across the tasks that consume them.

## Execution

Inline execution with `go -C bridge test ./...` + `go -C bridge build ./...` as the gate, a commit per task, authored as `sodre90` with no AI trailer.
