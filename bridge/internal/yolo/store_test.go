package yolo

import (
	"bytes"
	"log"
	"os"
	"path/filepath"
	"strings"
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

// captureLog redirects the standard logger to a buffer for the duration of
// the test, restoring it on cleanup.
func captureLog(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	prev := log.Writer()
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(prev) })
	return &buf
}

// TestOpenRecoversFromCorruptFileLoudly is internal/e2e's structural twin
// acceptance test for "loud corruption is not silent at every call": a
// genuinely corrupt (not valid SQLite) store file must not block agent
// startup, must be moved aside intact for forensics, must be logged
// loudly, and the resulting fresh store must be immediately usable.
func TestOpenRecoversFromCorruptFileLoudly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "yolo.json")
	garbage := []byte("not a database")
	if err := os.WriteFile(path, garbage, 0o600); err != nil {
		t.Fatalf("write garbage file: %v", err)
	}

	logBuf := captureLog(t)

	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open on corrupt file: %v", err)
	}
	if s == nil {
		t.Fatal("expected a non-nil *Store even after recovering from a corrupt file")
	}

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read recovered path: %v", err)
	}
	if bytes.Equal(got, garbage) {
		t.Fatal("expected the corrupt garbage to no longer be at the original path")
	}

	matches, err := filepath.Glob(path + ".corrupt.*")
	if err != nil {
		t.Fatalf("glob corrupt sibling: %v", err)
	}
	if len(matches) != 1 {
		t.Fatalf("expected exactly one <path>.corrupt.* sibling, got %v", matches)
	}
	corruptContent, err := os.ReadFile(matches[0])
	if err != nil {
		t.Fatalf("read corrupt sibling: %v", err)
	}
	if !bytes.Equal(corruptContent, garbage) {
		t.Fatalf("corrupt sibling content = %q, want original garbage %q", corruptContent, garbage)
	}

	logged := logBuf.String()
	if logged == "" {
		t.Fatal("expected non-empty log output for a corrupt store recovery")
	}
	if !strings.Contains(logged, path) {
		t.Fatalf("expected log output to name the original path %q, got: %s", path, logged)
	}
	if !strings.Contains(strings.ToLower(logged), "corrupt") {
		t.Fatalf("expected log output to mention corruption, got: %s", logged)
	}

	if err := s.SetMode("ws1", Bypass); err != nil {
		t.Fatalf("expected the recovered store to be immediately usable, SetMode failed: %v", err)
	}
}
