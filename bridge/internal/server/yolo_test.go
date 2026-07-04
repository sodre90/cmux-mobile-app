package server

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeYoloScript answers the three RPCs resolvePendingPermission makes:
// mobile.workspace.list (one workspace at /tmp/proj), feed.list (one pending
// permission item whose cwd matches), and feed.permission.reply (logged and
// acked). Every call is also appended to $CMUX_FAKE_LOG for assertions.
const fakeYoloScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.workspace.list)
    echo '{"workspaces":[{"id":"WS1","current_directory":"/tmp/proj","title":"t","terminals":[]}]}'
    ;;
  feed.list)
    echo '{"items":[{"id":"I1","request_id":"REQ1","kind":"permissionRequest","status":"pending","cwd":"/tmp/proj"}]}'
    ;;
  *)
    echo '{"ok":true}'
    ;;
esac
`

func TestResolvePendingPermissionRepliesToMatchingCWD(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, _ := newTestServer(t, fakeYoloScript)

	s.resolvePendingPermission(context.Background(), "WS1", "bypass")

	data, _ := os.ReadFile(logPath)
	log := string(data)
	for _, want := range []string{"feed.permission.reply", "REQ1", "bypass"} {
		if !strings.Contains(log, want) {
			t.Fatalf("log missing %q; got:\n%s", want, log)
		}
	}
}

// TestResolvePendingPermissionMatchesSymlinkedCWD reproduces a live-observed
// mismatch: mobile.workspace.list reports a workspace's raw current_directory
// (which can be a symlink, e.g. macOS's /tmp -> /private/tmp) while feed.list
// reports the same location already resolved. A plain string comparison
// silently drops every match for such a workspace; resolvePendingPermission
// must canonicalize both sides before comparing.
func TestResolvePendingPermissionMatchesSymlinkedCWD(t *testing.T) {
	base, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatalf("resolve temp dir: %v", err)
	}
	real := filepath.Join(base, "real")
	if err := os.Mkdir(real, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	alias := filepath.Join(base, "alias")
	if err := os.Symlink(real, alias); err != nil {
		t.Fatalf("symlink: %v", err)
	}

	script := `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
case "$2" in
  mobile.workspace.list)
    echo '{"workspaces":[{"id":"WS1","current_directory":"` + alias + `","title":"t","terminals":[]}]}'
    ;;
  feed.list)
    echo '{"items":[{"id":"I1","request_id":"REQ1","kind":"permissionRequest","status":"pending","cwd":"` + real + `"}]}'
    ;;
  *)
    echo '{"ok":true}'
    ;;
esac
`
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, _ := newTestServer(t, script)

	s.resolvePendingPermission(context.Background(), "WS1", "bypass")

	data, _ := os.ReadFile(logPath)
	log := string(data)
	for _, want := range []string{"feed.permission.reply", "REQ1", "bypass"} {
		if !strings.Contains(log, want) {
			t.Fatalf("log missing %q (workspace cwd %q, feed item cwd %q); got:\n%s", want, alias, real, log)
		}
	}
}

func TestResolvePendingPermissionSkipsUnknownWorkspace(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, _ := newTestServer(t, fakeYoloScript)

	s.resolvePendingPermission(context.Background(), "NOPE", "bypass")

	data, _ := os.ReadFile(logPath)
	if strings.Contains(string(data), "feed.permission.reply") {
		t.Fatalf("must not reply when the workspace can't be resolved; log:\n%s", data)
	}
}

func postYoloMode(t *testing.T, srvURL, tok, id, body string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("POST", srvURL+"/sessions/"+id+"/yolo-mode", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestSetYoloModePersistsAndAutoResolves(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeYoloScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postYoloMode(t, srv.URL, tok, "WS1", `{"mode":"bypass"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	if got := s.yolo.Mode("WS1"); got != "bypass" {
		t.Fatalf("mode not persisted: got %q", got)
	}
	data, _ := os.ReadFile(logPath)
	if !strings.Contains(string(data), "feed.permission.reply") {
		t.Fatalf("enabling a mode must auto-resolve any already-pending permission; log:\n%s", data)
	}
}

func TestSetYoloModeOffClearsStoredMode(t *testing.T) {
	s, tok := newTestServer(t, fakeYoloScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	postYoloMode(t, srv.URL, tok, "WS1", `{"mode":"always"}`).Body.Close()
	resp := postYoloMode(t, srv.URL, tok, "WS1", `{"mode":""}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	if got := s.yolo.Mode("WS1"); got != "" {
		t.Fatalf("mode should be cleared, got %q", got)
	}
}

func TestSetYoloModeRejectsUnknownMode(t *testing.T) {
	s, tok := newTestServer(t, fakeYoloScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postYoloMode(t, srv.URL, tok, "WS1", `{"mode":"deny"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for an unsupported mode, got %d", resp.StatusCode)
	}
	if got := s.yolo.Mode("WS1"); got != "" {
		t.Fatalf("rejected mode must not be persisted, got %q", got)
	}
}
