package server

import (
	"net/http"
	"net/http/httptest"
	"os"
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
		`{"kind":"permission","request_id":"REQ1","params":{"decision":"approve"}}`)
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

	resp := postFeed(t, srv.URL, tok, "/feed/F1/reply", `{"kind":"permission"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for missing request_id, got %d", resp.StatusCode)
	}
	if data, _ := os.ReadFile(logPath); len(data) != 0 {
		t.Fatalf("cmux must not be called on validation failure; log:\n%s", data)
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
