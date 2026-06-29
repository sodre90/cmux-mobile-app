// Package testutil provides helpers for testing the bridge without a real cmux.
package testutil

import (
	"os"
	"path/filepath"
	"testing"
)

// WriteFakeCmux writes script to an executable file named "cmux" in a temp dir
// and returns its absolute path. Use the returned path as cmux.Client.Bin so
// the bridge invokes the fake instead of the real cmux CLI.
func WriteFakeCmux(t *testing.T, script string) string {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "cmux")
	if err := os.WriteFile(p, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return p
}
