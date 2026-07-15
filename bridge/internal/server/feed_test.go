package server

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const fakeFeedScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
echo '{"ok":true}'
`

func postFeed(t *testing.T, srvURL, tok, path, body string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("POST", srvURL+path, strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestFeedPermissionReplyRouted(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeFeedScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postFeed(t, srv.URL, tok, "/feed/F1/reply",
		`{"kind":"permissionRequest","request_id":"REQ1","params":{"decision":"approve"}}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	data, _ := os.ReadFile(logPath)
	log := string(data)
	for _, want := range []string{"feed.permission.reply", "REQ1", "request_id", "approve"} {
		if !strings.Contains(log, want) {
			t.Fatalf("log missing %q; got:\n%s", want, log)
		}
	}
}

func TestFeedQuestionAndExitPlanMethods(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeFeedScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	for kind, method := range map[string]string{
		"question": "feed.question.reply",
		"exitPlan": "feed.exit_plan.reply",
	} {
		resp := postFeed(t, srv.URL, tok, "/feed/F1/reply",
			`{"kind":"`+kind+`","request_id":"R2"}`)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("kind %s: want 200, got %d", kind, resp.StatusCode)
		}
		data, _ := os.ReadFile(logPath)
		if !strings.Contains(string(data), method) {
			t.Fatalf("expected %s in log for kind %s", method, kind)
		}
	}
}

func TestFeedMissingRequestID400(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeFeedScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postFeed(t, srv.URL, tok, "/feed/F1/reply", `{"kind":"permissionRequest"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for missing request_id, got %d", resp.StatusCode)
	}
	if data, _ := os.ReadFile(logPath); len(data) != 0 {
		t.Fatalf("cmux must not be called on validation failure; log:\n%s", data)
	}
}

func TestFeedPendingListsItems(t *testing.T) {
	// The agent must surface the full question structure (request_id, options)
	// so the app can reply; the endpoint passes cmux feed.list through verbatim.
	script := `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
echo '{"items":[{"id":"I1","request_id":"REQ1","kind":"question","status":"pending","title":"AskUserQuestion","question_multi_select":false,"questions":[{"id":"q0","prompt":"Pick","options":[{"id":"opt0","label":"A"},{"id":"opt1","label":"B"}]}]}]}'
`
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, script)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/feed/pending", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	for _, want := range []string{"REQ1", "question", "opt0", "AskUserQuestion"} {
		if !strings.Contains(string(body), want) {
			t.Fatalf("response missing %q; got:\n%s", want, body)
		}
	}
	logData, _ := os.ReadFile(logPath)
	if !strings.Contains(string(logData), "feed.list") || !strings.Contains(string(logData), "pending_only") {
		t.Fatalf("cmux not called with feed.list pending_only; got:\n%s", logData)
	}
}

// TestFeedPendingCanonicalizesCWD reproduces the same live-observed symlink
// mismatch as yolo_test.go's TestResolvePendingPermissionMatchesSymlinkedCWD:
// cmux's feed.list reports an item's cwd as a symlink alias while
// mobile.workspace.list (surfaced through /sessions) reports the resolved
// form for the same location. /feed/pending must rewrite "cwd" to its
// canonical form so the app's cwd-based item-to-workspace matching
// (pendingItemTarget in SessionsLogic.kt) has a chance of succeeding.
func TestFeedPendingCanonicalizesCWD(t *testing.T) {
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
echo '{"items":[{"id":"I1","request_id":"REQ1","kind":"question","status":"pending","cwd":"` + alias + `"}]}'
`
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, script)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/feed/pending", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	var body struct {
		Items []struct {
			CWD string `json:"cwd"`
		} `json:"items"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Items) != 1 || body.Items[0].CWD != real {
		t.Fatalf("want cwd rewritten to canonical %q, got %+v", real, body.Items)
	}
}

func TestFeedUnknownKind400(t *testing.T) {
	s, tok := newTestServer(t, fakeFeedScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	resp := postFeed(t, srv.URL, tok, "/feed/F1/reply", `{"kind":"bogus","request_id":"R"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for unknown kind, got %d", resp.StatusCode)
	}
}
