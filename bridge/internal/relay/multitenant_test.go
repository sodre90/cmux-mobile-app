package relay

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/testutil"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

func TestRelayIsolatesTenants(t *testing.T) {
	const wsA = `{"workspaces":[{"id":"A","current_directory":"/a","preview":"tenant-a-secret","terminals":[{"id":"A-T1","current_directory":"/a","title":"~/a","is_focused":true,"is_ready":true}]}]}`
	const wsB = `{"workspaces":[{"id":"B","current_directory":"/b","preview":"tenant-b-secret","terminals":[{"id":"B-T1","current_directory":"/b","title":"~/b","is_focused":true,"is_ready":true}]}]}`
	binA := testutil.WriteFakeCmux(t, "#!/bin/sh\ncat <<'JSON'\n"+wsA+"\nJSON\n")
	binB := testutil.WriteFakeCmux(t, "#!/bin/sh\ncat <<'JSON'\n"+wsB+"\nJSON\n")
	const relayTok = "relay-secret"
	agentA := server.New(config.Config{}, &cmux.Client{Bin: binA}, nil).TrustedHandler(relayTok)
	agentB := server.New(config.Config{}, &cmux.Client{Bin: binB}, nil).TrustedHandler(relayTok)

	store, err := auth.Open(filepath.Join(t.TempDir(), "r.db"))
	if err != nil {
		t.Fatal(err)
	}
	tenantA, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tenantB, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	devA, _ := store.Issue(tenantA, "phone-a")
	devB, _ := store.Issue(tenantB, "phone-b")

	rl := New(store, nil, relayTok)
	relayHTTP := httptest.NewServer(rl.Handler())
	defer relayHTTP.Close()

	dial := func(tenantID string, h http.Handler) {
		u := "ws" + strings.TrimPrefix(relayHTTP.URL, "http") + "/agent/tunnel"
		sess, err := tunnel.Dial(context.Background(), u, nil, http.Header{"X-Client-Cert-Cn": {"CN=agent:" + tenantID}})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { sess.Close() })
		go func() { _ = http.Serve(sess, h) }()
	}
	dial(tenantA, agentA)
	dial(tenantB, agentB)

	waitFor(t, func() bool { return rl.reg.Get(tenantA) != nil && rl.reg.Get(tenantB) != nil })

	fetch := func(token string) string {
		req, _ := http.NewRequest("GET", relayHTTP.URL+"/sessions", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		return string(body)
	}

	bodyA := fetch(devA)
	if !strings.Contains(bodyA, "tenant-a-secret") {
		t.Fatalf("tenant A's device should see tenant A's data: %s", bodyA)
	}
	if strings.Contains(bodyA, "tenant-b-secret") {
		t.Fatalf("tenant A's device must never see tenant B's data: %s", bodyA)
	}

	bodyB := fetch(devB)
	if !strings.Contains(bodyB, "tenant-b-secret") {
		t.Fatalf("tenant B's device should see tenant B's data: %s", bodyB)
	}
	if strings.Contains(bodyB, "tenant-a-secret") {
		t.Fatalf("tenant B's device must never see tenant A's data: %s", bodyB)
	}
}

func TestRelayRevokedTenantCannotReconnectOrServeDevices(t *testing.T) {
	store, err := auth.Open(filepath.Join(t.TempDir(), "r.db"))
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	devTok, _ := store.Issue(tenantID, "phone")
	store.RevokeTenant(tenantID)

	rl := New(store, nil, "relay-secret")
	relayHTTP := httptest.NewServer(rl.Handler())
	defer relayHTTP.Close()

	req, _ := http.NewRequest("GET", relayHTTP.URL+"/agent/tunnel", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("revoked tenant's agent must be refused a tunnel, got %d", resp.StatusCode)
	}

	req2, _ := http.NewRequest("GET", relayHTTP.URL+"/sessions", nil)
	req2.Header.Set("Authorization", "Bearer "+devTok)
	resp2, err := http.DefaultClient.Do(req2)
	if err != nil {
		t.Fatal(err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusUnauthorized {
		t.Fatalf("a revoked tenant's device token must stop verifying, got %d", resp2.StatusCode)
	}
}
