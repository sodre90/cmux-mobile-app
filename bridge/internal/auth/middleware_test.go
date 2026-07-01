package auth

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func protected(t *testing.T) (*Store, http.Handler, string) {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "d.db"))
	if err != nil {
		t.Fatal(err)
	}
	tenant, _ := s.CreateTenant()
	tok, _ := s.Issue(tenant, "phone")
	h := Require(s, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dev, ok := DeviceFromContext(r.Context())
		if !ok || dev.Name != "phone" {
			t.Errorf("handler did not see device in context: %+v ok=%v", dev, ok)
		}
		w.WriteHeader(http.StatusOK)
	}))
	return s, h, tok
}

func TestRequireNoHeader401(t *testing.T) {
	_, h, _ := protected(t)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/x", nil))
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", rr.Code)
	}
}

func TestRequireBadToken401(t *testing.T) {
	_, h, _ := protected(t)
	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Authorization", "Bearer wrong")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", rr.Code)
	}
}

func TestRequireGoodToken200(t *testing.T) {
	_, h, tok := protected(t)
	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("want 200, got %d", rr.Code)
	}
}
