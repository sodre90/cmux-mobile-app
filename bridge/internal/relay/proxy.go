package relay

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"net/http/httputil"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// ErrAgentOffline is returned by the proxy transport when the target
// tenant's agent has no active session.
var ErrAgentOffline = errors.New("agent offline")

func writeJSONErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(`{"error":"` + msg + `"}`))
}

// newProxy builds the reverse proxy that forwards an app request over a fresh
// yamux stream to the agent belonging to the authenticated device's tenant,
// injecting the relay token. A device can never reach another tenant's
// session: the dial target is resolved solely from auth.DeviceFromContext,
// never from "whichever session happens to be registered."
func newProxy(reg *Registry, relayToken string) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		ErrorLog: log.New(log.Writer(), "relay-proxy: ", log.Flags()),
		Director: func(req *http.Request) {
			req.URL.Scheme = "http"
			req.URL.Host = "agent" // ignored by the stream dialer below
			req.Header.Set("X-Relay-Token", relayToken)
		},
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				dev, ok := auth.DeviceFromContext(ctx)
				if !ok {
					return nil, ErrAgentOffline
				}
				sess := reg.Get(dev.TenantID)
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
