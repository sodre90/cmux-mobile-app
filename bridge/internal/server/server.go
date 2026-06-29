// Package server exposes the bridge's HTTP/WebSocket API. Every route except
// /pair requires a device bearer token (auth.Require); the public edge adds
// mTLS in front of all of it.
package server

import (
	"context"
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
)

// Pusher delivers an agent-attention notification to a single device token.
// push.Sender satisfies this interface.
type Pusher interface {
	Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
}

// Server holds the dependencies shared by all handlers.
type Server struct {
	cfg          config.Config
	cmux         *cmux.Client
	store        *auth.Store
	hub          *hub
	push         Pusher
	terminalPoll time.Duration // how often WS /terminal re-replays for output
}

// SetPusher wires an optional push backend used when agent attention is needed.
func (s *Server) SetPusher(p Pusher) { s.push = p }

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
	return s.routes(s.authWrap, true)
}
