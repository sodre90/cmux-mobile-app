package server

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestTrustedHandlerRelayToken(t *testing.T) {
	script := "#!/bin/sh\ncat <<'JSON'\n" + fakeWorkspaceList + "\nJSON\n"
	s, _ := newTestServer(t, script)
	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	// No relay token → 401.
	resp, err := http.Get(srv.URL + "/sessions")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 without relay token, got %d", resp.StatusCode)
	}

	// Correct relay token → 200, no device bearer needed.
	req, _ := http.NewRequest("GET", srv.URL+"/sessions", nil)
	req.Header.Set("X-Relay-Token", relayTok)
	resp2, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("want 200 with relay token, got %d", resp2.StatusCode)
	}

	// /devices/register is not mounted in trusted mode → 404.
	req3, _ := http.NewRequest("POST", srv.URL+"/devices/register", nil)
	req3.Header.Set("X-Relay-Token", relayTok)
	resp3, err := http.DefaultClient.Do(req3)
	if err != nil {
		t.Fatal(err)
	}
	resp3.Body.Close()
	if resp3.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404 for register in trusted mode, got %d", resp3.StatusCode)
	}
}
