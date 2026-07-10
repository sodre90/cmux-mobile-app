package yolo

import (
	"path/filepath"
	"testing"
)

// mustOpen opens a Store at path, failing the test on error.
func mustOpen(t *testing.T, path string) *Store {
	t.Helper()
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open(%q): %v", path, err)
	}
	return s
}

func TestSetModeAndMode(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "yolo.json"))

	if got := s.Mode("ws1"); got != "" {
		t.Fatalf("Mode on unset workspace = %q, want empty", got)
	}
	if err := s.SetMode("ws1", Bypass); err != nil {
		t.Fatalf("SetMode: %v", err)
	}
	if got := s.Mode("ws1"); got != Bypass {
		t.Fatalf("Mode after SetMode = %q, want %q", got, Bypass)
	}

	// Persists across a fresh Store over the same file.
	s2 := mustOpen(t, filepath.Join(dir, "yolo.json"))
	if got := s2.Mode("ws1"); got != Bypass {
		t.Fatalf("Mode from reopened store = %q, want %q", got, Bypass)
	}
}

func TestSetModeOffRemovesEntry(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "yolo.json"))
	if err := s.SetMode("ws1", Always); err != nil {
		t.Fatalf("SetMode: %v", err)
	}
	if err := s.SetMode("ws1", ""); err != nil {
		t.Fatalf("SetMode off: %v", err)
	}
	if got := s.Mode("ws1"); got != "" {
		t.Fatalf("Mode after turning off = %q, want empty", got)
	}
}

func TestValid(t *testing.T) {
	for _, mode := range []string{"", Always, All, Bypass} {
		if !Valid(mode) {
			t.Errorf("Valid(%q) = false, want true", mode)
		}
	}
	for _, mode := range []string{"once", "deny", "bogus"} {
		if Valid(mode) {
			t.Errorf("Valid(%q) = true, want false", mode)
		}
	}
}
