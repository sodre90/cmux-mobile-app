package e2e

import (
	"crypto/ecdh"
	"crypto/rand"
	"fmt"
	"os"
	"path/filepath"
)

type Identity struct {
	Priv *ecdh.PrivateKey
}

func LoadOrCreateIdentity(path string) (*Identity, error) {
	raw, err := os.ReadFile(path)
	if err == nil {
		priv, err := ecdh.X25519().NewPrivateKey(raw)
		if err != nil {
			return nil, fmt.Errorf("parse identity key %s: %w", path, err)
		}
		return &Identity{Priv: priv}, nil
	}
	if !os.IsNotExist(err) {
		return nil, fmt.Errorf("read identity key %s: %w", path, err)
	}

	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate identity key: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create dir for identity key: %w", err)
	}
	if err := os.WriteFile(path, priv.Bytes(), 0o600); err != nil {
		return nil, fmt.Errorf("write identity key %s: %w", path, err)
	}
	return &Identity{Priv: priv}, nil
}

func (id *Identity) PublicKey() *ecdh.PublicKey {
	return id.Priv.PublicKey()
}
