package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSessionStreamRoundTrip(t *testing.T) {
	// Relay side: accept the tunnel, open a stream, write "ping".
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sess, err := Accept(w, r)
		if err != nil {
			return
		}
		st, err := sess.Open()
		if err != nil {
			return
		}
		_, _ = st.Write([]byte("ping"))
		_ = st.Close()
	}))
	defer srv.Close()

	u := "ws" + strings.TrimPrefix(srv.URL, "http")
	sess, err := Dial(context.Background(), u, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()

	st, err := sess.Accept()
	if err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(st, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "ping" {
		t.Fatalf("got %q", buf)
	}
}

// A failed dial must name every address that was tried, so a dual-stack
// failure can't be misread as a fault specific to the one family net.Dialer
// happens to report.
func TestDialErrorNamesEveryAddressTried(t *testing.T) {
	// A port that is listening and then closed: connect fails fast, with no
	// DNS involved, so the attempt list is exactly one known address.
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	_ = l.Close()

	_, err = Dial(context.Background(), "ws://"+addr, nil, nil)
	if err == nil {
		t.Fatal("want dial error against a closed port")
	}
	if !strings.Contains(err.Error(), "tried 1 address(es)") {
		t.Errorf("error should report the attempt count, got: %v", err)
	}
	if !strings.Contains(err.Error(), addr) {
		t.Errorf("error should name %s, got: %v", addr, err)
	}
}

// Annotating must preserve the wrapped error so callers can still match on it.
func TestDialAttemptsAnnotatePreservesCause(t *testing.T) {
	cause := errors.New("boom")

	var none dialAttempts
	if got := none.annotate(cause); got != cause {
		t.Errorf("no recorded attempts should pass the error through unchanged, got %v", got)
	}

	var two dialAttempts
	two.addrs = []string{"[2a01::1]:443", "192.168.1.165:443"}
	got := two.annotate(cause)
	if !errors.Is(got, cause) {
		t.Errorf("annotated error must still unwrap to the cause, got %v", got)
	}
	for _, want := range []string{"tried 2 address(es)", "[2a01::1]:443", "192.168.1.165:443"} {
		if !strings.Contains(got.Error(), want) {
			t.Errorf("annotated error missing %q: %v", want, got)
		}
	}
}
