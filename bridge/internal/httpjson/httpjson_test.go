package httpjson

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func TestErrorWritesShapeAndStatus(t *testing.T) {
	rec := httptest.NewRecorder()
	Error(rec, 409, "not_paired")

	if rec.Code != 409 {
		t.Fatalf("status = %d, want 409", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content-type = %q, want application/json", ct)
	}
	var body struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if body.Error != "not_paired" {
		t.Fatalf("error = %q, want not_paired", body.Error)
	}
}

func TestWriteEncodesArbitraryBody(t *testing.T) {
	rec := httptest.NewRecorder()
	Write(rec, 200, map[string]bool{"ok": true})

	var body struct {
		OK bool `json:"ok"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !body.OK {
		t.Fatal("expected ok=true")
	}
}
