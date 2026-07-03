// Package yolo persists each workspace's opt-in auto-reply mode for
// permission prompts. A workspace with no stored entry is off (the default,
// unchanged behavior) — cmux keeps prompting the phone as it does today.
package yolo

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// Non-off mode values, passed verbatim as cmux's own feed.permission.reply
// "mode" param.
const (
	Always = "always"
	All    = "all"
	Bypass = "bypass"
)

// Valid reports whether mode is a recognized value: "" (off) or one of the
// three auto-reply modes above.
func Valid(mode string) bool {
	switch mode {
	case "", Always, All, Bypass:
		return true
	}
	return false
}

type fileFormat struct {
	Modes map[string]string `json:"modes"`
}

// Store is a small JSON-file-backed map of workspace ID to yolo mode.
type Store struct {
	path string
	mu   sync.Mutex
}

func OpenStore(path string) *Store {
	return &Store{path: path}
}

func (s *Store) load() (fileFormat, error) {
	f := fileFormat{Modes: map[string]string{}}
	raw, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return f, nil
		}
		return f, fmt.Errorf("read yolo store %s: %w", s.path, err)
	}
	if len(raw) == 0 {
		return f, nil
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		return f, fmt.Errorf("parse yolo store %s: %w", s.path, err)
	}
	if f.Modes == nil {
		f.Modes = map[string]string{}
	}
	return f, nil
}

func (s *Store) save(f fileFormat) error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return fmt.Errorf("create dir for yolo store: %w", err)
	}
	raw, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal yolo store: %w", err)
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return fmt.Errorf("write yolo store: %w", err)
	}
	return os.Rename(tmp, s.path)
}

// Mode returns the stored mode for workspaceID, or "" (off) if unset.
func (s *Store) Mode(workspaceID string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return ""
	}
	return f.Modes[workspaceID]
}

// SetMode persists mode for workspaceID. An empty mode ("off") removes the
// entry rather than storing an empty string.
func (s *Store) SetMode(workspaceID, mode string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return err
	}
	if mode == "" {
		delete(f.Modes, workspaceID)
	} else {
		f.Modes[workspaceID] = mode
	}
	return s.save(f)
}
