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
)

// Server holds the dependencies shared by all handlers.
type Server struct {
	cfg          config.Config
	cmux         *cmux.Client
	store        *auth.Store
	hub          *hub
	terminalPoll time.Duration // how often WS /terminal re-replays for output
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

// Handler returns the fully-wired HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	// Authenticated routes.
	mux.Handle("GET /sessions", auth.Require(s.store, http.HandlerFunc(s.handleSessions)))
	mux.Handle("GET /events", auth.Require(s.store, http.HandlerFunc(s.handleEvents)))
	mux.Handle("GET /terminal/{id}", auth.Require(s.store, http.HandlerFunc(s.handleTerminal)))
	return mux
}
