package config

import (
	"errors"
	"fmt"
	"io/fs"
	"os"

	"github.com/BurntSushi/toml"
)

// AgentConfig is the Mac agent's configuration. The agent dials the relay and
// serves the cmux handler over the tunnel; it holds no device secrets other
// than its own e2e identity key.
type AgentConfig struct {
	CmuxBin    string `toml:"cmux_bin"`
	RelayURL   string `toml:"relay_url"`
	ClientCert string `toml:"client_cert"`
	ClientKey  string `toml:"client_key"`
	CACert     string `toml:"ca_cert"`
	RelayToken string `toml:"relay_token"`
	// BootstrapURL is the relay's no-mTLS registration endpoint
	// (e.g. https://cmux.example.com:8444/tenants/register), used exactly
	// once, on first run, when ClientCert/ClientKey/CACert don't exist yet.
	BootstrapURL string `toml:"bootstrap_url"`
	// IdentityKey is the path to this agent's X25519 e2e identity private key
	// (internal/e2e.Identity), created on first use by `cmux-bridge
	// pair-device`.
	IdentityKey string `toml:"identity_key"`
	// SessionStore is the path to the JSON file holding this agent's paired
	// devices' e2e shared secrets and replay counters (internal/e2e.Store).
	SessionStore string `toml:"session_store"`
	// YoloStore is the path to the JSON file holding each workspace's opt-in
	// auto-reply mode for permission prompts (internal/yolo.Store).
	YoloStore string `toml:"yolo_store"`
	// DirectListen is the address the agent listens on for direct
	// (Tailscale) connections, e.g. ":8443". Empty (the default) disables
	// direct mode entirely — the agent behaves exactly as it does today,
	// relay-only.
	DirectListen string `toml:"direct_listen"`
	// DirectAuthStore is the path to direct mode's own local SQLite device
	// store (internal/auth.Store) — deliberately separate from any
	// relay-shaped state, since direct mode has exactly one implicit tenant
	// (this Mac).
	DirectAuthStore string `toml:"direct_auth_store"`
	// FCMProjectID is the Firebase project id for direct-mode push. Empty
	// disables it -- direct mode behaves exactly as it does today. Same
	// Firebase project as the relay's own fcm_project_id, configured
	// separately here since the agent has its own independent device store.
	FCMProjectID string `toml:"fcm_project_id"`
	// FCMCredentials is the path to a Google service-account JSON key for
	// direct-mode push. Empty disables it.
	FCMCredentials string `toml:"fcm_credentials"`
}

func agentDefaults() AgentConfig {
	return AgentConfig{
		CmuxBin:         "cmux",
		IdentityKey:     "~/.config/cmux-bridge/identity.key",
		SessionStore:    "~/.config/cmux-bridge/sessions.json",
		YoloStore:       "~/.config/cmux-bridge/yolo.json",
		DirectAuthStore: "~/.config/cmux-bridge/direct-auth.db",
	}
}

// LoadAgent reads the agent TOML at path. A missing file yields defaults.
func LoadAgent(path string) (AgentConfig, error) {
	cfg := agentDefaults()
	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := toml.Unmarshal(data, &cfg); err != nil {
			return cfg, fmt.Errorf("parse agent config %s: %w", path, err)
		}
	case errors.Is(err, fs.ErrNotExist):
		// Fall through with defaults.
	default:
		return cfg, fmt.Errorf("read agent config %s: %w", path, err)
	}
	if cfg.CmuxBin == "" {
		cfg.CmuxBin = "cmux"
	}
	cfg.ClientCert = expandHome(cfg.ClientCert)
	cfg.ClientKey = expandHome(cfg.ClientKey)
	cfg.CACert = expandHome(cfg.CACert)
	cfg.IdentityKey = expandHome(cfg.IdentityKey)
	cfg.SessionStore = expandHome(cfg.SessionStore)
	cfg.YoloStore = expandHome(cfg.YoloStore)
	cfg.DirectAuthStore = expandHome(cfg.DirectAuthStore)
	cfg.FCMCredentials = expandHome(cfg.FCMCredentials)
	return cfg, nil
}
