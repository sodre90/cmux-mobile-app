package relay

import (
	"bufio"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestIsUpgrade(t *testing.T) {
	cases := []struct {
		name       string
		connection string
		upgrade    string
		want       bool
	}{
		{"websocket", "Upgrade", "websocket", true},
		{"multi-token connection", "keep-alive, Upgrade", "websocket", true},
		{"case-insensitive", "upgrade", "websocket", true},
		{"plain GET", "keep-alive", "", false},
		{"connection only", "Upgrade", "", false},
		{"upgrade header only", "keep-alive", "websocket", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", "http://relay/terminal/abc", nil)
			req.Header.Set("Connection", c.connection)
			if c.upgrade != "" {
				req.Header.Set("Upgrade", c.upgrade)
			}
			if got := isUpgrade(req); got != c.want {
				t.Fatalf("isUpgrade=%t, want %t", got, c.want)
			}
		})
	}
}

func TestStatusRecorderCapturesStatus(t *testing.T) {
	rr := httptest.NewRecorder()
	rec := &statusRecorder{ResponseWriter: rr, status: http.StatusOK}
	rec.WriteHeader(http.StatusBadGateway)
	if rec.status != http.StatusBadGateway {
		t.Fatalf("status=%d, want 502", rec.status)
	}
	if rr.Code != http.StatusBadGateway {
		t.Fatalf("underlying not written: %d", rr.Code)
	}
	if got := rec.outcome(); got != "502" {
		t.Fatalf("outcome=%q, want 502", got)
	}
}

// hijackableRecorder is an httptest.ResponseRecorder that also satisfies
// http.Hijacker, so we can verify the wrapper delegates the upgrade hijack.
type hijackableRecorder struct {
	*httptest.ResponseRecorder
	hijackCalled bool
}

func (h *hijackableRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h.hijackCalled = true
	c1, _ := net.Pipe()
	return c1, bufio.NewReadWriter(bufio.NewReader(c1), bufio.NewWriter(c1)), nil
}

func TestStatusRecorderDelegatesHijack(t *testing.T) {
	// Compile-time: the wrapper must expose these or upgrade routes break.
	var _ http.Hijacker = (*statusRecorder)(nil)
	var _ http.Flusher = (*statusRecorder)(nil)

	base := &hijackableRecorder{ResponseRecorder: httptest.NewRecorder()}
	rec := &statusRecorder{ResponseWriter: base, status: http.StatusOK}
	conn, _, err := rec.Hijack()
	if err != nil {
		t.Fatalf("Hijack errored: %v", err)
	}
	_ = conn.Close()
	if !base.hijackCalled {
		t.Fatal("Hijack did not delegate to the underlying writer")
	}
	if !rec.hijacked {
		t.Fatal("hijacked flag not set")
	}
	if got := rec.outcome(); got != "101 upgraded" {
		t.Fatalf("outcome=%q, want \"101 upgraded\"", got)
	}
}

func TestStatusRecorderHijackErrsWhenUnsupported(t *testing.T) {
	// httptest.NewRecorder() is not a Hijacker; delegation must surface an error
	// rather than panic.
	rec := &statusRecorder{ResponseWriter: httptest.NewRecorder(), status: http.StatusOK}
	if _, _, err := rec.Hijack(); err == nil {
		t.Fatal("expected error hijacking a non-Hijacker writer")
	}
}
