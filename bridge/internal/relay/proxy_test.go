package relay

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/hashicorp/yamux"
)

func TestProxyOfflineReturns503(t *testing.T) {
	p := newProxy(NewRegistry(), "tok")
	rr := httptest.NewRecorder()
	p.ServeHTTP(rr, httptest.NewRequest("GET", "http://relay/sessions", nil))
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("want 503, got %d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "agent_offline") {
		t.Fatalf("body = %q", rr.Body.String())
	}
}

func TestProxyForwardsAndInjectsRelayToken(t *testing.T) {
	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}
	gotTok := make(chan string, 1)
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotTok <- r.Header.Get("X-Relay-Token")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("ok"))
		}))
	}()

	reg := NewRegistry()
	reg.Set(relaySess, nil)
	p := newProxy(reg, "relay-secret")

	rr := httptest.NewRecorder()
	p.ServeHTTP(rr, httptest.NewRequest("GET", "http://relay/sessions", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("want 200, got %d (%s)", rr.Code, rr.Body.String())
	}
	if tok := <-gotTok; tok != "relay-secret" {
		t.Fatalf("X-Relay-Token not injected: %q", tok)
	}
}
