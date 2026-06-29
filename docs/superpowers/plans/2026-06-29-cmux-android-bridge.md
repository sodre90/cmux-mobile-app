# cmux Bridge (Mac side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `cmux-bridge`, a small Go daemon on the Mac that exposes cmux's
sessions, terminals, and agent feed over a simple authenticated HTTP/WebSocket
API, so a remote Android client (behind the user's mTLS nginx) can list
sessions, drive a terminal, and answer agent prompts.

**Architecture:** The bridge never speaks cmux's wire protocol. It shells out to
the documented `cmux` CLI — `cmux rpc <method> <json>` for control calls and one
long-lived `cmux events --reconnect` process for the event stream — and
normalizes the results into a stable JSON contract. It binds to loopback/LAN
only; the public edge (mTLS termination) is the user's existing nginx on Home
Assistant. A per-device bearer token gates every endpoint in addition to the
mTLS gate at the edge.

**Tech Stack:** Go 1.26 (stdlib `net/http` + `gorilla/websocket`), TOML config,
the `cmux` CLI as the only cmux dependency, FCM HTTP v1 for push.

## Global Constraints

- **Licensing:** the bridge is an independent work that talks to cmux over its
  documented IPC/CLI. **Never copy cmux source.** Reference its CLI/protocol
  contract as documentation only. Bridge code is written fresh.
- **cmux access:** the bridge runs as the logged-in user and relies on the
  `cmux` CLI's local auto-resolution of socket path + password. **The bridge
  stores no cmux socket password.**
- **No destructive cmux calls, ever — in code or tests.** The bridge only calls
  read methods (`mobile.workspace.list`, `mobile.terminal.replay`), terminal
  input (`mobile.terminal.input`/`paste`/`viewport`), feed replies, and
  `cmux events`. It MUST NOT call workspace/terminal close, restore, or create.
  Tests use a fake `cmux` binary and never touch the real socket.
- **Single user, own devices.** No multi-tenant logic.
- **One static binary** named `cmux-bridge` with subcommands `serve` (default),
  `pair`, `devices`.
- **Auth is layered:** mTLS at nginx (edge) + per-device bearer token at the
  bridge. The bridge trusts network position for nothing.
- **Go module path:** `github.com/sodre90/cmux-bridge`.
- **Testing:** `go test ./...` must pass with no network and no real cmux. Every
  task ends green and committed. Commit author is the human developer; never add
  AI co-author trailers.

---

## Calibration note (planner == executor)

This plan is executed inline in the same session that wrote it (the user asked
for autonomous implementation). Tasks therefore give **exact file paths, exact
public signatures, and the concrete test assertions/fixtures** that lock each
task's contract, plus full code for the decision-bearing seams (cmux client,
auth, FCM). Trivial glue (struct field plumbing, router wiring) is specified by
signature + test rather than transcribed line-for-line. If this plan is instead
handed to fresh subagents, expand each "implement" step to full code from the
stated signatures before dispatching.

## File structure

```
bridge/
  go.mod                                   # module github.com/sodre90/cmux-bridge, go 1.26
  go.sum
  config.example.toml                      # documented sample config
  README.md                                # build + install + nginx + pairing walkthrough
  cmd/cmux-bridge/main.go                  # CLI entry; dispatch serve|pair|devices
  internal/cmux/client.go                  # Client: Rpc(), Events() — the only cmux seam
  internal/cmux/client_test.go             # uses a fake cmux script via PATH/Bin
  internal/config/config.go                # Config struct + Load()
  internal/config/config_test.go
  internal/auth/store.go                   # device-token + pairing-code store (JSON file)
  internal/auth/store_test.go
  internal/auth/middleware.go              # bearer-token http middleware
  internal/auth/middleware_test.go
  internal/server/server.go                # New(), routes, http.Handler
  internal/server/sessions.go              # GET /sessions
  internal/server/sessions_test.go
  internal/server/events.go                # WS /events + event classifier
  internal/server/events_test.go
  internal/server/terminal.go              # WS /terminal/{id}
  internal/server/terminal_test.go
  internal/server/feed.go                  # POST /feed/{id}/reply
  internal/server/feed_test.go
  internal/server/devices.go               # POST /pair, POST /devices/register
  internal/server/devices_test.go
  internal/push/fcm.go                     # FCM HTTP v1 sender
  internal/push/fcm_test.go
  internal/testutil/fakecmux.go            # builds a fake `cmux` for tests
  deploy/com.sodre90.cmux-bridge.plist     # launchd LaunchAgent template
  deploy/nginx-cmux-bridge.conf            # sample mTLS vhost reverse-proxy
```

Stable JSON contract (the app depends only on this, never on cmux internals):

- `Session`: `{ "id": string, "cwd": string, "title": string, "kind": "agent"|"terminal", "needs_attention": bool }`
- `EventFrame` (down on `WS /events`): `{ "type": "feed"|"notification"|"heartbeat", "needs_attention": bool, "feed_id": string, "workspace_id": string, "title": string, "kind": string, "raw": object }`
- `TerminalDown`: `{ "type": "replay"|"output"|"viewport", "grid": object, "columns": int, "rows": int }`
- `TerminalUp`: `{ "type": "input"|"paste"|"resize", "text": string, "columns": int, "rows": int }`
- `FeedReply` (POST body): `{ "kind": "permission"|"question"|"exitPlan", "request_id": string, "decision": string, "text": string }`

---

### Task 0: Project scaffold + green test loop

**Files:**
- Create: `bridge/go.mod`
- Create: `bridge/cmd/cmux-bridge/main.go`
- Create: `bridge/internal/version/version.go`
- Test: `bridge/internal/version/version_test.go`

**Interfaces:**
- Produces: `version.String() string` (returns a non-empty version string).
- Produces: binary that prints usage and dispatches `serve|pair|devices`.

- [ ] **Step 1: Write the failing test**

```go
// bridge/internal/version/version_test.go
package version

import "testing"

func TestStringNotEmpty(t *testing.T) {
	if String() == "" {
		t.Fatal("version.String() must not be empty")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bridge && go test ./internal/version/`
Expected: FAIL — `undefined: String` (package won't compile).

- [ ] **Step 3: Minimal implementation**

```go
// bridge/internal/version/version.go
package version

// String returns the bridge version. Overridden at build time via -ldflags.
var v = "0.1.0-dev"

func String() string { return v }
```

```go
// bridge/cmd/cmux-bridge/main.go
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
		fmt.Println("cmux-bridge", version.String())
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-bridge <serve|pair|devices|version>")
}

// Stubs replaced in later tasks.
func runServe(args []string) int   { fmt.Fprintln(os.Stderr, "serve: not implemented"); return 1 }
func runPair(args []string) int    { fmt.Fprintln(os.Stderr, "pair: not implemented"); return 1 }
func runDevices(args []string) int { fmt.Fprintln(os.Stderr, "devices: not implemented"); return 1 }
```

`bridge/go.mod`:

```
module github.com/sodre90/cmux-bridge

go 1.26
```

- [ ] **Step 4: Run tests + build**

Run: `cd bridge && go build ./... && go test ./...`
Expected: build OK, tests PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge/go.mod bridge/cmd bridge/internal/version
git commit -m "feat(bridge): scaffold cmux-bridge CLI with version command"
```

---

### Task 1: cmux CLI client (the testable seam)

This is the only place that touches cmux. Everything else is tested against a
fake `cmux` script, so the whole suite runs with no real cmux.

**Files:**
- Create: `bridge/internal/cmux/client.go`
- Create: `bridge/internal/testutil/fakecmux.go`
- Test: `bridge/internal/cmux/client_test.go`

**Interfaces:**
- Produces:
  ```go
  type Client struct { Bin string } // Bin defaults to "cmux" when empty
  func (c *Client) Rpc(ctx context.Context, method string, params any) (json.RawMessage, error)
  func (c *Client) Events(ctx context.Context, args ...string) (*exec.Cmd, io.ReadCloser, error)
  ```
  `Rpc` runs `<Bin> rpc <method> [json(params)]` with `CMUX_QUIET=1`, returns
  stdout as raw JSON; on non-zero exit returns an error that includes stderr.
  `Events` starts `<Bin> events <args...>` and returns the started cmd plus a
  stdout pipe for NDJSON; caller cancels ctx / closes pipe to stop it.
- Produces (testutil): `func WriteFakeCmux(t *testing.T, script string) (bin string)` — writes an executable shell script to a temp dir and returns its path.

- [ ] **Step 1: Write the failing tests**

```go
// bridge/internal/cmux/client_test.go
package cmux

import (
	"context"
	"strings"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/testutil"
)

func TestRpcReturnsStdoutJSON(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
# echo args back as JSON so the test can assert them
printf '{"method":"%s","params":%s}' "$2" "${3:-null}"
`)
	c := &Client{Bin: bin}
	out, err := c.Rpc(context.Background(), "mobile.workspace.list", map[string]any{"x": 1})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(out), `"method":"mobile.workspace.list"`) {
		t.Fatalf("unexpected: %s", out)
	}
	if !strings.Contains(string(out), `"x":1`) {
		t.Fatalf("params not forwarded: %s", out)
	}
}

func TestRpcErrorIncludesStderr(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
echo "boom" >&2
exit 3
`)
	c := &Client{Bin: bin}
	_, err := c.Rpc(context.Background(), "x", nil)
	if err == nil || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("want error containing stderr, got %v", err)
	}
}

func TestEventsStreamsLines(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
printf '{"seq":1}\n{"seq":2}\n'
`)
	c := &Client{Bin: bin}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cmd, pipe, err := c.Events(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer pipe.Close()
	buf := make([]byte, 64)
	n, _ := pipe.Read(buf)
	if !strings.Contains(string(buf[:n]), `"seq":1`) {
		t.Fatalf("want first frame, got %q", buf[:n])
	}
	_ = cmd.Wait()
}
```

```go
// bridge/internal/testutil/fakecmux.go
package testutil

import (
	"os"
	"path/filepath"
	"testing"
)

// WriteFakeCmux writes script to an executable file named "cmux" in a temp dir
// and returns its absolute path. Use it as Client.Bin.
func WriteFakeCmux(t *testing.T, script string) string {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "cmux")
	if err := os.WriteFile(p, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return p
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd bridge && go test ./internal/cmux/`
Expected: FAIL — `undefined: Client`.

- [ ] **Step 3: Implement**

```go
// bridge/internal/cmux/client.go
package cmux

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
)

type Client struct {
	Bin string // path to the cmux binary; "cmux" if empty
}

func (c *Client) bin() string {
	if c.Bin == "" {
		return "cmux"
	}
	return c.Bin
}

// Rpc runs `cmux rpc <method> [json]` and returns stdout as raw JSON.
func (c *Client) Rpc(ctx context.Context, method string, params any) (json.RawMessage, error) {
	args := []string{"rpc", method}
	if params != nil {
		b, err := json.Marshal(params)
		if err != nil {
			return nil, fmt.Errorf("marshal params: %w", err)
		}
		args = append(args, string(b))
	}
	cmd := exec.CommandContext(ctx, c.bin(), args...)
	cmd.Env = append(cmd.Environ(), "CMUX_QUIET=1")
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("cmux rpc %s: %w: %s", method, err, stderr.String())
	}
	return json.RawMessage(stdout.Bytes()), nil
}

// Events starts `cmux events <args...>` and returns the running command plus a
// reader over its stdout (NDJSON). Stop it by cancelling ctx and closing pipe.
func (c *Client) Events(ctx context.Context, args ...string) (*exec.Cmd, io.ReadCloser, error) {
	cmd := exec.CommandContext(ctx, c.bin(), append([]string{"events"}, args...)...)
	cmd.Env = append(cmd.Environ(), "CMUX_QUIET=1")
	pipe, err := cmd.StdoutPipe()
	if err != nil {
		return nil, nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, nil, err
	}
	return cmd, pipe, nil
}
```

- [ ] **Step 4: Run + verify pass**

Run: `cd bridge && go test ./internal/cmux/ ./internal/testutil/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/cmux bridge/internal/testutil
git commit -m "feat(bridge): cmux CLI client seam with fake-cmux test harness"
```

---

### Task 2: Config loading

**Files:**
- Create: `bridge/internal/config/config.go`
- Create: `bridge/config.example.toml`
- Test: `bridge/internal/config/config_test.go`
- Modify: `bridge/go.mod` (add `github.com/BurntSushi/toml`)

**Interfaces:**
- Produces:
  ```go
  type Config struct {
      Listen        string // default "127.0.0.1:8765"
      CmuxBin       string // default "cmux"
      TokenStore    string // default "~/.config/cmux-bridge/devices.json" (expanded)
      FCMProjectID  string
      FCMCredentials string // path to service-account JSON; "" disables push
  }
  func Load(path string) (Config, error) // missing file => defaults; ~ expanded
  ```

- [ ] **Step 1: Failing test**

```go
// bridge/internal/config/config_test.go
package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadDefaultsWhenMissing(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Listen != "127.0.0.1:8765" || cfg.CmuxBin != "cmux" {
		t.Fatalf("bad defaults: %+v", cfg)
	}
}

func TestLoadOverrides(t *testing.T) {
	p := filepath.Join(t.TempDir(), "c.toml")
	os.WriteFile(p, []byte(`
listen = "0.0.0.0:9000"
cmux_bin = "/opt/cmux"
fcm_project_id = "proj-123"
`), 0o644)
	cfg, err := Load(p)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Listen != "0.0.0.0:9000" || cfg.CmuxBin != "/opt/cmux" || cfg.FCMProjectID != "proj-123" {
		t.Fatalf("overrides not applied: %+v", cfg)
	}
}
```

- [ ] **Step 2: Verify fail** — `cd bridge && go test ./internal/config/` → FAIL (undefined).

- [ ] **Step 3: Implement** `Config` + `Load` with toml tags (`listen`, `cmux_bin`, `token_store`, `fcm_project_id`, `fcm_credentials`), defaults applied before decode, `~` expanded via `os.UserHomeDir()`. Run `go get github.com/BurntSushi/toml`. Write `config.example.toml` mirroring the fields with comments.

- [ ] **Step 4: Verify pass** — `cd bridge && go test ./internal/config/` → PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/config bridge/config.example.toml bridge/go.mod bridge/go.sum
git commit -m "feat(bridge): TOML config with defaults and ~ expansion"
```

---

### Task 3: Device-token + pairing-code store

**Files:**
- Create: `bridge/internal/auth/store.go`
- Test: `bridge/internal/auth/store_test.go`

**Interfaces:**
- Produces:
  ```go
  type Device struct { Token, Name string; Created time.Time }
  type Store struct { /* path + mutex */ }
  func Open(path string) (*Store, error)
  func (s *Store) Issue(name string) (token string, err error)   // 256-bit random, persisted
  func (s *Store) Verify(token string) (Device, bool)
  func (s *Store) List() []Device                                 // tokens redacted to last 6
  func (s *Store) Revoke(token string) bool
  func (s *Store) NewPairingCode(ttl time.Duration) string        // short 8-char code, single use
  func (s *Store) RedeemPairingCode(code, name string) (token string, ok bool)
  ```
  Persistence is an atomic write of a JSON file (tmp + rename), mode 0600.

- [ ] **Step 1: Failing tests** — issue→verify roundtrip; revoke makes verify false; unknown token false; `RedeemPairingCode` returns a token once then false on reuse; expired code false. (Use `ttl` of `-1` to test expiry.)

```go
// bridge/internal/auth/store_test.go  (key cases)
func TestIssueVerifyRevoke(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "d.json"))
	tok, err := s.Issue("phone")
	if err != nil { t.Fatal(err) }
	if _, ok := s.Verify(tok); !ok { t.Fatal("should verify") }
	if _, ok := s.Verify("bogus"); ok { t.Fatal("bogus must fail") }
	if !s.Revoke(tok) { t.Fatal("revoke") }
	if _, ok := s.Verify(tok); ok { t.Fatal("revoked must fail") }
}

func TestPairingCodeSingleUse(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "d.json"))
	code := s.NewPairingCode(time.Minute)
	tok, ok := s.RedeemPairingCode(code, "phone")
	if !ok || tok == "" { t.Fatal("first redeem should succeed") }
	if _, ok := s.RedeemPairingCode(code, "phone"); ok { t.Fatal("reuse must fail") }
}

func TestPairingCodeExpiry(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "d.json"))
	code := s.NewPairingCode(-time.Second) // already expired
	if _, ok := s.RedeemPairingCode(code, "phone"); ok { t.Fatal("expired must fail") }
}

func TestPersistenceReload(t *testing.T) {
	p := filepath.Join(t.TempDir(), "d.json")
	s, _ := Open(p)
	tok, _ := s.Issue("phone")
	s2, _ := Open(p) // reopen
	if _, ok := s2.Verify(tok); !ok { t.Fatal("token must survive reload") }
}
```

- [ ] **Step 2: Verify fail.** - [ ] **Step 3: Implement** (`crypto/rand` tokens hex-encoded; codes from an unambiguous alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`; pairing codes held in-memory only, devices persisted). - [ ] **Step 4: Verify pass.**

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/auth/store.go bridge/internal/auth/store_test.go
git commit -m "feat(bridge): device token + single-use pairing code store"
```

---

### Task 4: Bearer-token middleware

**Files:**
- Create: `bridge/internal/auth/middleware.go`
- Test: `bridge/internal/auth/middleware_test.go`

**Interfaces:**
- Consumes: `*Store.Verify`.
- Produces:
  ```go
  func Require(s *Store, next http.Handler) http.Handler
  func DeviceFromContext(ctx context.Context) (Device, bool)
  ```
  Reads `Authorization: Bearer <token>`; on miss/invalid → 401 JSON
  `{"error":"unauthorized"}`; on success attaches `Device` to request context.

- [ ] **Step 1: Failing tests** — no header → 401; bad token → 401; good token → 200 and handler sees the device via `DeviceFromContext`. Use `httptest`.

- [ ] **Step 2: fail.** - [ ] **Step 3: implement.** - [ ] **Step 4: pass.**

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/auth/middleware.go bridge/internal/auth/middleware_test.go
git commit -m "feat(bridge): bearer-token auth middleware"
```

---

### Task 5: `GET /sessions`

**Files:**
- Create: `bridge/internal/server/server.go`
- Create: `bridge/internal/server/sessions.go`
- Test: `bridge/internal/server/sessions_test.go`
- Modify: `bridge/go.mod` (add `github.com/gorilla/websocket` now so later tasks share it)

**Interfaces:**
- Consumes: `*cmux.Client`, `*auth.Store`, `config.Config`.
- Produces:
  ```go
  type Server struct { /* cfg, cmux, store, push, hub */ }
  func New(cfg config.Config, c *cmux.Client, s *auth.Store) *Server
  func (s *Server) Handler() http.Handler   // all routes, auth-wrapped except /pair
  ```
  `GET /sessions` → `{ "sessions": []Session }` derived from
  `mobile.workspace.list`. Dedup by `id`; `kind` = `"agent"` when the title is a
  task line, else `"terminal"`; `needs_attention` defaults false (set by events).

- [ ] **Step 1: Failing test** — fake cmux returns a canned `mobile.workspace.list` payload (the real shape: top-level `groups` + `workspaces`, objects carry `id`, `current_directory`, `preview`/`title`). Assert the handler returns deduped sessions with `id`/`cwd`/`title` populated and unauthorized without a token.

```go
// bridge/internal/server/sessions_test.go  (shape mirrors real mobile.workspace.list)
const fakeWorkspaceList = `{"groups":[],"workspaces":[
  {"id":"882CA6F0","current_directory":"/Users/u/prj/trading","preview":"Build options trading system"},
  {"id":"882CA6F0","current_directory":"/Users/u/prj/trading","preview":"Build options trading system"},
  {"id":"E43BBF04","current_directory":"/Users/u/prj/trading","preview":"u@host:~/prj/trading"}
]}`

func TestSessionsDedupAndShape(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, `#!/bin/sh
case "$2" in
  mobile.workspace.list) cat <<'JSON'
`+fakeWorkspaceList+`
JSON
  ;;
esac`)
	// new Server with cmux.Client{Bin: bin}, issue a token, GET /sessions with it
	// assert 2 unique sessions, ids 882CA6F0 + E43BBF04, cwd set.
}
```

- [ ] **Step 2: fail.** - [ ] **Step 3: implement** `New`, `Handler` (router: `/pair`, `/devices/register`, `/sessions`, `/events`, `/terminal/`, `/feed/`), the `mobile.workspace.list` → `[]Session` normaliser (recursive walk collecting objects with `current_directory`, dedup by id, first non-shell title wins). - [ ] **Step 4: pass.**

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/server.go bridge/internal/server/sessions.go bridge/internal/server/sessions_test.go bridge/go.mod bridge/go.sum
git commit -m "feat(bridge): GET /sessions normalized from mobile.workspace.list"
```

---

### Task 6: `WS /events` + agent-attention classifier (resolves S3)

**Files:**
- Create: `bridge/internal/server/events.go`
- Test: `bridge/internal/server/events_test.go`

**Interfaces:**
- Produces:
  ```go
  func classify(name string, frame map[string]any) (EventFrame, bool) // false => drop (noise/heartbeat-only)
  func needsAttention(name string, frame map[string]any) bool
  ```
  Attention is true when the frame is a feed item with
  `kind in {permissionRequest, question, exitPlan}` and `status == "pending"`,
  OR a `notification.*` event whose message matches "needs your permission" /
  "waiting for your input". `WS /events` runs `cmux events --reconnect`
  (categories `feed`,`notification`), classifies each NDJSON line, and fans out
  `EventFrame`s to connected clients via a hub. Heartbeats forwarded as
  `{"type":"heartbeat"}` so the app can detect liveness.

- [ ] **Step 1: Failing tests** — table of frames → `needsAttention` true/false (pending permission ⇒ true; answered question ⇒ false; chat message ⇒ false; "needs your permission" notification ⇒ true). Plus an integration test: fake `cmux events` emitting two NDJSON lines, a test WS client receives the classified frames. Use `httptest.NewServer(s.Handler())` and `gorilla/websocket` dialer with the bearer token header.

- [ ] **Step 2: fail.** - [ ] **Step 3: implement** classifier + hub (`map[*conn]bool` guarded by mutex, register/unregister/broadcast) + the events goroutine reading the pipe with `bufio.Scanner`. - [ ] **Step 4: pass.**

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/events.go bridge/internal/server/events_test.go
git commit -m "feat(bridge): WS /events stream with agent-attention classifier"
```

---

### Task 7: `WS /terminal/{id}`

**Files:**
- Create: `bridge/internal/server/terminal.go`
- Test: `bridge/internal/server/terminal_test.go`

**Interfaces:**
- Produces: handler for `WS /terminal/{id}` that:
  - on connect: calls `mobile.terminal.replay {surface_id:id}` and sends one
    `TerminalDown{type:"replay", grid:..., columns:...}` (real shape:
    `render_grid` with `format:"cmux.render-grid.v1"`, `columns`, `cursor`,
    `rows`).
  - down: forwards that surface's output frames (from the shared events stream)
    as `TerminalDown{type:"output"}`.
  - up: `TerminalUp{type:"input",text}` → `mobile.terminal.input {surface_id,text}`;
    `type:"resize"` → `mobile.terminal.viewport {surface_id,columns,rows}`;
    `type:"paste"` → `mobile.terminal.paste`.
  - **never** calls create/close.

- [ ] **Step 1: Failing test** — fake cmux answers `mobile.terminal.replay` with a canned render-grid and records `mobile.terminal.input` args to a temp file. Test: dial `WS /terminal/SURF1`, assert first message is a `replay` with `columns>0`; send an `input` frame; assert the fake recorded `surface_id:"SURF1"` + the text. (Fake writes received argv to `$CMUX_FAKE_LOG`.)

- [ ] **Step 2: fail.** - [ ] **Step 3: implement** (path parse for `{id}`; replay call; read loop mapping `TerminalUp`→rpc; subscribe to hub filtered by surface id for output). - [ ] **Step 4: pass.**

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/terminal.go bridge/internal/server/terminal_test.go
git commit -m "feat(bridge): WS /terminal/{id} replay + input/resize/paste"
```

---

### Task 8: `POST /feed/{id}/reply`

**Files:**
- Create: `bridge/internal/server/feed.go`
- Test: `bridge/internal/server/feed_test.go`

**Interfaces:**
- Produces: handler that reads `FeedReply` JSON and dispatches by `kind`:
  `permission` → `feed.permission.reply`, `question` → `feed.question.reply`,
  `exitPlan` → `feed.exit_plan.reply`, each with `{request_id, ...}` (request_id
  is required by cmux). Returns 400 on unknown kind / missing `request_id`,
  502 if the cmux call errors, 200 `{"ok":true}` on success.

- [ ] **Step 1: Failing tests** — permission reply forwards `feed.permission.reply` with `request_id` + `decision` (assert via fake log); missing `request_id` → 400; unknown kind → 400.

- [ ] **Step 2: fail.** - [ ] **Step 3: implement.** - [ ] **Step 4: pass.**

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/server/feed.go bridge/internal/server/feed_test.go
git commit -m "feat(bridge): POST /feed/{id}/reply routing to cmux feed methods"
```

---

### Task 9: FCM push + `POST /devices/register`

**Files:**
- Create: `bridge/internal/push/fcm.go`
- Create: `bridge/internal/server/devices.go`
- Test: `bridge/internal/push/fcm_test.go`
- Test: `bridge/internal/server/devices_test.go`

**Interfaces:**
- Produces:
  ```go
  type Sender struct { ProjectID, Endpoint string; HTTP *http.Client; Token func(context.Context) (string, error) }
  func (s *Sender) Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
  ```
  `Send` POSTs the FCM HTTP v1 `messages:send` body (high-priority `data`
  message) to `Endpoint` (default the real FCM URL; overridable for tests) with
  `Authorization: Bearer <oauth>` from `Token`. `Endpoint` + `Token` injection
  is what makes it testable with `httptest` and no Google network.
- Produces: `POST /devices/register {fcm_token}` stores the FCM token on the
  caller's `Device` (extends `auth.Store` with `SetFCMToken(token, fcm string)`
  and `FCMTokens() []string`). The events goroutine calls `Sender.Send` for each
  registered FCM token when `needsAttention` is true.

- [ ] **Step 1: Failing tests**
  - `fcm_test.go`: `httptest` server captures the request; assert URL contains the project id, `Authorization: Bearer test-oauth`, body has `message.data.feed_id` and `android.priority == "high"`. `Token` returns a fixed string; no real Google.
  - `devices_test.go`: register sets the token (assert via `FCMTokens()`); a classified attention event triggers exactly one `Send` per registered token (inject a fake Sender recording calls).

- [ ] **Step 2: fail.** - [ ] **Step 3: implement** (`Send` marshals the v1 schema; `devices.go` handler; wire `Server.push` into the events loop behind a nil-check so push is optional when `FCMCredentials==""`). The OAuth `Token` func is built in `runServe` from the service-account JSON via `golang.org/x/oauth2/google` with scope `https://www.googleapis.com/auth/firebase.messaging`; unit tests inject a stub, so no Google dependency is exercised in tests. - [ ] **Step 4: pass.**

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/push bridge/internal/server/devices.go bridge/internal/server/devices_test.go bridge/go.mod bridge/go.sum
git commit -m "feat(bridge): FCM HTTP v1 sender + device registration + push on attention"
```

---

### Task 10: `serve`/`pair`/`devices` wiring + deployment artifacts + docs

**Files:**
- Modify: `bridge/cmd/cmux-bridge/main.go` (replace stubs)
- Create: `bridge/deploy/com.sodre90.cmux-bridge.plist`
- Create: `bridge/deploy/nginx-cmux-bridge.conf`
- Create: `bridge/README.md`
- Test: `bridge/internal/server/server_test.go` (smoke: `Handler()` serves `/sessions` 401 without token, 200 with)

**Interfaces:**
- `runServe`: load config, open store, build `cmux.Client`, optional `push.Sender`, `server.New(...).Handler()`, `http.ListenAndServe(cfg.Listen, h)`; start the events goroutine.
- `runPair`: `store.NewPairingCode(10m)`, print the code big to stdout.
- `runDevices`: `list` (default) prints redacted devices; `revoke <token>` revokes.

- [ ] **Step 1: Smoke test** — build a `Server`, hit `/sessions` without and with a token via `httptest`, assert 401 then 200 (fake cmux). Confirms routing + auth wiring end to end.

- [ ] **Step 2: fail (if needed).** - [ ] **Step 3: implement** wiring + write artifacts:
  - **launchd plist** `com.sodre90.cmux-bridge`: `RunAtLoad`, `KeepAlive`,
    `ProgramArguments = [<abs path>/cmux-bridge, serve]`, `StandardOut/ErrorPath`
    to `~/Library/Logs/cmux-bridge.log`, `EnvironmentVariables.PATH` including
    cmux's bin dir. Loaded with `launchctl bootstrap gui/$(id -u) <plist>`.
  - **nginx vhost**: `server` on the dedicated DNS name, `ssl_client_certificate`
    + `ssl_verify_client on` (mTLS gate), `location /` and `location /` WS upgrade
    headers (`Upgrade`/`Connection`) `proxy_pass http://<mac-lan-ip>:8765;`.
  - **README**: build (`go build -o cmux-bridge ./cmd/cmux-bridge`), config,
    `launchctl` install, nginx snippet, pairing walkthrough
    (`cmux-bridge pair` → enter code in app), Firebase setup pointer, the
    explicit "no destructive cmux calls / GPL independent-work" notes.
- [ ] **Step 4: pass** — `cd bridge && go build ./... && go test ./...` green.

- [ ] **Step 5: Commit**

```bash
git add bridge/cmd bridge/deploy bridge/README.md bridge/internal/server/server_test.go
git commit -m "feat(bridge): serve/pair/devices wiring, launchd + nginx + README"
```

---

## Self-review (spec coverage)

- Bridge API §5.3 — `/pair` (T4/T10), `/sessions` (T5), `/terminal/{id}` (T7),
  `/events` (T6), `/feed/{id}/reply` (T8), `/devices/register` (T9). ✅
- Security model §4 — device-token store + middleware (T3/T4), pairing (T4),
  bind loopback/LAN via `Listen` default (T2/T10), no socket password (Global
  Constraints / T1), revocation (`devices revoke`, T3/T10), mTLS at nginx (T10
  artifact). ✅
- cmux connection §5.2 / S1 — resolved: CLI subprocess transport (T1). ✅
- Terminal shape §6.3 / S2 — resolved: render-grid cell snapshots; bridge passes
  the grid through (T7). The Android renderer (cell grid vs emulator) is in the
  follow-on app plan. ✅
- "Agent needs you" §7 / S3 — `classify`/`needsAttention` (T6), push (T9). ✅
- Lifecycle §5.1 / S5 — launchd LaunchAgent template (T10); the
  reach-the-GUI-socket validation is a runtime install check noted in README. ✅
- Push §7 — FCM HTTP v1 sender + registration + trigger (T9). ✅

**Out of scope for this plan (follow-on):** the Android app (Compose UI, OkHttp
mTLS client, FCM client, terminal renderer — spike S4) and the user-specific
edge bring-up (issuing the client cert, the real nginx DNS host). These become
`docs/superpowers/plans/2026-06-29-cmux-android-app.md`.

## Execution

Executed inline in this session (user requested autonomous implementation),
task-by-task with TDD and a commit per task, using the superpowers:executing-plans
discipline. The Android app + edge plan is written and executed after the bridge
is green.
