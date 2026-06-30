package server

import (
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// RequireRelayToken gates a handler behind a static shared token sent by the
// relay as X-Relay-Token. On the agent this replaces device-bearer auth: the
// relay is the device gatekeeper, and the only reachable path to the agent is
// the mutually-authenticated tunnel.
func RequireRelayToken(token string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if token == "" || r.Header.Get("X-Relay-Token") != token {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}
		next.ServeHTTP(w, r)
	})
}

// routes wires the API onto a mux using wrap as the per-route middleware. When
// includeRegister is false (agent/trusted mode), /devices/register is omitted —
// the relay owns device registration.
func (s *Server) routes(wrap func(http.Handler) http.Handler, includeRegister bool) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /sessions", wrap(http.HandlerFunc(s.handleSessions)))
	mux.Handle("GET /events", wrap(http.HandlerFunc(s.handleEvents)))
	mux.Handle("GET /terminal/{id}", wrap(http.HandlerFunc(s.handleTerminal)))
	mux.Handle("GET /feed/pending", wrap(http.HandlerFunc(s.handleFeedPending)))
	mux.Handle("POST /feed/{id}/reply", wrap(http.HandlerFunc(s.handleFeedReply)))
	if includeRegister {
		mux.Handle("POST /devices/register", wrap(http.HandlerFunc(s.handleDeviceRegister)))
	}
	return mux
}

// TrustedHandler is the handler the Mac agent serves over the relay tunnel:
// device-bearer auth is replaced by the relay-token check and /devices/register
// is dropped (the relay handles it).
func (s *Server) TrustedHandler(relayToken string) http.Handler {
	return s.routes(func(h http.Handler) http.Handler {
		return RequireRelayToken(relayToken, h)
	}, false)
}

// authWrap is the production device-bearer middleware used by Handler.
func (s *Server) authWrap(h http.Handler) http.Handler { return auth.Require(s.store, h) }
