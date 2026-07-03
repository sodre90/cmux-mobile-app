package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestNeedsAttention(t *testing.T) {
	// cmux feed items carry the Claude Code hook event in payload.hook_event_name
	// and fire twice: phase "received" then "completed". We alert once, on
	// "received", and only for the events that block on the user.
	cases := []struct {
		hook, phase string
		want        bool
	}{
		{"Notification", "received", true},
		{"AskUserQuestion", "received", true},
		{"Notification", "completed", false},
		{"AskUserQuestion", "completed", false},
		{"PreToolUse", "received", false},
		{"Stop", "received", false},
		{"SubagentStop", "received", false},
		{"UserPromptSubmit", "received", false},
		{"SessionStart", "received", false},
		{"", "", false},
	}
	for _, c := range cases {
		if got := needsAttention(c.hook, c.phase); got != c.want {
			t.Errorf("needsAttention(%q,%q)=%v want %v", c.hook, c.phase, got, c.want)
		}
	}
}

func TestClassifyDropsNoise(t *testing.T) {
	var m map[string]any
	_ = json.Unmarshal([]byte(`{"type":"event","name":"surface.selected","category":"surface","payload":{}}`), &m)
	if _, ok := classify(m); ok {
		t.Fatal("surface churn should be dropped")
	}
	var ack map[string]any
	_ = json.Unmarshal([]byte(`{"type":"ack","protocol":"cmux-events"}`), &ack)
	if _, ok := classify(ack); ok {
		t.Fatal("ack should be dropped")
	}
}

func TestClassifyFeedAttentionPrompt(t *testing.T) {
	// A real cmux feed frame: the blocking signal is payload.hook_event_name,
	// the feed id is the top-level id, and the only human label available
	// (cmux redacts the prompt) is the cwd basename.
	raw := `{"type":"event","name":"feed.item.received","category":"feed",
		"id":"BOOT-168","workspace_id":"W1",
		"payload":{"hook_event_name":"Notification","phase":"received",
			"session_id":"claude-abc","cwd":"/Users/perdos/prj/cmux-app","workspace_id":"W1"}}`
	var m map[string]any
	_ = json.Unmarshal([]byte(raw), &m)
	f, ok := classify(m)
	if !ok {
		t.Fatal("feed prompt should be forwarded")
	}
	if f.Type != "feed" || !f.NeedsAttention || f.FeedID != "BOOT-168" || f.Kind != "Notification" {
		t.Fatalf("unexpected frame: %+v", f)
	}
	if f.WorkspaceID != "W1" {
		t.Fatalf("workspace id wrong: %+v", f)
	}
	if f.Title != "cmux-app" { // cwd basename, used as the "which agent" label
		t.Fatalf("title (cwd basename) wrong: %+v", f)
	}
}

func TestClassifyFeedNonBlocking(t *testing.T) {
	// PreToolUse fires constantly and must never alert; the "completed" phase of
	// an attention event must not re-alert either.
	for _, raw := range []string{
		`{"type":"event","category":"feed","id":"BOOT-1","payload":{"hook_event_name":"PreToolUse","phase":"received","cwd":"/x/y","tool_name":"Bash"}}`,
		`{"type":"event","category":"feed","id":"BOOT-2","payload":{"hook_event_name":"Notification","phase":"completed","cwd":"/x/y"}}`,
	} {
		var m map[string]any
		_ = json.Unmarshal([]byte(raw), &m)
		f, ok := classify(m)
		if !ok {
			t.Fatalf("feed frame should be forwarded: %s", raw)
		}
		if f.NeedsAttention {
			t.Fatalf("frame must not set attention: %+v", f)
		}
	}
}

func TestClassifyNotificationNoAttention(t *testing.T) {
	raw := `{"type":"event","name":"notification.created","category":"notification",
		"payload":{"notification_id":"N1","title":null,"surface_id":"S1","workspace_id":"W1"}}`
	var m map[string]any
	_ = json.Unmarshal([]byte(raw), &m)
	f, ok := classify(m)
	if !ok {
		t.Fatal("notification should be forwarded")
	}
	if f.Type != "notification" || f.NeedsAttention {
		t.Fatalf("notification must not set attention (redacted body): %+v", f)
	}
	if f.SurfaceID != "S1" || f.WorkspaceID != "W1" {
		t.Fatalf("ids wrong: %+v", f)
	}
}

// wsDial connects a websocket client to the test server's /events with token.
func wsDial(t *testing.T, srvURL, tok string) *websocket.Conn {
	t.Helper()
	u := "ws" + strings.TrimPrefix(srvURL, "http") + "/events"
	h := http.Header{"Authorization": {"Bearer " + tok}}
	c, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err != nil {
		code := 0
		if resp != nil {
			code = resp.StatusCode
		}
		t.Fatalf("ws dial failed (status %d): %v", code, err)
	}
	return c
}

func TestWSEventsDeliversBroadcast(t *testing.T) {
	s, tok := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsDial(t, srv.URL, tok)
	defer c.Close()
	time.Sleep(100 * time.Millisecond) // let the handler register with the hub

	s.hub.broadcast(EventFrame{Type: "feed", FeedID: "X", NeedsAttention: true})

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var got EventFrame
	if err := c.ReadJSON(&got); err != nil {
		t.Fatal(err)
	}
	if got.FeedID != "X" || !got.NeedsAttention {
		t.Fatalf("unexpected frame: %+v", got)
	}
}

func TestWSEventsRejectsNoToken(t *testing.T) {
	s, _ := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	u := "ws" + strings.TrimPrefix(srv.URL, "http") + "/events"
	_, resp, err := websocket.DefaultDialer.Dial(u, nil)
	if err == nil {
		t.Fatal("expected dial to fail without token")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 handshake, got %v", resp)
	}
}

func TestIngestEventsBroadcastsClassified(t *testing.T) {
	s, tok := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsDial(t, srv.URL, tok)
	defer c.Close()
	time.Sleep(100 * time.Millisecond)

	feed := `{"type":"event","name":"feed.item.received","category":"feed","id":"BOOT-9","payload":{"hook_event_name":"AskUserQuestion","phase":"received","cwd":"/Users/perdos/prj/cmux-app"}}`
	noise := `{"type":"event","name":"pane.focused","category":"pane","payload":{}}`
	go s.ingestEvents(context.Background(), strings.NewReader(noise+"\n"+feed+"\n"))

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var got EventFrame
	if err := c.ReadJSON(&got); err != nil {
		t.Fatal(err)
	}
	if got.FeedID != "BOOT-9" || got.Kind != "AskUserQuestion" || !got.NeedsAttention {
		t.Fatalf("expected the classified feed frame, got %+v", got)
	}
}

func TestIngestEventsEnrichesAttentionTitle(t *testing.T) {
	// Reuses sessions_test.go's fakeWorkspaceList: workspace "882CA6F0" has
	// title "✳ Build options" (cleaned to "Build options") and preview "Build
	// options trading system".
	script := "#!/bin/sh\ncat <<'JSON'\n" + fakeWorkspaceList + "\nJSON\n"
	s, tok := newTestServer(t, script)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsDial(t, srv.URL, tok)
	defer c.Close()
	time.Sleep(100 * time.Millisecond)

	feed := `{"type":"event","name":"feed.item.received","category":"feed","id":"BOOT-9","payload":{"hook_event_name":"Notification","phase":"received","cwd":"/Users/u/prj/trading","workspace_id":"882CA6F0"}}`
	go s.ingestEvents(context.Background(), strings.NewReader(feed+"\n"))

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var got EventFrame
	if err := c.ReadJSON(&got); err != nil {
		t.Fatal(err)
	}
	want := "Build options: Build options trading system"
	if got.Title != want {
		t.Fatalf("title not enriched: got %q want %q", got.Title, want)
	}
}

func TestIngestEventsKeepsFallbackTitleWhenWorkspaceNotFound(t *testing.T) {
	script := "#!/bin/sh\ncat <<'JSON'\n" + fakeWorkspaceList + "\nJSON\n"
	s, tok := newTestServer(t, script)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	c := wsDial(t, srv.URL, tok)
	defer c.Close()
	time.Sleep(100 * time.Millisecond)

	feed := `{"type":"event","name":"feed.item.received","category":"feed","id":"BOOT-10","payload":{"hook_event_name":"Notification","phase":"received","cwd":"/x/y","workspace_id":"UNKNOWN"}}`
	go s.ingestEvents(context.Background(), strings.NewReader(feed+"\n"))

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var got EventFrame
	if err := c.ReadJSON(&got); err != nil {
		t.Fatal(err)
	}
	if got.Title != "y" { // cwd basename fallback, unchanged when the lookup misses
		t.Fatalf("title should fall back to cwd basename, got %q", got.Title)
	}
}

func TestIngestEventsAutoResolvesWhenYoloEnabled(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeYoloScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	if err := s.yolo.SetMode("WS1", "bypass"); err != nil {
		t.Fatal(err)
	}

	c := wsDial(t, srv.URL, tok)
	defer c.Close()
	time.Sleep(100 * time.Millisecond)

	feed := `{"type":"event","name":"feed.item.received","category":"feed","id":"BOOT-11","payload":{"hook_event_name":"Notification","phase":"received","cwd":"/tmp/proj","workspace_id":"WS1"}}`
	go s.ingestEvents(context.Background(), strings.NewReader(feed+"\n"))

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var got EventFrame
	if err := c.ReadJSON(&got); err != nil {
		t.Fatal(err)
	}

	// maybeAutoResolve spawns the reply in a goroutine (see yolo.go); poll
	// rather than sleep-then-check so this doesn't flake under load.
	deadline := time.Now().Add(2 * time.Second)
	for {
		data, _ := os.ReadFile(logPath)
		if strings.Contains(string(data), "feed.permission.reply") {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("expected an auto-reply for the yolo-enabled workspace; log:\n%s", data)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestIngestEventsDoesNotAutoResolveWhenYoloOff(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeYoloScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	// WS1's yolo mode is left off (the default).

	c := wsDial(t, srv.URL, tok)
	defer c.Close()
	time.Sleep(100 * time.Millisecond)

	feed := `{"type":"event","name":"feed.item.received","category":"feed","id":"BOOT-12","payload":{"hook_event_name":"Notification","phase":"received","cwd":"/tmp/proj","workspace_id":"WS1"}}`
	go s.ingestEvents(context.Background(), strings.NewReader(feed+"\n"))

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var got EventFrame
	if err := c.ReadJSON(&got); err != nil {
		t.Fatal(err)
	}
	time.Sleep(150 * time.Millisecond) // let a stray goroutine misbehave, if any
	data, _ := os.ReadFile(logPath)
	if strings.Contains(string(data), "feed.permission.reply") {
		t.Fatalf("must not auto-reply when yolo mode is off; log:\n%s", data)
	}
}
