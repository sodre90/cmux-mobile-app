package server

import (
	"crypto/subtle"
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// RequireRelayToken gates a handler behind a static shared token sent by the
// relay as X-Relay-Token. On the agent this replaces device-bearer auth: the
// relay is the device gatekeeper, and the only reachable path to the agent is
// the mutually-authenticated tunnel. Constant-time compare so the token can't
// be recovered by timing (mirrors relay.requireEdge).
func RequireRelayToken(token string, next http.Handler) http.Handler {
	want := []byte(token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if token == "" || subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Relay-Token")), want) != 1 {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}
		next.ServeHTTP(w, r)
	})
}

// routes wires the API onto a mux using wrap as the per-route middleware.
// Device registration is handled exclusively by the relay (see
// internal/relay.handleRegister), so it is never mounted here.
func (s *Server) routes(wrap func(http.Handler) http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /sessions", wrap(http.HandlerFunc(s.handleSessions)))
	mux.Handle("GET /events", wrap(http.HandlerFunc(s.handleEvents)))
	mux.Handle("GET /terminal/{id}", wrap(http.HandlerFunc(s.handleTerminal)))
	mux.Handle("GET /feed/pending", wrap(http.HandlerFunc(s.handleFeedPending)))
	mux.Handle("POST /feed/{id}/reply", wrap(http.HandlerFunc(s.handleFeedReply)))
	mux.Handle("POST /sessions/{id}/rename", wrap(http.HandlerFunc(s.handleRenameWorkspace)))
	mux.Handle("POST /sessions/{id}/yolo-mode", wrap(http.HandlerFunc(s.handleSetYoloMode)))
	return mux
}

// TrustedHandler is the handler the Mac agent serves over the relay tunnel:
// device-bearer auth is replaced by the relay-token check, and the opt-in
// e2e encryption layer (SetSessions) wraps the whole route set.
func (s *Server) TrustedHandler(relayToken string) http.Handler {
	base := s.routes(func(h http.Handler) http.Handler {
		return RequireRelayToken(relayToken, h)
	})
	return s.encryptionMiddleware(base)
}

// authWrap is the production device-bearer middleware used by Handler.
func (s *Server) authWrap(h http.Handler) http.Handler { return auth.Require(s.store, h) }
