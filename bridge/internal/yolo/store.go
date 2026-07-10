// Package yolo persists each workspace's opt-in auto-reply mode for
// permission prompts. A workspace with no stored entry is off (the default,
// unchanged behavior) — cmux keeps prompting the phone as it does today.
package yolo

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	sqlite "modernc.org/sqlite"
	sqlite3 "modernc.org/sqlite/lib"
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

// fileFormat is the pre-migration JSON on-disk shape -- kept only so
// importLegacyJSON can decode a yolo.json left behind by an install that
// predates the SQLite-backed Store.
type fileFormat struct {
	Modes map[string]string `json:"modes"`
}

// Store is a small SQLite-backed map of workspace ID to yolo mode. Safe to
// call from multiple short-lived processes against the same file -- SQLite
// handles the locking, so there is no in-memory cache to fall out of sync.
type Store struct {
	mu sync.Mutex
	db *sql.DB
}

const schema = `
CREATE TABLE IF NOT EXISTS yolo_modes (
	workspace_id TEXT PRIMARY KEY,
	mode         TEXT NOT NULL
);
`

// Open opens (creating if absent) the SQLite database at path, applies the
// schema, and imports a legacy yolo.json sibling on first run (see
// importLegacyJSON). Structurally identical to internal/e2e.OpenStore's
// shape (busy_timeout pragma, schema-on-Exec, corruption recovery,
// self-terminating legacy import) -- see that package's doc comments for the
// rationale behind each piece; duplicated here rather than shared, since the
// two packages are independent with no existing shared-internals convention
// between them and the shared logic is only ~15 lines.
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create dir for yolo store: %w", err)
	}
	dsn := path + "?_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open yolo store %s: %w", path, err)
	}
	if _, err := db.Exec(schema); err != nil {
		_ = db.Close()
		recovered, rerr := recoverIfCorrupt(path, err)
		if rerr != nil {
			return nil, fmt.Errorf("yolo store %s: %w", path, rerr)
		}
		if recovered {
			return Open(path) // bad file moved aside; retry fresh
		}
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	s := &Store{db: db}
	if err := s.importLegacyJSON(path); err != nil {
		log.Printf("yolo: legacy store import from %s incomplete: %v", legacyPath(path), err)
	}
	return s, nil
}

// recoverIfCorrupt is internal/e2e.recoverIfCorrupt's structural twin -- see
// that function's doc comment for the full rationale (empirically-verified
// SQLITE_NOTADB/SQLITE_CORRUPT codes, timestamped forensic rename).
func recoverIfCorrupt(path string, openErr error) (recovered bool, err error) {
	var sqliteErr *sqlite.Error
	if !errors.As(openErr, &sqliteErr) {
		return false, nil
	}
	if sqliteErr.Code() != sqlite3.SQLITE_NOTADB && sqliteErr.Code() != sqlite3.SQLITE_CORRUPT {
		return false, nil
	}
	corrupt := fmt.Sprintf("%s.corrupt.%d", path, time.Now().Unix())
	if err := os.Rename(path, corrupt); err != nil {
		return false, fmt.Errorf("rename corrupt store aside: %w", err)
	}
	log.Printf("yolo: store %s was corrupt (%v); moved to %s -- every workspace's auto-reply mode was lost", path, openErr, corrupt)
	return true, nil
}

// legacyPath derives the pre-migration JSON yolo file path from the new
// SQLite database path by swapping the extension, e.g. "yolo.db" ->
// "yolo.json".
func legacyPath(dbPath string) string {
	return strings.TrimSuffix(dbPath, filepath.Ext(dbPath)) + ".json"
}

// importLegacyJSON one-time-imports a legacy yolo.json sibling of dbPath
// into the (already schema'd) database, iff yolo_modes is still empty --
// see internal/e2e.importLegacyJSON's doc comment for why that's the
// trigger condition instead of file-existence. A successful import renames
// the source .json to .json.migrated; an unreadable legacy file is logged
// and skipped rather than blocking startup.
func (s *Store) importLegacyJSON(dbPath string) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM yolo_modes`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil // already has real data; never re-import
	}
	raw, err := os.ReadFile(legacyPath(dbPath))
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var f fileFormat
	if err := json.Unmarshal(raw, &f); err != nil {
		log.Printf("yolo: legacy store %s is unreadable (%v); starting empty instead of blocking startup", legacyPath(dbPath), err)
		return nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }() //nolint:errcheck // no-op after a successful Commit
	for workspaceID, mode := range f.Modes {
		if mode == "" {
			continue // matches SetMode's "empty mode removes the entry" semantics
		}
		if _, err := tx.Exec(`INSERT INTO yolo_modes (workspace_id, mode) VALUES (?, ?)`, workspaceID, mode); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	migratedTo := legacyPath(dbPath) + ".migrated"
	if err := os.Rename(legacyPath(dbPath), migratedTo); err != nil {
		log.Printf("yolo: imported %d mode(s) from %s but could not rename it aside (%v) -- remove it manually", len(f.Modes), legacyPath(dbPath), err)
		return nil
	}
	log.Printf("yolo: migrated %d workspace mode(s) from %s to %s", len(f.Modes), legacyPath(dbPath), dbPath)
	return nil
}

// Mode returns the stored mode for workspaceID, or "" (off) if unset.
func (s *Store) Mode(workspaceID string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var mode string
	err := s.db.QueryRow(`SELECT mode FROM yolo_modes WHERE workspace_id = ?`, workspaceID).Scan(&mode)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			log.Printf("yolo: query mode for %s: %v", workspaceID, err)
		}
		return ""
	}
	return mode
}

// SetMode persists mode for workspaceID. An empty mode ("off") removes the
// entry rather than storing an empty string.
func (s *Store) SetMode(workspaceID, mode string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if mode == "" {
		if _, err := s.db.Exec(`DELETE FROM yolo_modes WHERE workspace_id = ?`, workspaceID); err != nil {
			return fmt.Errorf("clear yolo mode for %s: %w", workspaceID, err)
		}
		return nil
	}
	if _, err := s.db.Exec(`
		INSERT INTO yolo_modes (workspace_id, mode) VALUES (?, ?)
		ON CONFLICT(workspace_id) DO UPDATE SET mode = excluded.mode`,
		workspaceID, mode); err != nil {
		return fmt.Errorf("set yolo mode for %s: %w", workspaceID, err)
	}
	return nil
}
