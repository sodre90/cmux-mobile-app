package relay

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
)

// ErrAgentOffline is returned by the proxy transport when no agent session is
// registered.
var ErrAgentOffline = errors.New("agent offline")

func writeJSONErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(`{"error":"` + msg + `"}`))
}

// newProxy builds the reverse proxy that forwards an app request over a fresh
// yamux stream to the agent, injecting the relay token. Offline (no session) is
// surfaced as 503 agent_offline; other transport failures as 502.
func newProxy(reg *Registry, relayToken string) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		// Surfaces internal proxy failures (upgrade/copy errors after headers are
		// sent, which ErrorHandler can no longer report) to the relay's log.
		ErrorLog: log.New(log.Writer(), "relay-proxy: ", log.Flags()),
		Director: func(req *http.Request) {
			req.URL.Scheme = "http"
			req.URL.Host = "agent" // ignored by the stream dialer below
			req.Header.Set("X-Relay-Token", relayToken)
		},
		Transport: &http.Transport{
			DialContext: func(_ context.Context, _, _ string) (net.Conn, error) {
				sess := reg.Current()
				if sess == nil {
					return nil, ErrAgentOffline
				}
				return sess.Open()
			},
		},
		ErrorHandler: func(w http.ResponseWriter, req *http.Request, err error) {
			if errors.Is(err, ErrAgentOffline) {
				log.Printf("relay: %s %s -> agent_offline", req.Method, req.URL.Path)
				writeJSONErr(w, http.StatusServiceUnavailable, "agent_offline")
				return
			}
			log.Printf("relay: %s %s -> agent_error: %v", req.Method, req.URL.Path, err)
			writeJSONErr(w, http.StatusBadGateway, "agent_error")
		},
	}
}
