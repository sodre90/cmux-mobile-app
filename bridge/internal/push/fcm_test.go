package push

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSendBuildsHTTPv1Request(t *testing.T) {
	var gotPath, gotAuth string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotAuth = r.Header.Get("Authorization")
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"name":"ok"}`))
	}))
	defer srv.Close()

	s := &Sender{
		ProjectID: "proj-123",
		BaseURL:   srv.URL,
		Token:     func(context.Context) (string, error) { return "test-oauth", nil },
	}
	err := s.Send(context.Background(), "fcmtok", "Agent needs your attention", "Run rm -rf?",
		map[string]string{"feed_id": "F1"})
	if err != nil {
		t.Fatal(err)
	}

	if gotPath != "/v1/projects/proj-123/messages:send" {
		t.Fatalf("path wrong: %q", gotPath)
	}
	if gotAuth != "Bearer test-oauth" {
		t.Fatalf("auth wrong: %q", gotAuth)
	}
	msg, _ := gotBody["message"].(map[string]any)
	if msg["token"] != "fcmtok" {
		t.Fatalf("token wrong: %+v", msg)
	}
	android, _ := msg["android"].(map[string]any)
	if android["priority"] != "high" {
		t.Fatalf("priority wrong: %+v", android)
	}
	data, _ := msg["data"].(map[string]any)
	if data["feed_id"] != "F1" || data["title"] != "Agent needs your attention" || data["body"] != "Run rm -rf?" {
		t.Fatalf("data wrong: %+v", data)
	}
}

func TestSendNon2xxIsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer srv.Close()
	s := &Sender{
		ProjectID: "p",
		BaseURL:   srv.URL,
		Token:     func(context.Context) (string, error) { return "x", nil },
	}
	if err := s.Send(context.Background(), "t", "a", "b", nil); err == nil {
		t.Fatal("expected error on non-2xx")
	}
}
