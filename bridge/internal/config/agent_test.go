package config

import (
	"os"
	"path/filepath"
	"strings"
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
	if err := os.WriteFile(path, []byte("relay_token=\"secret\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.RelayToken != "secret" {
		t.Fatalf("relay fields not parsed: %+v", cfg)
	}
	if cfg.CACert == "" || cfg.CAKey == "" {
		t.Fatal("CACert/CAKey should default, not be empty")
	}
}

func TestLoadAgentDefaultsIdentityAndSessionStorePaths(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.IdentityKey == "" || strings.Contains(cfg.IdentityKey, "~") {
		t.Fatalf("IdentityKey default not expanded: %q", cfg.IdentityKey)
	}
	if cfg.SessionStore == "" || strings.Contains(cfg.SessionStore, "~") {
		t.Fatalf("SessionStore default not expanded: %q", cfg.SessionStore)
	}
	if !strings.HasSuffix(cfg.IdentityKey, "identity.key") {
		t.Fatalf("IdentityKey = %q", cfg.IdentityKey)
	}
	if !strings.HasSuffix(cfg.SessionStore, "sessions.db") {
		t.Fatalf("SessionStore = %q", cfg.SessionStore)
	}
}

func TestLoadAgentParsesIdentityAndSessionStorePaths(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
identity_key   = "/c/identity.key"
session_store  = "/c/sessions.json"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.IdentityKey != "/c/identity.key" {
		t.Fatalf("IdentityKey = %q", cfg.IdentityKey)
	}
	if cfg.SessionStore != "/c/sessions.json" {
		t.Fatalf("SessionStore = %q", cfg.SessionStore)
	}
}

func TestLoadAgentDefaultsDirectAuthStorePath(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DirectListen != "" {
		t.Fatalf("DirectListen default = %q, want empty (direct mode off by default)", cfg.DirectListen)
	}
	if cfg.DirectAuthStore == "" || strings.Contains(cfg.DirectAuthStore, "~") {
		t.Fatalf("DirectAuthStore default not expanded: %q", cfg.DirectAuthStore)
	}
	if !strings.HasSuffix(cfg.DirectAuthStore, "direct-auth.db") {
		t.Fatalf("DirectAuthStore = %q", cfg.DirectAuthStore)
	}
}

func TestLoadAgentParsesDirectFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
direct_listen     = ":8443"
direct_auth_store = "/c/direct-auth.db"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DirectListen != ":8443" {
		t.Fatalf("DirectListen = %q", cfg.DirectListen)
	}
	if cfg.DirectAuthStore != "/c/direct-auth.db" {
		t.Fatalf("DirectAuthStore = %q", cfg.DirectAuthStore)
	}
}

func TestLoadAgentDefaultsFCMFieldsEmpty(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.FCMProjectID != "" || cfg.FCMCredentials != "" {
		t.Fatalf("FCM fields should default empty (push disabled), got project=%q credentials=%q", cfg.FCMProjectID, cfg.FCMCredentials)
	}
}

func TestLoadAgentParsesFCMFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
fcm_project_id  = "my-project"
fcm_credentials = "/c/fcm-key.json"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.FCMProjectID != "my-project" {
		t.Fatalf("FCMProjectID = %q", cfg.FCMProjectID)
	}
	if cfg.FCMCredentials != "/c/fcm-key.json" {
		t.Fatalf("FCMCredentials = %q", cfg.FCMCredentials)
	}
}

func TestLoadAgentExpandsFCMCredentialsHome(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	if err := os.WriteFile(path, []byte(`fcm_credentials = "~/fcm-key.json"`+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(cfg.FCMCredentials, "~") {
		t.Fatalf("FCMCredentials not expanded: %q", cfg.FCMCredentials)
	}
	if !strings.HasSuffix(cfg.FCMCredentials, "fcm-key.json") {
		t.Fatalf("FCMCredentials = %q", cfg.FCMCredentials)
	}
}
