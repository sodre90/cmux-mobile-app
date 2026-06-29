package cli

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConfigPath(t *testing.T) {
	p := filepath.ToSlash(ConfigPath("cmux-relay", "config.toml"))
	if !strings.HasSuffix(p, ".config/cmux-relay/config.toml") {
		t.Fatalf("ConfigPath = %q", p)
	}
}

func TestLoadStoreOpensConfiguredStore(t *testing.T) {
	dir := t.TempDir()
	storePath := filepath.Join(dir, "devices.json")
	cfgPath := filepath.Join(dir, "config.toml")
	if err := os.WriteFile(cfgPath, []byte("token_store = \""+storePath+"\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, store, err := LoadStore(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	if store == nil {
		t.Fatal("store should be non-nil")
	}
	if cfg.TokenStore != storePath {
		t.Fatalf("TokenStore = %q want %q", cfg.TokenStore, storePath)
	}
	// Issuing a token should persist to the configured path.
	if _, err := store.Issue("phone"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(storePath); err != nil {
		t.Fatalf("store file not created: %v", err)
	}
}
