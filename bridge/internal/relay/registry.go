// Package relay is the home-server rendezvous: it accepts the Mac agent's
// outbound yamux tunnel and reverse-proxies authenticated app requests over it,
// one yamux stream per request. It owns device auth, pairing, and FCM push.
package relay

import (
	"sync"

	"github.com/hashicorp/yamux"
)

// Registry holds the single active agent tunnel session (v1: one Mac). A new
// session replaces and closes the previous one.
type Registry struct {
	mu   sync.Mutex
	sess *yamux.Session
	stop func() // cancels work bound to sess (e.g. the push monitor)
}

func NewRegistry() *Registry { return &Registry{} }

// Set installs sess as current, closing any prior session and calling its stop
// func. stop may be nil.
func (r *Registry) Set(sess *yamux.Session, stop func()) {
	r.mu.Lock()
	old, oldStop := r.sess, r.stop
	r.sess, r.stop = sess, stop
	r.mu.Unlock()

	if oldStop != nil {
		oldStop()
	}
	if old != nil {
		_ = old.Close()
	}
}

// Current returns the active session, or nil when none is connected or it has
// closed.
func (r *Registry) Current() *yamux.Session {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.sess != nil && r.sess.IsClosed() {
		return nil
	}
	return r.sess
}

// Clear removes sess if it is still the current session.
func (r *Registry) Clear(sess *yamux.Session) {
	r.mu.Lock()
	var stop func()
	if r.sess == sess {
		stop, r.stop, r.sess = r.stop, nil, nil
	}
	r.mu.Unlock()
	if stop != nil {
		stop()
	}
}
