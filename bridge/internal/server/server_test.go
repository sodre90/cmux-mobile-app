package server

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// Smoke test: every non-pair route is mounted and gated by the device token.
func TestRoutesRequireAuth(t *testing.T) {
	s, _ := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	cases := []struct {
		method, path string
	}{
		{"GET", "/sessions"},
		{"GET", "/events"},
		{"GET", "/terminal/S1"},
		{"POST", "/feed/F1/reply"},
	}
	for _, c := range cases {
		req, _ := http.NewRequest(c.method, srv.URL+c.path, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", c.method, c.path, err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s %s: want 401 without token, got %d", c.method, c.path, resp.StatusCode)
		}
	}
}

func TestUnknownRoute404(t *testing.T) {
	s, tok := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	req, _ := http.NewRequest("GET", srv.URL+"/nope", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404 for unknown route, got %d", resp.StatusCode)
	}
}
