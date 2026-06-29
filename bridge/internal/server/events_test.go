package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestNeedsAttention(t *testing.T) {
	cases := []struct {
		kind, status string
		want         bool
	}{
		{"permissionRequest", "pending", true},
		{"question", "pending", true},
		{"exitPlan", "pending", true},
		{"permissionRequest", "answered", false},
		{"question", "resolved", false},
		{"toolUse", "telemetry", false},
		{"toolResult", "telemetry", false},
		{"sessionStart", "pending", false},
	}
	for _, c := range cases {
		if got := needsAttention(c.kind, c.status); got != c.want {
			t.Errorf("needsAttention(%q,%q)=%v want %v", c.kind, c.status, got, c.want)
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

func TestClassifyFeedPendingPrompt(t *testing.T) {
	raw := `{"type":"event","name":"feed.item.received","category":"feed","workspace_id":"W1",
		"payload":{"id":"F1","kind":"permissionRequest","status":"pending","title":"Run rm -rf?","workstream_id":"WS1"}}`
	var m map[string]any
	_ = json.Unmarshal([]byte(raw), &m)
	f, ok := classify(m)
	if !ok {
		t.Fatal("feed prompt should be forwarded")
	}
	if f.Type != "feed" || !f.NeedsAttention || f.FeedID != "F1" || f.Kind != "permissionRequest" {
		t.Fatalf("unexpected frame: %+v", f)
	}
	if f.WorkspaceID != "WS1" { // workstream_id preferred
		t.Fatalf("workspace id wrong: %+v", f)
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

	feed := `{"type":"event","name":"feed.item.received","category":"feed","payload":{"id":"F9","kind":"question","status":"pending","title":"Which option?"}}`
	noise := `{"type":"event","name":"pane.focused","category":"pane","payload":{}}`
	go s.ingestEvents(context.Background(), strings.NewReader(noise+"\n"+feed+"\n"))

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var got EventFrame
	if err := c.ReadJSON(&got); err != nil {
		t.Fatal(err)
	}
	if got.FeedID != "F9" || got.Kind != "question" || !got.NeedsAttention {
		t.Fatalf("expected the classified feed frame, got %+v", got)
	}
}
