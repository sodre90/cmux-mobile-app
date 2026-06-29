// Package cli holds bootstrap helpers shared by the cmux-bridge and cmux-relay
// command binaries: resolving config paths and opening the device-token store
// the config points at.
package cli

import (
	"os"
	"path/filepath"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/config"
)

// ConfigPath returns ~/.config/<app>/<file>, falling back to <file> in the
// working directory when the home directory cannot be resolved.
func ConfigPath(app, file string) string {
	home, err := os.UserHomeDir()
	if err != nil {
		return file
	}
	return filepath.Join(home, ".config", app, file)
}

// LoadStore loads the config at cfgPath and opens the device-token store it
// names.
func LoadStore(cfgPath string) (config.Config, *auth.Store, error) {
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return cfg, nil, err
	}
	store, err := auth.Open(cfg.TokenStore)
	if err != nil {
		return cfg, nil, err
	}
	return cfg, store, nil
}
