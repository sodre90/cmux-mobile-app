package relay

import (
	"context"
	"errors"
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
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			if errors.Is(err, ErrAgentOffline) {
				writeJSONErr(w, http.StatusServiceUnavailable, "agent_offline")
				return
			}
			writeJSONErr(w, http.StatusBadGateway, "agent_error")
		},
	}
}
