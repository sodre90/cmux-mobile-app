package config

import (
	"errors"
	"fmt"
	"io/fs"
	"os"

	"github.com/BurntSushi/toml"
)

// AgentConfig is the Mac agent's configuration. The agent dials the relay and
// serves the cmux handler over the tunnel; it holds no device secrets.
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
}

func agentDefaults() AgentConfig { return AgentConfig{CmuxBin: "cmux"} }

// LoadAgent reads the agent TOML at path. A missing file yields defaults.
func LoadAgent(path string) (AgentConfig, error) {
	cfg := agentDefaults()
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return cfg, nil
		}
		return cfg, fmt.Errorf("read agent config %s: %w", path, err)
	}
	if err := toml.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("parse agent config %s: %w", path, err)
	}
	if cfg.CmuxBin == "" {
		cfg.CmuxBin = "cmux"
	}
	cfg.ClientCert = expandHome(cfg.ClientCert)
	cfg.ClientKey = expandHome(cfg.ClientKey)
	cfg.CACert = expandHome(cfg.CACert)
	return cfg, nil
}
