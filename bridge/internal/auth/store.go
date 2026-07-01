// Package auth manages tenants, their Mac-agent identity, and paired device
// (phone) bearer tokens, persisted in a local SQLite database. Bearer tokens
// are stored only as a SHA-256 hash — the raw token is returned once, at
// issuance, and never persisted or logged.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS tenants (
	id         TEXT PRIMARY KEY,
	created_at TEXT NOT NULL,
	revoked_at TEXT
);
CREATE TABLE IF NOT EXISTS agent_certs (
	serial     TEXT PRIMARY KEY,
	tenant_id  TEXT NOT NULL REFERENCES tenants(id),
	issued_at  TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
	token_hash TEXT PRIMARY KEY,
	tenant_id  TEXT NOT NULL REFERENCES tenants(id),
	name       TEXT NOT NULL,
	fcm_token  TEXT,
	created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pairing_codes (
	code       TEXT PRIMARY KEY,
	tenant_id  TEXT NOT NULL REFERENCES tenants(id),
	expires_at TEXT NOT NULL
);
`

// Tenant is a registered Mac-agent identity. Devices belong to exactly one.
type Tenant struct {
	ID        string
	CreatedAt time.Time
	Revoked   bool
}

// Device is a paired client (a phone) belonging to exactly one tenant.
type Device struct {
	TenantID string
	Name     string
	FCM      string
	Created  time.Time
	// HashSuffix is the last 6 hex characters of the token's SHA-256 hash —
	// enough for an operator to eyeball which device is which; the store
	// never holds anything the raw token can be recovered from.
	HashSuffix string
}

// Store holds tenants, agent-cert audit records, devices, and pairing codes
// in a local SQLite database at path.
type Store struct {
	mu sync.Mutex
	db *sql.DB
}

// Open opens (creating if absent) the SQLite database at path and applies the
// schema. Safe to call from multiple short-lived processes (the relay server
// and the `cmux-relay` CLI) against the same file — SQLite handles the
// locking, so there is no in-memory cache to fall out of sync (unlike the
// previous JSON-file store, this needs no reload-on-SIGHUP mechanism).
func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open store %s: %w", path, err)
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	return &Store{db: db}, nil
}

func now() string { return time.Now().UTC().Format(time.RFC3339) }

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// CreateTenant mints a fresh, unguessable tenant ID.
func (s *Store) CreateTenant() (string, error) {
	id, err := randomHex(16)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.db.Exec(`INSERT INTO tenants (id, created_at) VALUES (?, ?)`, id, now()); err != nil {
		return "", fmt.Errorf("create tenant: %w", err)
	}
	return id, nil
}

// TenantActive reports whether id names a tenant that exists and has not
// been revoked.
func (s *Store) TenantActive(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	var revoked sql.NullString
	err := s.db.QueryRow(`SELECT revoked_at FROM tenants WHERE id = ?`, id).Scan(&revoked)
	if err != nil {
		return false
	}
	return !revoked.Valid
}

// RevokeTenant marks a tenant revoked. Its agent tunnel is refused on next
// connect and its devices' bearer tokens stop verifying immediately (Verify
// joins against tenants.revoked_at).
func (s *Store) RevokeTenant(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`UPDATE tenants SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, now(), id)
	if err != nil {
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// ListTenants returns every tenant, oldest first.
func (s *Store) ListTenants() ([]Tenant, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT id, created_at, revoked_at FROM tenants ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Tenant
	for rows.Next() {
		var t Tenant
		var created string
		var revoked sql.NullString
		if err := rows.Scan(&t.ID, &created, &revoked); err != nil {
			return nil, err
		}
		t.CreatedAt, _ = time.Parse(time.RFC3339, created)
		t.Revoked = revoked.Valid
		out = append(out, t)
	}
	return out, rows.Err()
}

// RecordAgentCert logs an issued agent-cert serial against its tenant, for
// audit purposes only. It is not consulted for access control in this
// version: revoking a specific cert without losing tenant identity (key
// rotation) is out of scope — revoking a tenant revokes its whole identity.
func (s *Store) RecordAgentCert(tenantID, serial string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO agent_certs (serial, tenant_id, issued_at) VALUES (?, ?, ?)`,
		serial, tenantID, now())
	return err
}

// Issue creates a new device bearer token for tenantID. The raw token is
// returned once, here — only its hash is ever persisted.
func (s *Store) Issue(tenantID, name string) (string, error) {
	tok, err := randomHex(32)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err = s.db.Exec(`INSERT INTO devices (token_hash, tenant_id, name, created_at) VALUES (?, ?, ?, ?)`,
		hashToken(tok), tenantID, name, now())
	if err != nil {
		return "", fmt.Errorf("issue device token: %w", err)
	}
	return tok, nil
}

// Verify returns the device for a token, provided its tenant has not been
// revoked. A SHA-256 digest lookup needs no constant-time comparison here:
// unlike a raw-token equality check with early-exit branching, an indexed
// lookup on a fully-avalanching hash leaks no exploitable timing signal.
func (s *Store) Verify(token string) (Device, bool) {
	if token == "" {
		return Device{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	hash := hashToken(token)
	row := s.db.QueryRow(`
		SELECT d.tenant_id, d.name, d.fcm_token, d.created_at
		FROM devices d JOIN tenants t ON t.id = d.tenant_id
		WHERE d.token_hash = ? AND t.revoked_at IS NULL`, hash)
	var dev Device
	var fcm sql.NullString
	var created string
	if err := row.Scan(&dev.TenantID, &dev.Name, &fcm, &created); err != nil {
		return Device{}, false
	}
	dev.FCM = fcm.String
	dev.Created, _ = time.Parse(time.RFC3339, created)
	dev.HashSuffix = hash[len(hash)-6:]
	return dev, true
}

// List returns all devices across all tenants.
func (s *Store) List() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT token_hash, tenant_id, name, fcm_token, created_at FROM devices ORDER BY created_at`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var dev Device
		var hash string
		var fcm sql.NullString
		var created string
		if err := rows.Scan(&hash, &dev.TenantID, &dev.Name, &fcm, &created); err != nil {
			continue
		}
		dev.FCM = fcm.String
		dev.Created, _ = time.Parse(time.RFC3339, created)
		dev.HashSuffix = hash[len(hash)-6:]
		out = append(out, dev)
	}
	return out
}

// Revoke removes a device by its raw token. Reports whether a device was
// removed.
func (s *Store) Revoke(token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`DELETE FROM devices WHERE token_hash = ?`, hashToken(token))
	if err != nil {
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// SetFCMToken records the FCM registration token for a device token.
func (s *Store) SetFCMToken(token, fcm string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`UPDATE devices SET fcm_token = ? WHERE token_hash = ?`, fcm, hashToken(token))
	if err != nil {
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// FCMTokens returns all non-empty FCM registration tokens across all
// tenants.
func (s *Store) FCMTokens() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT fcm_token FROM devices WHERE fcm_token IS NOT NULL AND fcm_token != ''`)
	if err != nil {
		return []string{}
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var tok string
		if err := rows.Scan(&tok); err == nil {
			out = append(out, tok)
		}
	}
	return out
}

const pairingCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // unambiguous: no 0/O/1/I

func randomCode(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	out := make([]byte, n)
	for i := range b {
		out[i] = pairingCodeAlphabet[int(b[i])%len(pairingCodeAlphabet)]
	}
	return string(out)
}

// NewPairingCode generates a single-use pairing code, scoped to tenantID,
// valid for ttl.
func (s *Store) NewPairingCode(tenantID string, ttl time.Duration) (string, error) {
	code := randomCode(8)
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO pairing_codes (code, tenant_id, expires_at) VALUES (?, ?, ?)`,
		code, tenantID, time.Now().Add(ttl).UTC().Format(time.RFC3339))
	if err != nil {
		return "", err
	}
	return code, nil
}

// RedeemPairingCode exchanges a valid, unexpired code for a freshly issued
// device token scoped to that code's tenant. The code is consumed regardless
// of outcome, to prevent reuse.
func (s *Store) RedeemPairingCode(code, name string) (token, tenantID string, ok bool) {
	s.mu.Lock()
	var expiresAt string
	err := s.db.QueryRow(`SELECT tenant_id, expires_at FROM pairing_codes WHERE code = ?`, code).
		Scan(&tenantID, &expiresAt)
	if err == nil {
		_, _ = s.db.Exec(`DELETE FROM pairing_codes WHERE code = ?`, code)
	}
	s.mu.Unlock()
	if err != nil {
		return "", "", false
	}
	exp, _ := time.Parse(time.RFC3339, expiresAt)
	if time.Now().After(exp) {
		return "", "", false
	}
	tok, err := s.Issue(tenantID, name)
	if err != nil {
		return "", "", false
	}
	return tok, tenantID, true
}
