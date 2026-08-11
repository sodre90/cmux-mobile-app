package server

import (
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/devices"
)

// injectDeviceID copies the bearer-token-verified Device's TokenHash (set by
// auth.Require, which must run before this) into the X-Device-ID header,
// overwriting any client-supplied value. This is what lets the existing,
// unmodified encryptionMiddleware -- which trusts X-Device-ID because only
// the relay's proxy Director used to be able to set it -- work safely when
// there's no relay in front: a real device can prove its own bearer token
// but must never be able to pick which device's shared secret its request
// gets decrypted against.
func injectDeviceID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dev, _ := auth.DeviceFromContext(r.Context())
		r.Header.Set("X-Device-ID", dev.TokenHash)
		next.ServeHTTP(w, r)
	})
}

// DirectHandler is the authenticated route set served directly over
// Tailscale, with no relay in the path: per-device bearer-token auth
// (auth.Require, keyed on s.store) replaces the relay-token check, and
// injectDeviceID lets the existing encryptionMiddleware run unmodified.
// Order matters: auth.Require must resolve the token (needs only the
// Authorization header) before injectDeviceID can read it from context,
// and injectDeviceID must set X-Device-ID before encryptionMiddleware reads
// it to decrypt the body.
func (s *Server) DirectHandler() http.Handler {
	wrap := func(h http.Handler) http.Handler {
		return auth.Require(s.store, injectDeviceID(s.encryptionMiddleware(h)))
	}
	mux := s.routes(wrap).(*http.ServeMux)
	mux.Handle("POST /devices/register", wrap(http.HandlerFunc(s.handleRegisterDevice)))
	mux.Handle("POST /devices/test-push", wrap(http.HandlerFunc(s.handleTestPushDevice)))
	// Bearer auth only, deliberately skipping wrap's encryptionMiddleware:
	// this route carries no cmux content, and it is terminated identically in
	// relay mode, where no e2e layer exists to be behind. Do not "fix" it to
	// use wrap -- a handler behind that middleware cannot delete a device the
	// response is about to be encrypted for (cmux-app-f5y).
	devices.MountSelfRevoke(mux, s.store, func(h http.Handler) http.Handler {
		return auth.Require(s.store, h)
	})
	return mux
}
