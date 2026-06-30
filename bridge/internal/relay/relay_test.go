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
		"CN=mac-agent":         "mac-agent",
		"CN=mac-agent,O=home":  "mac-agent",
		"/CN=mac-agent/O=home": "mac-agent",
		"O=home,CN=mac-agent":  "mac-agent",
		"O=home":               "",
		"":                     "",
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
	// Agent side: trusted handler backed by a fake cmux returning one workspace
	// with one terminal pane (a workspace always carries a terminals array).
	const ws = `{"workspaces":[{"id":"E43BBF04","current_directory":"/x","preview":"u@h:~/x","terminals":[{"id":"E43BBF04-T1","current_directory":"/x","title":"~/x","is_focused":true,"is_ready":true}]}]}`
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
		Workspaces []map[string]any `json:"workspaces"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Workspaces) != 1 {
		t.Fatalf("want 1 workspace, got %d", len(body.Workspaces))
	}

	// A device with no bearer is rejected before reaching the agent.
	bad, err := http.Get(relayHTTP.URL + "/sessions")
	if err != nil {
		t.Fatal(err)
	}
	bad.Body.Close()
	if bad.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 without bearer, got %d", bad.StatusCode)
	}
}

func TestRelayHealthz(t *testing.T) {
	// /healthz is liveness only: no client cert, no bearer, no agent required.
	rl := New(nil, "mac-agent", "tok")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200 from /healthz, got %d", resp.StatusCode)
	}
}

func TestRelayEdgeTokenGate(t *testing.T) {
	rl := New(nil, "mac-agent", "tok")
	rl.SetEdgeToken("edge-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	// /healthz stays open even with the gate on (local container healthcheck).
	h, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	h.Body.Close()
	if h.StatusCode != http.StatusOK {
		t.Fatalf("healthz should stay open, got %d", h.StatusCode)
	}

	// No edge token: stopped at the gate (401) before any CN logic, even though
	// the wrong CN would otherwise yield 403 on the tunnel route.
	req1, _ := http.NewRequest("GET", srv.URL+"/agent/tunnel", nil)
	req1.Header.Set("X-Client-Cert-Cn", "CN=phone")
	r1, err := http.DefaultClient.Do(req1)
	if err != nil {
		t.Fatal(err)
	}
	r1.Body.Close()
	if r1.StatusCode != http.StatusUnauthorized {
		t.Fatalf("missing edge token → want 401, got %d", r1.StatusCode)
	}

	// Correct edge token: passes the gate, then hits the CN check → 403.
	req2, _ := http.NewRequest("GET", srv.URL+"/agent/tunnel", nil)
	req2.Header.Set("X-Edge-Token", "edge-secret")
	req2.Header.Set("X-Client-Cert-Cn", "CN=phone")
	r2, err := http.DefaultClient.Do(req2)
	if err != nil {
		t.Fatal(err)
	}
	r2.Body.Close()
	if r2.StatusCode != http.StatusForbidden {
		t.Fatalf("with edge token, wrong CN → want 403, got %d", r2.StatusCode)
	}
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
