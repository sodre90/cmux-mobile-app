package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadAgentParses(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
relay_url   = "wss://cmux.example.com/agent/tunnel"
client_cert = "/c/agent.crt"
client_key  = "/c/agent.key"
ca_cert     = "/c/ca.crt"
relay_token = "secret"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.RelayURL != "wss://cmux.example.com/agent/tunnel" {
		t.Fatalf("relay_url = %q", cfg.RelayURL)
	}
	if cfg.RelayToken != "secret" || cfg.ClientCert != "/c/agent.crt" {
		t.Fatalf("unexpected cfg: %+v", cfg)
	}
	if cfg.CmuxBin != "cmux" {
		t.Fatalf("CmuxBin default = %q, want cmux", cfg.CmuxBin)
	}
}

func TestLoadAgentMissingFileDefaults(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.CmuxBin != "cmux" {
		t.Fatalf("CmuxBin default = %q", cfg.CmuxBin)
	}
}

func TestConfigRelayFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "relay.toml")
	if err := os.WriteFile(path, []byte("agent_cn=\"mac-agent\"\nrelay_token=\"secret\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AgentCN != "mac-agent" || cfg.RelayToken != "secret" {
		t.Fatalf("relay fields not parsed: %+v", cfg)
	}
}
