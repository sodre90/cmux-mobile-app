package auth

import (
	"context"
	"errors"
	"log"
	"net/http"
	"strings"

	"github.com/sodre90/cmux-bridge/internal/httpjson"
)

type ctxKey int

const deviceKey ctxKey = 0

// Require wraps next so that only requests bearing a valid device token reach
// it. The token is read from the "Authorization: Bearer <token>" header. A
// token that verifies against no device gets a 401; a genuine store failure
// (the DB itself misbehaving) gets a 500 instead -- an infra error must never
// masquerade as an authentication failure. On success the resolved Device is
// attached to the request context (see DeviceFromContext).
func Require(s *Store, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := BearerToken(r)
		dev, err := s.Verify(tok)
		if err != nil {
			if errors.Is(err, ErrNotFound) {
				httpjson.Error(w, http.StatusUnauthorized, "unauthorized")
				return
			}
			log.Printf("auth: verify: %v", err)
			httpjson.Error(w, http.StatusInternalServerError, "internal_error")
			return
		}
		ctx := context.WithValue(r.Context(), deviceKey, dev)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// DeviceFromContext returns the Device attached by Require, if any.
func DeviceFromContext(ctx context.Context) (Device, bool) {
	d, ok := ctx.Value(deviceKey).(Device)
	return d, ok
}

// BearerToken extracts the raw "Authorization: Bearer <token>" value from a
// request, or "" if absent/malformed. Exported so handlers that already went
// through Require (and so already have a Device in context) can recover the
// original raw token for calls that key off it directly, like SetFCMToken.
func BearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		return strings.TrimSpace(h[len(prefix):])
	}
	return ""
}
