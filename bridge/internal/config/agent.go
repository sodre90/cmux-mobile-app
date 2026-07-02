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
	// The same bootstrap vhost also serves /devices/pair (see
	// cmd/cmux-bridge/pair.go), derived from this URL.
	BootstrapURL string `toml:"bootstrap_url"`
	// IdentityKey is the path to this agent's X25519 e2e identity private key
	// (internal/e2e.Identity), created on first use by `cmux-bridge
	// pair-device`.
	IdentityKey string `toml:"identity_key"`
	// SessionStore is the path to the JSON file holding this agent's paired
	// devices' e2e shared secrets and replay counters (internal/e2e.Store).
	SessionStore string `toml:"session_store"`
}

func agentDefaults() AgentConfig {
	return AgentConfig{
		CmuxBin:      "cmux",
		IdentityKey:  "~/.config/cmux-bridge/identity.key",
		SessionStore: "~/.config/cmux-bridge/sessions.json",
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
	return cfg, nil
}
