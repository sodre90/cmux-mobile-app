// Package server exposes the bridge's HTTP/WebSocket API. Every route except
// /pair requires a device bearer token (auth.Require); the public edge adds
// mTLS in front of all of it.
package server

import (
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
)

// Server holds the dependencies shared by all handlers.
type Server struct {
	cfg          config.Config
	cmux         *cmux.Client
	store        *auth.Store
	hub          *hub
	terminalPoll time.Duration // how often WS /terminal re-replays for output
	// sessions is nil unless SetSessions is called (only by runAgent's
	// production wiring). Nil means the plaintext code path every existing
	// test exercises; non-nil enables the opt-in e2e encryption layer.
	sessions *e2e.Store
}

// New constructs a Server.
func New(cfg config.Config, c *cmux.Client, s *auth.Store) *Server {
	return &Server{
		cfg:          cfg,
		cmux:         c,
		store:        s,
		hub:          newHub(),
		terminalPoll: 250 * time.Millisecond,
	}
}

// Handler returns the fully-wired HTTP handler (device-bearer auth on every
// route; the public edge adds mTLS in front).
func (s *Server) Handler() http.Handler {
	return s.routes(s.authWrap)
}
