package server

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakePusher struct {
	mu    sync.Mutex
	calls []pushCall
}

type pushCall struct {
	token, title, body string
	data               map[string]string
}

func (p *fakePusher) Send(_ context.Context, token, title, body string, data map[string]string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls = append(p.calls, pushCall{token, title, body, data})
	return nil
}

func (p *fakePusher) count() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.calls)
}

func (p *fakePusher) first() pushCall {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.calls[0]
}

func TestDeviceRegisterStoresFCMToken(t *testing.T) {
	s, tok := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/devices/register", strings.NewReader(`{"fcm_token":"fcm-1"}`))
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	got := s.store.FCMTokens()
	if len(got) != 1 || got[0] != "fcm-1" {
		t.Fatalf("fcm token not stored: %v", got)
	}
}

func TestDeviceRegisterMissingToken400(t *testing.T) {
	s, tok := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/devices/register", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400, got %d", resp.StatusCode)
	}
}

func TestAttentionEventTriggersPush(t *testing.T) {
	s, tok := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	fp := &fakePusher{}
	s.SetPusher(fp)
	s.store.SetFCMToken(tok, "fcm-xyz")

	feed := `{"type":"event","name":"feed.item.received","category":"feed","id":"BOOT-1","payload":{"hook_event_name":"Notification","phase":"received","cwd":"/Users/perdos/prj/cmux-app"}}`
	go s.ingestEvents(context.Background(), strings.NewReader(feed+"\n"))

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if fp.count() >= 1 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if fp.count() != 1 {
		t.Fatalf("expected exactly one push, got %d", fp.count())
	}
	call := fp.first()
	if call.token != "fcm-xyz" || call.data["feed_id"] != "BOOT-1" || call.data["kind"] != "Notification" {
		t.Fatalf("push call wrong: %+v", call)
	}
}

func TestNonAttentionEventNoPush(t *testing.T) {
	s, _ := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	fp := &fakePusher{}
	s.SetPusher(fp)

	// ordinary tool-call feed item -> no attention -> no push
	feed := `{"type":"event","name":"feed.item.received","category":"feed","id":"BOOT-2","payload":{"hook_event_name":"PreToolUse","phase":"received","tool_name":"Bash","cwd":"/x"}}`
	s.ingestEvents(context.Background(), strings.NewReader(feed+"\n"))

	time.Sleep(100 * time.Millisecond)
	if fp.count() != 0 {
		t.Fatalf("expected no push for an ordinary tool call, got %d", fp.count())
	}
}
