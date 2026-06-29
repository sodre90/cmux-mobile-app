package tunnel

import (
	"context"
	"io"
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
