package relay

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"time"
)

// logProxy wraps the app-facing proxy with a concise per-request access log:
// method, path, client CN, whether the request asked to upgrade (WebSocket),
// and the outcome — the captured status code, or "101 upgraded" when the proxy
// hijacked the connection for a successful WebSocket upgrade. For long-lived
// streams (/events, /terminal/{id}) the line lands when the stream closes, so
// the duration doubles as the stream's lifetime. This is the relay's window
// into per-route behaviour (e.g. why /terminal/{id} fails while /events works).
func (r *Relay) logProxy(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(rec, req)
		log.Printf("relay: %s %s cn=%q upgrade=%t -> %s (%s)",
			req.Method, req.URL.Path, r.clientCN(req), isUpgrade(req),
			rec.outcome(), time.Since(start).Round(time.Millisecond))
	})
}

// isUpgrade reports whether the request is a protocol-upgrade (WebSocket)
// request: a "Connection: Upgrade" token plus a non-empty Upgrade header.
func isUpgrade(req *http.Request) bool {
	return headerHasToken(req.Header.Get("Connection"), "upgrade") &&
		req.Header.Get("Upgrade") != ""
}

func headerHasToken(header, token string) bool {
	for _, part := range strings.Split(header, ",") {
		if strings.EqualFold(strings.TrimSpace(part), token) {
			return true
		}
	}
	return false
}

// statusRecorder captures the response status while delegating Hijacker and
// Flusher (and exposing Unwrap) so the proxy's WebSocket upgrade and streaming
// responses keep working. A plain wrapper that hid Hijacker would break every
// upgrade route, so this passthrough is load-bearing, not cosmetic.
type statusRecorder struct {
	http.ResponseWriter
	status   int
	hijacked bool
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

// Hijack delegates to the underlying writer (a successful WebSocket upgrade
// hijacks the connection instead of writing a status, so this call IS the
// "101 upgraded" signal).
func (s *statusRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := s.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("relay: underlying ResponseWriter is not a Hijacker")
	}
	s.hijacked = true
	return h.Hijack()
}

func (s *statusRecorder) Flush() {
	if f, ok := s.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// Unwrap lets http.ResponseController reach the real writer (e.g. for upgrade
// read/write deadlines) per the Go 1.20+ wrapper convention.
func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

func (s *statusRecorder) outcome() string {
	if s.hijacked {
		return "101 upgraded"
	}
	return fmt.Sprintf("%d", s.status)
}
