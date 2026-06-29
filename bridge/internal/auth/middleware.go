package auth

import (
	"context"
	"net/http"
	"strings"
)

type ctxKey int

const deviceKey ctxKey = 0

// Require wraps next so that only requests bearing a valid device token reach
// it. The token is read from the "Authorization: Bearer <token>" header. On
// failure it writes a 401 JSON body. On success the resolved Device is attached
// to the request context (see DeviceFromContext).
func Require(s *Store, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := bearerToken(r)
		dev, ok := s.Verify(tok)
		if !ok {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
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

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		return strings.TrimSpace(h[len(prefix):])
	}
	return ""
}
