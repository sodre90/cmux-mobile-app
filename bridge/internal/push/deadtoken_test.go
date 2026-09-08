package push

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The real shapes FCM HTTP v1 returns, from
// https://firebase.google.com/docs/cloud-messaging/manage-tokens.
const (
	unregisteredBody = `{"error":{"code":404,"message":"Requested entity was not found.",` +
		`"status":"NOT_FOUND","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",` +
		`"errorCode":"UNREGISTERED"}]}}`
	invalidArgumentBody = `{"error":{"code":400,"message":"The registration token is not a valid FCM registration token",` +
		`"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",` +
		`"errorCode":"INVALID_ARGUMENT"}]}}`
	unavailableBody = `{"error":{"code":503,"message":"The service is unavailable.",` +
		`"status":"UNAVAILABLE","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",` +
		`"errorCode":"UNAVAILABLE"}]}}`
)

func senderAnswering(t *testing.T, status int, body string) *Sender {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return &Sender{
		ProjectID: "p",
		BaseURL:   srv.URL,
		Token:     func(context.Context) (string, error) { return "tok", nil },
	}
}

// cmux-app-6u7 assumed FCM answers 2xx for a decommissioned token. It does
// not -- it answers 404 UNREGISTERED. What was missing was any use of that.
func TestSendReportsAnUnregisteredTokenAsDead(t *testing.T) {
	s := senderAnswering(t, http.StatusNotFound, unregisteredBody)

	err := s.Send(context.Background(), "dead-token", "", "", nil)

	if !errors.Is(err, ErrTokenDead) {
		t.Fatalf("err = %v, want it to report the token as dead", err)
	}
	if !strings.Contains(err.Error(), "UNREGISTERED") {
		t.Errorf("the error should name the FCM code so it can be grepped: %v", err)
	}
}

// A relay outage must never be read as "every device uninstalled the app".
func TestSendDoesNotReportATransientFailureAsDead(t *testing.T) {
	s := senderAnswering(t, http.StatusServiceUnavailable, unavailableBody)

	err := s.Send(context.Background(), "live-token", "", "", nil)

	if err == nil {
		t.Fatal("a 503 is still a failure")
	}
	if errors.Is(err, ErrTokenDead) {
		t.Fatalf("a transient failure must not condemn the token: %v", err)
	}
}

// INVALID_ARGUMENT means a dead registration OR a message this sender built
// wrong. Since it builds every message itself, treating it as fatal would
// turn one payload bug into every token in the store being dropped at once.
func TestSendReportsInvalidArgumentWithoutCondemningTheToken(t *testing.T) {
	s := senderAnswering(t, http.StatusBadRequest, invalidArgumentBody)

	err := s.Send(context.Background(), "some-token", "", "", nil)

	if err == nil {
		t.Fatal("a 400 is still a failure")
	}
	if errors.Is(err, ErrTokenDead) {
		t.Fatal("INVALID_ARGUMENT is ambiguous between a dead token and our own bad payload")
	}
	if !strings.Contains(err.Error(), "INVALID_ARGUMENT") {
		t.Errorf("the error should still name the FCM code: %v", err)
	}
}

func TestSendFallsBackToTheStatusWhenTheBodyExplainsNothing(t *testing.T) {
	s := senderAnswering(t, http.StatusInternalServerError, "not json at all")

	err := s.Send(context.Background(), "some-token", "", "", nil)

	if err == nil || errors.Is(err, ErrTokenDead) {
		t.Fatalf("want a plain failure, got %v", err)
	}
	if !strings.Contains(err.Error(), "500") {
		t.Errorf("the error should still carry the status: %v", err)
	}
}

type fakeTokenStore struct {
	cleared []string
	rows    int
	err     error
}

func (f *fakeTokenStore) ClearFCMToken(fcm string) (int, error) {
	if f.err != nil {
		return 0, f.err
	}
	f.cleared = append(f.cleared, fcm)
	return f.rows, nil
}

func TestDropDeadTokenClearsOnlyADeadToken(t *testing.T) {
	store := &fakeTokenStore{rows: 2}

	if !DropDeadToken(store, "dead", ErrTokenDead) {
		t.Fatal("a dead token should be dropped")
	}
	if DropDeadToken(store, "live", errors.New("fcm status 503")) {
		t.Fatal("a transient failure must not drop the token")
	}
	if len(store.cleared) != 1 || store.cleared[0] != "dead" {
		t.Fatalf("cleared %v, want just the dead token", store.cleared)
	}
}

func TestDropDeadTokenReportsFalseWhenTheStoreFails(t *testing.T) {
	store := &fakeTokenStore{err: errors.New("db is gone")}

	if DropDeadToken(store, "dead", ErrTokenDead) {
		t.Fatal("nothing was dropped, so it must not report that it was")
	}
}

func TestDropDeadTokenToleratesNoStore(t *testing.T) {
	if DropDeadToken(nil, "dead", ErrTokenDead) {
		t.Fatal("with no store there is nothing to drop")
	}
}
