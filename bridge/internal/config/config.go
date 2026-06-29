// Package config loads the bridge's TOML configuration. A missing file yields
// sensible defaults so the bridge can run with zero configuration on the Mac
// where cmux is already installed.
package config

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/BurntSushi/toml"
)

// Config is the bridge configuration. Field tags map to TOML keys.
type Config struct {
	// Listen is the host:port the bridge binds. Default loopback only.
	Listen string `toml:"listen"`
	// CmuxBin is the path to the cmux CLI. Default "cmux" (resolved via PATH).
	CmuxBin string `toml:"cmux_bin"`
	// TokenStore is the path to the device-token JSON file.
	TokenStore string `toml:"token_store"`
	// FCMProjectID is the Firebase project id for push. Empty disables push.
	FCMProjectID string `toml:"fcm_project_id"`
	// FCMCredentials is the path to a Google service-account JSON key. Empty
	// disables push.
	FCMCredentials string `toml:"fcm_credentials"`
	// AgentCN is the client-cert CN the relay trusts as the Mac agent.
	AgentCN string `toml:"agent_cn"`
	// RelayToken is the shared secret the relay injects and the agent checks.
	RelayToken string `toml:"relay_token"`
}

func defaults() Config {
	return Config{
		Listen:     "127.0.0.1:8765",
		CmuxBin:    "cmux",
		TokenStore: expandHome("~/.config/cmux-bridge/devices.json"),
	}
}

// Load reads the TOML file at path. If the file does not exist, defaults are
// returned. Any "~/" prefixes in path-valued fields are expanded to the user's
// home directory.
func Load(path string) (Config, error) {
	cfg := defaults()
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return cfg, nil
		}
		return cfg, fmt.Errorf("read config %s: %w", path, err)
	}
	if err := toml.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("parse config %s: %w", path, err)
	}
	cfg.TokenStore = expandHome(cfg.TokenStore)
	cfg.FCMCredentials = expandHome(cfg.FCMCredentials)
	if cfg.Listen == "" {
		cfg.Listen = defaults().Listen
	}
	if cfg.CmuxBin == "" {
		cfg.CmuxBin = defaults().CmuxBin
	}
	return cfg, nil
}

// expandHome replaces a leading "~/" with the user's home directory. On
// failure or for non-"~" paths, the input is returned unchanged.
func expandHome(p string) string {
	if p == "" || !strings.HasPrefix(p, "~/") {
		return p
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return p
	}
	return filepath.Join(home, p[2:])
}
