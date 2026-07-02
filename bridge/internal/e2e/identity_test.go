package e2e

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestLoadOrCreateIdentityPersistsAcrossCalls(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.key")

	first, err := LoadOrCreateIdentity(path)
	if err != nil {
		t.Fatalf("first LoadOrCreateIdentity: %v", err)
	}
	second, err := LoadOrCreateIdentity(path)
	if err != nil {
		t.Fatalf("second LoadOrCreateIdentity: %v", err)
	}
	if !bytes.Equal(first.PublicKey().Bytes(), second.PublicKey().Bytes()) {
		t.Fatal("expected same public key across LoadOrCreateIdentity calls")
	}
}

func TestLoadOrCreateIdentityFilePermissions(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.key")

	if _, err := LoadOrCreateIdentity(path); err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("expected 0600 permissions, got %o", perm)
	}
}

func TestLoadOrCreateIdentityRejectsCorruptFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.key")
	if err := os.WriteFile(path, []byte("not a valid key"), 0o600); err != nil {
		t.Fatalf("write corrupt file: %v", err)
	}
	if _, err := LoadOrCreateIdentity(path); err == nil {
		t.Fatal("expected error loading corrupt identity file, got nil")
	}
}
