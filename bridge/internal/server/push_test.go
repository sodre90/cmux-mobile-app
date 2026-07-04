package server

import (
	"context"
	"path/filepath"
	"sync"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
)

type fakePusher struct {
	mu    sync.Mutex
	calls []struct {
		token, title, body string
		data               map[string]string
	}
}

func (p *fakePusher) Send(_ context.Context, tok, title, body string, data map[string]string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls = append(p.calls, struct {
		token, title, body string
		data               map[string]string
	}{tok, title, body, data})
	return nil
}

func (p *fakePusher) callCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.calls)
}

func newPushTestServer(t *testing.T) (*Server, *auth.Store) {
	t.Helper()
	store, err := auth.Open(filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	s := New(config.Config{}, &cmux.Client{}, store)
	return s, store
}

func TestMaybeSendPushNoopWithoutPusher(t *testing.T) {
	s, store := newPushTestServer(t)
	tenant, _ := store.CreateTenant()
	tok, _ := store.Issue(tenant, "phone", "test-pubkey-b64")
	store.SetFCMToken(tok, "fcm-token-1")
	// s.pusher is nil (SetPusher never called) -- must not panic, must not
	// look anything up.
	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})
}

func TestMaybeSendPushNoopWithoutStore(t *testing.T) {
	s := New(config.Config{}, &cmux.Client{}, nil) // store nil: direct mode off
	fp := &fakePusher{}
	s.SetPusher(fp, "some-tenant")
	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})
	if fp.callCount() != 0 {
		t.Fatalf("must not call pusher when store is nil, got %d calls", fp.callCount())
	}
}

func TestMaybeSendPushSendsToEveryRegisteredToken(t *testing.T) {
	s, store := newPushTestServer(t)
	tenant, _ := store.CreateTenant()
	tok1, _ := store.Issue(tenant, "phone-1", "test-pubkey-1")
	tok2, _ := store.Issue(tenant, "phone-2", "test-pubkey-2")
	store.SetFCMToken(tok1, "fcm-1")
	store.SetFCMToken(tok2, "fcm-2")

	fp := &fakePusher{}
	s.SetPusher(fp, tenant)

	s.maybeSendPush(context.Background(), EventFrame{
		NeedsAttention: true, FeedID: "F1", WorkspaceID: "W1", SurfaceID: "S1",
		Title: "Run rm -rf?", Kind: "permissionRequest",
	})

	if fp.callCount() != 2 {
		t.Fatalf("want 2 push calls (one per registered token), got %d", fp.callCount())
	}
	got := map[string]bool{}
	for _, c := range fp.calls {
		got[c.token] = true
		if c.data["type"] != "attention" || c.data["feed_id"] != "F1" || c.data["workspace_id"] != "W1" || c.data["kind"] != "permissionRequest" {
			t.Fatalf("unexpected push data: %+v", c.data)
		}
		if c.body != "Run rm -rf?" {
			t.Fatalf("body = %q, want the frame's Title", c.body)
		}
	}
	if !got["fcm-1"] || !got["fcm-2"] {
		t.Fatalf("expected both tokens to receive push, got calls: %+v", fp.calls)
	}
}

func TestMaybeSendPushNoopWithNoRegisteredTokens(t *testing.T) {
	s, store := newPushTestServer(t)
	tenant, _ := store.CreateTenant()
	fp := &fakePusher{}
	s.SetPusher(fp, tenant)

	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})

	if fp.callCount() != 0 {
		t.Fatalf("no tokens registered -- want 0 calls, got %d", fp.callCount())
	}
}

func TestMaybeSendPushScopesToOwnTenant(t *testing.T) {
	s, store := newPushTestServer(t)
	tenantA, _ := store.CreateTenant()
	tenantB, _ := store.CreateTenant()
	tokA, _ := store.Issue(tenantA, "phone-a", "test-pubkey-a")
	tokB, _ := store.Issue(tenantB, "phone-b", "test-pubkey-b")
	store.SetFCMToken(tokA, "fcm-a")
	store.SetFCMToken(tokB, "fcm-b")

	fp := &fakePusher{}
	s.SetPusher(fp, tenantA) // Server only knows about tenantA

	s.maybeSendPush(context.Background(), EventFrame{NeedsAttention: true, Kind: "permissionRequest"})

	if fp.callCount() != 1 || fp.calls[0].token != "fcm-a" {
		t.Fatalf("push must be scoped to directTenantID only, got calls: %+v", fp.calls)
	}
}
