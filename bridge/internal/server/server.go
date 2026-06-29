// Package server exposes the bridge's HTTP/WebSocket API. Every route except
// /pair requires a device bearer token (auth.Require); the public edge adds
// mTLS in front of all of it.
package server

import (
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
)

// Server holds the dependencies shared by all handlers.
type Server struct {
	cfg   config.Config
	cmux  *cmux.Client
	store *auth.Store
	hub   *hub
}

// New constructs a Server.
func New(cfg config.Config, c *cmux.Client, s *auth.Store) *Server {
	return &Server{cfg: cfg, cmux: c, store: s, hub: newHub()}
}

// Handler returns the fully-wired HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	// Authenticated routes.
	mux.Handle("GET /sessions", auth.Require(s.store, http.HandlerFunc(s.handleSessions)))
	mux.Handle("GET /events", auth.Require(s.store, http.HandlerFunc(s.handleEvents)))
	return mux
}
