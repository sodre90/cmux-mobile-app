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
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// ErrNotFound is returned by Verify, Revoke, and SetFCMToken when no
// matching device (or, for Verify, no matching active-tenant device) exists
// -- as opposed to a genuine infrastructure error (a failed query), which
// those methods return unwrapped so callers can tell "no such device" apart
// from "the store is unhealthy" and respond accordingly (see auth.Require,
// which must never turn an infra error into a 401).
var ErrNotFound = errors.New("auth: not found")

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

// migrations is applied unconditionally after schema on every Open, one
// statement at a time, so it is a no-op on a database that already has these
// columns (including a database created fresh by schema above, once a future
// edit folds them into the base CREATE TABLE) and additive on one that
// predates device-pubkey pairing.
//
// This deliberately does not use "ALTER TABLE ... ADD COLUMN IF NOT EXISTS":
// modernc.org/sqlite v1.53.0 reports sqlite_version() 3.53.2 (which upstream
// SQLite has supported that clause on since 3.35.0), but its parser rejects
// it with a syntax error regardless — a real gap between the reported and
// actual grammar of this particular Go port. So each statement below runs
// individually, and applyMigrations treats the "duplicate column name" error
// SQLite returns for a column that already exists as success rather than a
// failure.
var migrations = []string{
	`ALTER TABLE devices ADD COLUMN device_pubkey TEXT NOT NULL DEFAULT ''`,
	`ALTER TABLE pairing_codes ADD COLUMN device_pubkey TEXT`,
	`ALTER TABLE pairing_codes ADD COLUMN token_hash TEXT`,
	`ALTER TABLE pairing_codes ADD COLUMN redeemed_at TEXT`,
	`ALTER TABLE pairing_codes ADD COLUMN agent_pubkey TEXT NOT NULL DEFAULT ''`,
}

// applyMigrations runs each migration statement, tolerating a "duplicate
// column name" error (meaning a prior Open already applied it) as a no-op.
// Any other error aborts and is returned.
func applyMigrations(db *sql.DB) error {
	for _, stmt := range migrations {
		if _, err := db.Exec(stmt); err != nil {
			if strings.Contains(err.Error(), "duplicate column name") {
				continue
			}
			return err
		}
	}
	return nil
}

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
	// DevicePubkey is the device's base64-encoded X25519 public key, submitted
	// at pairing time (internal/e2e). Every device has one — Issue and
	// RedeemPairingCode both reject an empty value.
	DevicePubkey string
	// TokenHash is the full SHA-256 hex digest of the raw token — used as the
	// internal/e2e.Store session key, and injected as X-Device-ID by the
	// relay's proxy Director (internal/relay/proxy.go) so the agent can tell
	// which device is speaking without ever seeing the raw bearer token.
	TokenHash string
	// HashSuffix is the last 6 hex characters of TokenHash — enough for an
	// operator to eyeball which device is which in `cmux-relay devices list`
	// output without printing the full hash.
	HashSuffix string
}

// Store holds tenants, agent-cert audit records, devices, and pairing codes
// in a local SQLite database at path.
type Store struct {
	mu sync.Mutex
	db *sql.DB
}

// Open opens (creating if absent) the SQLite database at path and applies the
// schema and migrations. Safe to call from multiple short-lived processes
// (the relay server and the `cmux-relay` CLI) against the same file — SQLite
// handles the locking, so there is no in-memory cache to fall out of sync.
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create dir for store: %w", err)
	}
	// Add busy_timeout pragma to allow multiple processes to wait instead of
	// immediately failing with SQLITE_BUSY. 5 seconds should be ample for
	// infrequent operations like tenant/device list and pairing code redemption.
	dsn := path + "?_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open store %s: %w", path, err)
	}
	if _, err := db.Exec(schema); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	if err := applyMigrations(db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("apply migrations: %w", err)
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

// TenantCount returns the total number of tenants ever created (including
// revoked ones). Used to cap unbounded growth from /tenants/register abuse.
func (s *Store) TenantCount() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM tenants`).Scan(&n); err != nil {
		return 0, fmt.Errorf("count tenants: %w", err)
	}
	return n, nil
}

// ListTenants returns every tenant, oldest first.
func (s *Store) ListTenants() ([]Tenant, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT id, created_at, revoked_at FROM tenants ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
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

// Issue creates a new device bearer token for tenantID, bound to
// devicePubkey (the device's base64 X25519 public key). The raw token is
// returned once, here — only its hash is ever persisted. devicePubkey is
// required: there is no manual-pairing fallback path in this version, so a
// device is never issued a token without an e2e identity to encrypt to.
func (s *Store) Issue(tenantID, name, devicePubkey string) (string, error) {
	if devicePubkey == "" {
		return "", fmt.Errorf("issue device token: device_pubkey is required")
	}
	tok, err := randomHex(32)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err = s.db.Exec(`INSERT INTO devices (token_hash, tenant_id, name, device_pubkey, created_at) VALUES (?, ?, ?, ?, ?)`,
		hashToken(tok), tenantID, name, devicePubkey, now())
	if err != nil {
		return "", fmt.Errorf("issue device token: %w", err)
	}
	return tok, nil
}

// Verify returns the device for a token, provided its tenant has not been
// revoked. A SHA-256 digest lookup needs no constant-time comparison here:
// unlike a raw-token equality check with early-exit branching, an indexed
// lookup on a fully-avalanching hash leaks no exploitable timing signal.
// Returns ErrNotFound when the token is empty or matches no active-tenant
// device; any other error is a genuine store failure.
func (s *Store) Verify(token string) (Device, error) {
	if token == "" {
		return Device{}, ErrNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	hash := hashToken(token)
	row := s.db.QueryRow(`
		SELECT d.tenant_id, d.name, d.fcm_token, d.device_pubkey, d.created_at
		FROM devices d JOIN tenants t ON t.id = d.tenant_id
		WHERE d.token_hash = ? AND t.revoked_at IS NULL`, hash)
	var dev Device
	var fcm sql.NullString
	var created string
	if err := row.Scan(&dev.TenantID, &dev.Name, &fcm, &dev.DevicePubkey, &created); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Device{}, ErrNotFound
		}
		return Device{}, fmt.Errorf("verify device: %w", err)
	}
	dev.FCM = fcm.String
	dev.Created, _ = time.Parse(time.RFC3339, created)
	dev.TokenHash = hash
	dev.HashSuffix = hash[len(hash)-6:]
	return dev, nil
}

// List returns all devices across all tenants.
func (s *Store) List() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT token_hash, tenant_id, name, fcm_token, device_pubkey, created_at FROM devices ORDER BY created_at`)
	if err != nil {
		return nil
	}
	defer func() { _ = rows.Close() }()
	var out []Device
	for rows.Next() {
		var dev Device
		var hash string
		var fcm sql.NullString
		var created string
		if err := rows.Scan(&hash, &dev.TenantID, &dev.Name, &fcm, &dev.DevicePubkey, &created); err != nil {
			continue
		}
		dev.FCM = fcm.String
		dev.Created, _ = time.Parse(time.RFC3339, created)
		dev.TokenHash = hash
		dev.HashSuffix = hash[len(hash)-6:]
		out = append(out, dev)
	}
	return out
}

// Revoke removes a device by its raw token. Returns ErrNotFound if no device
// matched (already revoked, or never existed); any other error is a genuine
// store failure.
func (s *Store) Revoke(token string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`DELETE FROM devices WHERE token_hash = ?`, hashToken(token))
	if err != nil {
		return fmt.Errorf("revoke device: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// SetFCMToken records the FCM registration token for a device token. Returns
// ErrNotFound if token matches no device; any other error is a genuine store
// failure.
func (s *Store) SetFCMToken(token, fcm string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`UPDATE devices SET fcm_token = ? WHERE token_hash = ?`, fcm, hashToken(token))
	if err != nil {
		return fmt.Errorf("set fcm token: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// FCMDevice pairs a device's e2e deviceID (its bearer-token hash -- the same
// value the relay's proxy Director injects as X-Device-ID, and the same key
// e2e.Store.EncryptFrame expects) with its registered FCM token.
type FCMDevice struct {
	DeviceID string
	FCMToken string
}

// TenantFCMDevices returns one FCMDevice per distinct, non-empty FCM token
// belonging to tenantID's own devices. Scoped per tenant so an attention push
// triggered by one tenant's agent can never fan out to another tenant's
// phones. Dedup matters here: a device that re-pairs repeatedly (e.g. a phone
// toggling between direct and relay slots) leaves behind multiple device rows
// sharing the same still-valid fcm_token -- without it, a single attention
// event would fan out one push per stale row to the exact same token. Among
// rows sharing a token, the most recently inserted row wins: re-pairing a
// slot overwrites that slot's shared secret on the phone, so only the newest
// row's deviceID still has a secret the phone can actually decrypt with.
// Ordered by rowid, not created_at -- created_at has only second resolution
// (see now()), too coarse to break ties between pairings done in the same
// second, while rowid is exact insertion order regardless of timestamp.
func (s *Store) TenantFCMDevices(tenantID string) []FCMDevice {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT token_hash, fcm_token FROM devices WHERE tenant_id = ? AND fcm_token IS NOT NULL AND fcm_token != '' ORDER BY rowid DESC`, tenantID)
	if err != nil {
		return []FCMDevice{}
	}
	defer func() { _ = rows.Close() }()
	seen := map[string]bool{}
	out := []FCMDevice{}
	for rows.Next() {
		var d FCMDevice
		if err := rows.Scan(&d.DeviceID, &d.FCMToken); err != nil {
			continue
		}
		if seen[d.FCMToken] {
			continue
		}
		seen[d.FCMToken] = true
		out = append(out, d)
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
// valid for ttl. agentPubkey (the requesting agent's base64 X25519 e2e
// public key) is stored alongside it so PairingCodeInfo can hand it to a
// phone pairing via manual entry instead of a scanned QR -- the QR carries
// this same key directly, this is just the same data reachable by code alone.
func (s *Store) NewPairingCode(tenantID, agentPubkey string, ttl time.Duration) (string, error) {
	code := randomCode(8)
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO pairing_codes (code, tenant_id, agent_pubkey, expires_at) VALUES (?, ?, ?, ?)`,
		code, tenantID, agentPubkey, time.Now().Add(ttl).UTC().Format(time.RFC3339))
	if err != nil {
		return "", err
	}
	return code, nil
}

// RedeemPairingCode consumes a single-use pairing code, issuing a fresh
// device token bound to devicePubkey. Unlike the old CLI-driven flow this
// replaces, this is non-destructive: the pairing_codes row is UPDATEd (not
// deleted) so the issuing agent's poll (PairingCodeStatus) can retrieve the
// device's token hash and public key after redemption — the raw token itself
// is returned only here, to the caller (the phone), and never persisted.
// Atomic at the database level via a transaction, so two concurrent
// redemption attempts on the same code can't both succeed.
func (s *Store) RedeemPairingCode(code, name, devicePubkey string) (token, tenantID string, ok bool) {
	if devicePubkey == "" {
		return "", "", false
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.db.Begin()
	if err != nil {
		return "", "", false
	}
	defer tx.Rollback() //nolint:errcheck // no-op after a successful Commit

	var expiresAt string
	var redeemedAt sql.NullString
	err = tx.QueryRow(`SELECT tenant_id, expires_at, redeemed_at FROM pairing_codes WHERE code = ?`, code).
		Scan(&tenantID, &expiresAt, &redeemedAt)
	if err != nil || redeemedAt.Valid {
		return "", "", false
	}
	exp, err := time.Parse(time.RFC3339, expiresAt)
	if err != nil || time.Now().After(exp) {
		return "", "", false
	}

	tok, err := randomHex(32)
	if err != nil {
		return "", "", false
	}
	hash := hashToken(tok)
	if _, err := tx.Exec(`INSERT INTO devices (token_hash, tenant_id, name, device_pubkey, created_at) VALUES (?, ?, ?, ?, ?)`,
		hash, tenantID, name, devicePubkey, now()); err != nil {
		return "", "", false
	}
	if _, err := tx.Exec(`UPDATE pairing_codes SET device_pubkey = ?, token_hash = ?, redeemed_at = ? WHERE code = ?`,
		devicePubkey, hash, now(), code); err != nil {
		return "", "", false
	}
	if err := tx.Commit(); err != nil {
		return "", "", false
	}
	return tok, tenantID, true
}

// PairingCodeStatus reports whether code exists under tenantID, and if so
// whether it has been redeemed. Once redeemed, it returns the redeeming
// device's public key and full token hash — never the raw token, which was
// already handed to the phone directly by RedeemPairingCode and is never
// persisted. Used by `cmux-bridge pair-device`'s poll loop to learn a
// device's identity once the phone completes /devices/pair. Scoped to
// tenantID so one tenant's agent can never observe another tenant's pairing
// codes, even by guessing.
func (s *Store) PairingCodeStatus(tenantID, code string) (devicePubkey, tokenHash string, redeemed, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var gotTenant string
	var pubkey, hash, redeemedAt sql.NullString
	err := s.db.QueryRow(`SELECT tenant_id, device_pubkey, token_hash, redeemed_at FROM pairing_codes WHERE code = ?`, code).
		Scan(&gotTenant, &pubkey, &hash, &redeemedAt)
	if err != nil || gotTenant != tenantID {
		return "", "", false, false
	}
	if !redeemedAt.Valid {
		return "", "", false, true
	}
	return pubkey.String, hash.String, true, true
}

// PairingCodeInfo resolves a pairing code to the agent's e2e public key and
// tenant, for a phone pairing via manual entry instead of a scanned QR --
// the QR carries this same data directly; this lets a phone without the QR
// (no camera, or pairing remotely) resolve it from the code alone. Unlike
// PairingCodeStatus this is NOT tenant-scoped: the caller doesn't know its
// tenant yet, that's what it's trying to learn. Reports not-ok once the code
// is expired or already redeemed, matching RedeemPairingCode's single-use
// semantics -- a used-up code must not keep resolving.
func (s *Store) PairingCodeInfo(code string) (agentPubkey, tenantID, expiresAt string, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var pubkey sql.NullString
	var redeemedAt sql.NullString
	err := s.db.QueryRow(`SELECT agent_pubkey, tenant_id, expires_at, redeemed_at FROM pairing_codes WHERE code = ?`, code).
		Scan(&pubkey, &tenantID, &expiresAt, &redeemedAt)
	if err != nil || redeemedAt.Valid {
		return "", "", "", false
	}
	exp, err := time.Parse(time.RFC3339, expiresAt)
	if err != nil || time.Now().After(exp) {
		return "", "", "", false
	}
	return pubkey.String, tenantID, expiresAt, true
}
