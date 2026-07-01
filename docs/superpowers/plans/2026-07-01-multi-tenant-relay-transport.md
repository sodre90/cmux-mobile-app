# Multi-Tenant Relay: Transport Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `cmux-relay` from a single-tenant relay (one Mac agent, one tunnel slot, one flat device-token file) into a relay that serves many independent tenants, each strictly isolated at the routing layer, with agents self-registering over HTTP instead of the operator hand-rolling certs.

**Architecture:** The relay becomes its own certificate authority. Each Mac agent registers itself over an HTTP endpoint reachable without a client cert (a brand-new agent has none yet), receiving a unique per-tenant client cert whose CN (`agent:<tenant-id>`) the relay uses to key a per-tenant slot in the tunnel registry. Device (phone) bearer tokens gain a `TenantID` field, and the reverse proxy resolves which tenant's tunnel to use strictly from the authenticated device's token — never from "whichever tunnel is currently up." Storage moves from a flat JSON file to an embedded SQLite database, with bearer tokens hashed at rest.

**Tech Stack:** Go 1.26, `modernc.org/sqlite` (pure-Go, no cgo — keeps cross-compilation as simple as it is today), Go's stdlib `crypto/x509` for the CA, existing `hashicorp/yamux` tunnel and `BurntSushi/toml` config.

## Global Constraints

- Go 1.26, no new cgo dependencies (rules out `mattn/go-sqlite3`; use `modernc.org/sqlite`).
- Follow existing patterns: TOML config via `internal/config`, bootstrap helpers via `internal/cli`, table-driven-where-natural tests using `t.TempDir()`, no test framework beyond stdlib `testing`.
- Every bearer token is stored only as a SHA-256 hash — never the raw value — per the approved design spec.
- This plan is transport-layer isolation only. It does **not** touch the Android app, does **not** implement E2E content encryption, and does **not** implement self-service device pairing (QR codes) — those are covered by a separate plan built on top of this one. Device pairing in this plan remains operator-CLI-driven, same operational shape as today, just tenant-scoped.
- Per-cert-serial revocation with stable tenant-ID preservation (rotating a Mac's key without losing its tenant identity) is explicitly out of scope — re-registering mints a new tenant ID. This is a known, documented limitation, not an oversight.
- Reference: `docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md` (the approved design this plan implements).

---

### Task 1: Rewrite `auth.Store` as a multi-tenant SQLite-backed store

**Files:**
- Modify: `bridge/internal/auth/store.go` (full rewrite of the persistence layer)
- Modify: `bridge/internal/auth/middleware.go:38-44` (export `bearerToken` as `BearerToken`)
- Modify: `bridge/internal/auth/middleware_test.go` (rename references)
- Modify: `bridge/internal/auth/store_test.go` (full rewrite for the new API)
- Modify: `bridge/go.mod`, `bridge/go.sum` (add `modernc.org/sqlite`)

**Interfaces:**
- Produces: `type Device struct { TenantID, Name, FCM string; Created time.Time; HashSuffix string }`; `type Tenant struct { ID string; CreatedAt time.Time; Revoked bool }`; `Store.Open(path string) (*Store, error)`; `Store.CreateTenant() (string, error)`; `Store.TenantActive(id string) bool`; `Store.RevokeTenant(id string) bool`; `Store.ListTenants() ([]Tenant, error)`; `Store.RecordAgentCert(tenantID, serial string) error`; `Store.Issue(tenantID, name string) (string, error)`; `Store.Verify(token string) (Device, bool)`; `Store.List() []Device`; `Store.Revoke(token string) bool`; `Store.SetFCMToken(token, fcm string) bool`; `Store.FCMTokens() []string`; `Store.NewPairingCode(tenantID string, ttl time.Duration) (string, error)`; `Store.RedeemPairingCode(code, name string) (token, tenantID string, ok bool)`; `auth.BearerToken(r *http.Request) string`.
- Consumes: nothing outside stdlib + `modernc.org/sqlite`.

- [ ] **Step 1: Add the SQLite dependency**

Run: `cd bridge && go get modernc.org/sqlite`
Expected: `go.mod`/`go.sum` gain `modernc.org/sqlite` and its transitive deps; exits 0.

- [ ] **Step 2: Write the new store_test.go**

Replace `bridge/internal/auth/store_test.go` entirely:

```go
package auth

import (
	"path/filepath"
	"testing"
	"time"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "d.db"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func newTenant(t *testing.T, s *Store) string {
	t.Helper()
	id, err := s.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestCreateTenantActiveRevoke(t *testing.T) {
	s := newStore(t)
	id := newTenant(t, s)
	if !s.TenantActive(id) {
		t.Fatal("freshly created tenant should be active")
	}
	if s.TenantActive("nonexistent") {
		t.Fatal("unknown tenant id must not be active")
	}
	if !s.RevokeTenant(id) {
		t.Fatal("revoke should report success")
	}
	if s.TenantActive(id) {
		t.Fatal("revoked tenant must not be active")
	}
	if s.RevokeTenant(id) {
		t.Fatal("double revoke should report false")
	}
}

func TestListTenants(t *testing.T) {
	s := newStore(t)
	a := newTenant(t, s)
	b := newTenant(t, s)
	s.RevokeTenant(b)
	list, err := s.ListTenants()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 {
		t.Fatalf("want 2 tenants, got %d", len(list))
	}
	byID := map[string]Tenant{}
	for _, tn := range list {
		byID[tn.ID] = tn
	}
	if byID[a].Revoked {
		t.Fatal("tenant a should not be revoked")
	}
	if !byID[b].Revoked {
		t.Fatal("tenant b should be revoked")
	}
}

func TestIssueVerifyRevoke(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, err := s.Issue(tenant, "phone")
	if err != nil {
		t.Fatal(err)
	}
	dev, ok := s.Verify(tok)
	if !ok {
		t.Fatal("issued token should verify")
	}
	if dev.TenantID != tenant {
		t.Fatalf("Verify TenantID = %q want %q", dev.TenantID, tenant)
	}
	if _, ok := s.Verify("bogus"); ok {
		t.Fatal("bogus token must not verify")
	}
	if _, ok := s.Verify(""); ok {
		t.Fatal("empty token must not verify")
	}
	if !s.Revoke(tok) {
		t.Fatal("revoke should report removal")
	}
	if _, ok := s.Verify(tok); ok {
		t.Fatal("revoked token must not verify")
	}
	if s.Revoke(tok) {
		t.Fatal("double revoke should report false")
	}
}

func TestVerifyFailsClosedWhenTenantRevoked(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone")
	s.RevokeTenant(tenant)
	if _, ok := s.Verify(tok); ok {
		t.Fatal("a device token must stop verifying once its tenant is revoked")
	}
}

func TestTokensAreHashedAtRest(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone")
	var count int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM devices WHERE token_hash = ?`, tok).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("the raw token must never appear as a stored token_hash value")
	}
}

func TestPairingCodeSingleUse(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	tok, gotTenant, ok := s.RedeemPairingCode(code, "phone")
	if !ok || tok == "" {
		t.Fatal("first redeem should succeed")
	}
	if gotTenant != tenant {
		t.Fatalf("redeemed tenant = %q want %q", gotTenant, tenant)
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone"); ok {
		t.Fatal("reuse of a code must fail")
	}
	if _, ok := s.Verify(tok); !ok {
		t.Fatal("redeemed token should verify")
	}
}

func TestPairingCodeExpiry(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, _ := s.NewPairingCode(tenant, -time.Second) // already expired
	if _, _, ok := s.RedeemPairingCode(code, "phone"); ok {
		t.Fatal("expired code must fail")
	}
}

func TestPersistenceAcrossReopen(t *testing.T) {
	p := filepath.Join(t.TempDir(), "d.db")
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone")
	s2, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := s2.Verify(tok); !ok {
		t.Fatal("token must survive reopening the database file")
	}
}

func TestListShowsHashSuffixNotRawToken(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone")
	list := s.List()
	if len(list) != 1 {
		t.Fatalf("want 1 device, got %d", len(list))
	}
	if list[0].HashSuffix == "" || len(list[0].HashSuffix) != 6 {
		t.Fatalf("want a 6-char hash suffix, got %q", list[0].HashSuffix)
	}
	for _, want := range []string{tok, tok[len(tok)-6:]} {
		if list[0].HashSuffix == want {
			t.Fatal("List must never expose anything derived from the raw token")
		}
	}
}

func TestFCMTokens(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone")
	if got := s.FCMTokens(); len(got) != 0 {
		t.Fatalf("expected no FCM tokens, got %v", got)
	}
	if !s.SetFCMToken(tok, "fcm-abc") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}
	if s.SetFCMToken("bogus", "x") {
		t.Fatal("SetFCMToken must fail for unknown device")
	}
	got := s.FCMTokens()
	if len(got) != 1 || got[0] != "fcm-abc" {
		t.Fatalf("unexpected FCM tokens: %v", got)
	}
}
```

- [ ] **Step 3: Run the new tests to confirm they fail to compile against the old store**

Run: `cd bridge && go test ./internal/auth/...`
Expected: FAIL — compile errors like `s.CreateTenant undefined`, `too many arguments in call to s.Issue`.

- [ ] **Step 4: Replace `store.go`**

Replace `bridge/internal/auth/store.go` entirely:

```go
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
```

- [ ] **Step 5: Export `BearerToken` in middleware.go**

In `bridge/internal/auth/middleware.go`, rename `bearerToken` to `BearerToken` (2 occurrences: the func declaration and its use inside `Require`), and update its doc comment:

```go
// BearerToken extracts the raw "Authorization: Bearer <token>" value from a
// request, or "" if absent/malformed. Exported so handlers that already went
// through Require (and so already have a Device in context) can recover the
// original raw token for calls that key off it directly, like SetFCMToken.
func BearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		return strings.TrimSpace(h[len(prefix):])
	}
	return ""
}
```

And inside `Require`, change `tok := bearerToken(r)` to `tok := BearerToken(r)`.

In `bridge/internal/auth/middleware_test.go`, rename any `bearerToken(` call to `BearerToken(`.

- [ ] **Step 6: Run the auth package tests**

Run: `cd bridge && go test ./internal/auth/... -v`
Expected: PASS — all tests including `TestTokensAreHashedAtRest`, `TestVerifyFailsClosedWhenTenantRevoked`.

- [ ] **Step 7: Commit**

```bash
git add bridge/go.mod bridge/go.sum bridge/internal/auth/
git commit -m "auth: rewrite store as multi-tenant SQLite backend with hashed tokens"
```

---

### Task 2: New `internal/ca` package (minimal certificate authority)

**Files:**
- Create: `bridge/internal/ca/ca.go`
- Test: `bridge/internal/ca/ca_test.go`

**Interfaces:**
- Produces: `type CA struct { CertPEM []byte }` (unexported key/cert fields); `ca.LoadOrCreate(certPath, keyPath string) (*CA, error)`; `(*CA) SignCSR(csrPEM []byte, cn string, validity time.Duration) (certPEM []byte, serial string, err error)`.
- Consumes: stdlib only.

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/ca/ca_test.go`:

```go
package ca

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"path/filepath"
	"testing"
	"time"
)

func generateCSR(t *testing.T, cn string) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.CertificateRequest{Subject: pkix.Name{CommonName: cn}}
	der, err := x509.CreateCertificateRequest(rand.Reader, tmpl, key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: der})
}

func mustParseCert(t *testing.T, certPEM []byte) *x509.Certificate {
	t.Helper()
	block, _ := pem.Decode(certPEM)
	if block == nil {
		t.Fatal("no PEM block")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func TestLoadOrCreatePersistsAndReloads(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "ca.crt")
	keyPath := filepath.Join(dir, "ca.key")

	ca1, err := LoadOrCreate(certPath, keyPath)
	if err != nil {
		t.Fatal(err)
	}
	ca2, err := LoadOrCreate(certPath, keyPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(ca1.CertPEM) != string(ca2.CertPEM) {
		t.Fatal("second LoadOrCreate should reuse the persisted CA, not mint a new one")
	}
}

func TestSignCSRIgnoresCSRsOwnCNAndProducesVerifiableCert(t *testing.T) {
	dir := t.TempDir()
	c, err := LoadOrCreate(filepath.Join(dir, "ca.crt"), filepath.Join(dir, "ca.key"))
	if err != nil {
		t.Fatal(err)
	}
	// The CSR itself requests a CN of "whatever-the-caller-wants" — the CA
	// must ignore that and use the CN the relay assigns, or a hostile caller
	// could self-request another tenant's identity.
	csr := generateCSR(t, "agent:someone-elses-tenant-id")
	certPEM, serial, err := c.SignCSR(csr, "agent:the-real-tenant-id", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if serial == "" {
		t.Fatal("want a non-empty serial")
	}
	leaf := mustParseCert(t, certPEM)
	if leaf.Subject.CommonName != "agent:the-real-tenant-id" {
		t.Fatalf("CN = %q, want the assigned CN, not the CSR's requested one", leaf.Subject.CommonName)
	}
	roots := x509.NewCertPool()
	roots.AddCert(mustParseCert(t, c.CertPEM))
	if _, err := leaf.Verify(x509.VerifyOptions{Roots: roots, KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}); err != nil {
		t.Fatalf("issued cert does not verify against the CA root: %v", err)
	}
}

func TestSignCSRRejectsGarbage(t *testing.T) {
	dir := t.TempDir()
	c, err := LoadOrCreate(filepath.Join(dir, "ca.crt"), filepath.Join(dir, "ca.key"))
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.SignCSR([]byte("not a csr"), "agent:x", time.Hour); err == nil {
		t.Fatal("want an error for a garbage CSR")
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd bridge && go test ./internal/ca/...`
Expected: FAIL — `package ca is not in std` / no Go files, since `ca.go` doesn't exist yet.

- [ ] **Step 3: Write `ca.go`**

Create `bridge/internal/ca/ca.go`:

```go
// Package ca is a minimal certificate authority the relay uses to issue
// short-lived identity certs to newly registered tenants (Mac agents). It
// signs CSRs the caller generates locally — private keys never reach this
// package, matching the project's existing principle that key material never
// crosses the network.
package ca

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

// CA holds the root signing key and certificate.
type CA struct {
	key  *ecdsa.PrivateKey
	cert *x509.Certificate
	// CertPEM is the root certificate, PEM-encoded — handed to newly
	// registered agents so they can pin it as their trust root.
	CertPEM []byte
}

// LoadOrCreate loads a root CA from certPath/keyPath, generating and
// persisting a new one (10-year validity) if either file is absent.
func LoadOrCreate(certPath, keyPath string) (*CA, error) {
	certPEM, certErr := os.ReadFile(certPath)
	keyPEM, keyErr := os.ReadFile(keyPath)
	if certErr == nil && keyErr == nil {
		return load(certPEM, keyPEM)
	}
	return create(certPath, keyPath)
}

func create(certPath, keyPath string) (*CA, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate CA key: %w", err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "cmux-relay root CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, fmt.Errorf("create CA cert: %w", err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	if err := os.MkdirAll(filepath.Dir(certPath), 0o700); err != nil {
		return nil, err
	}
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(keyPath), 0o700); err != nil {
		return nil, err
	}
	if err := os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		return nil, err
	}
	return load(certPEM, keyPEM)
}

func load(certPEM, keyPEM []byte) (*CA, error) {
	certBlock, _ := pem.Decode(certPEM)
	if certBlock == nil {
		return nil, fmt.Errorf("no PEM block in CA cert")
	}
	cert, err := x509.ParseCertificate(certBlock.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse CA cert: %w", err)
	}
	keyBlock, _ := pem.Decode(keyPEM)
	if keyBlock == nil {
		return nil, fmt.Errorf("no PEM block in CA key")
	}
	key, err := x509.ParseECPrivateKey(keyBlock.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse CA key: %w", err)
	}
	return &CA{key: key, cert: cert, CertPEM: certPEM}, nil
}

// SignCSR verifies csrPEM's self-signature and issues a leaf certificate
// using cn — NOT whatever CN the CSR itself requested, since the caller
// (the relay) is the only party allowed to decide which identity a CSR
// receives. validity is how long the issued cert remains valid.
func (c *CA) SignCSR(csrPEM []byte, cn string, validity time.Duration) (certPEM []byte, serial string, err error) {
	block, _ := pem.Decode(csrPEM)
	if block == nil {
		return nil, "", fmt.Errorf("no PEM block in CSR")
	}
	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil {
		return nil, "", fmt.Errorf("parse CSR: %w", err)
	}
	if err := csr.CheckSignature(); err != nil {
		return nil, "", fmt.Errorf("CSR signature invalid: %w", err)
	}
	serialNum, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, "", err
	}
	tmpl := &x509.Certificate{
		SerialNumber: serialNum,
		Subject:      pkix.Name{CommonName: cn},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(validity),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, c.cert, csr.PublicKey, c.key)
	if err != nil {
		return nil, "", fmt.Errorf("sign cert: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), serialNum.String(), nil
}
```

- [ ] **Step 4: Run to confirm it passes**

Run: `cd bridge && go test ./internal/ca/... -v`
Expected: PASS, including `TestSignCSRIgnoresCSRsOwnCNAndProducesVerifiableCert`.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/ca/
git commit -m "ca: add minimal certificate authority for tenant agent-cert issuance"
```

---

### Task 3: Registry tenant-keyed + relay routing, config, and `serve.go` wiring

`registry.go` and `relay.go` are the same Go package (`relay`): changing `Registry`'s method signatures immediately breaks `relay.go`'s calls to it, so this task lands as one commit, not two — there is no point between them where `go build ./internal/relay/...` succeeds. `proxy.go`, `config.go`, and `cmd/cmux-relay/serve.go` join for the same reason: all reference each other's changed signatures, and the package (and its caller) only compiles once every one of them is updated.

**Files:**
- Modify: `bridge/internal/relay/registry.go`
- Modify: `bridge/internal/relay/registry_test.go`
- Modify: `bridge/internal/relay/relay.go`
- Modify: `bridge/internal/relay/proxy.go`
- Modify: `bridge/internal/relay/relay_test.go`
- Modify: `bridge/internal/relay/proxy_test.go`
- Modify: `bridge/internal/config/config.go`
- Modify: `bridge/internal/config/config_test.go`
- Modify: `bridge/cmd/cmux-relay/serve.go`

**Interfaces:**
- Produces: `Registry.Set(tenantID string, sess *yamux.Session, stop func())`; `Registry.Get(tenantID string) *yamux.Session`; `Registry.Clear(tenantID string, sess *yamux.Session)`; `relay.New(store *auth.Store, signer *ca.CA, relayToken string) *Relay`; keeps `Relay.Handler()`, `Relay.SetEdgeToken`, `Relay.SetSessionHook` unchanged in shape.
- Consumes: `github.com/hashicorp/yamux`; `auth.Store` (Task 1); `ca.CA`/`ca.LoadOrCreate` (Task 2).

- [ ] **Step 1: Write the failing test for tenant non-interference**

Replace `bridge/internal/relay/registry_test.go`:

```go
package relay

import (
	"net"
	"testing"
	"time"

	"github.com/hashicorp/yamux"
)

func mkSession(t *testing.T) *yamux.Session {
	t.Helper()
	c1, c2 := net.Pipe()
	t.Cleanup(func() { c1.Close(); c2.Close() })
	go func() { _, _ = yamux.Client(c2, nil) }() // peer end keeps the pipe alive
	s, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestRegistrySetReplaceClosesOldForSameTenant(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	stopped := make(chan struct{})
	r.Set("tenant-a", s1, func() { close(stopped) })

	s2 := mkSession(t)
	r.Set("tenant-a", s2, nil)

	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("prior stop func not called on replace")
	}
	if !s1.IsClosed() {
		t.Fatal("prior session should be closed on replace")
	}
	if r.Get("tenant-a") != s2 {
		t.Fatal("Get(tenant-a) should return s2")
	}
}

func TestRegistryTenantsDoNotInterfere(t *testing.T) {
	r := NewRegistry()
	sa := mkSession(t)
	sb := mkSession(t)
	r.Set("tenant-a", sa, nil)
	r.Set("tenant-b", sb, nil)

	if r.Get("tenant-a") != sa {
		t.Fatal("tenant-a's session should be unaffected by tenant-b's Set")
	}
	if r.Get("tenant-b") != sb {
		t.Fatal("tenant-b's session should be present")
	}
	r.Clear("tenant-a", sa)
	if r.Get("tenant-a") != nil {
		t.Fatal("tenant-a should be cleared")
	}
	if r.Get("tenant-b") != sb {
		t.Fatal("clearing tenant-a must not affect tenant-b")
	}
}

func TestRegistryClearOnlyIfCurrent(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	r.Set("tenant-a", s1, nil)
	other := mkSession(t)
	r.Clear("tenant-a", other) // not current for tenant-a → no-op
	if r.Get("tenant-a") != s1 {
		t.Fatal("Clear of a non-current session should be a no-op")
	}
	r.Clear("tenant-a", s1)
	if r.Get("tenant-a") != nil {
		t.Fatal("Get should be nil after clearing the active session")
	}
}

func TestRegistryGetUnknownTenant(t *testing.T) {
	r := NewRegistry()
	if r.Get("never-registered") != nil {
		t.Fatal("Get on an unknown tenant should return nil, not panic")
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd bridge && go test ./internal/relay/... -run TestRegistry`
Expected: FAIL — `r.Set` / `r.Get` called with wrong argument count against the old single-session `Registry`.

- [ ] **Step 3: Replace `registry.go`**

```go
// Package relay is the home-server rendezvous: it accepts Mac agents'
// outbound yamux tunnels and reverse-proxies authenticated app requests over
// them, one yamux stream per request, one tunnel slot per tenant. It owns
// tenant/device auth, pairing, and FCM push.
package relay

import (
	"sync"

	"github.com/hashicorp/yamux"
)

// Registry holds one active agent tunnel session per tenant. A new session
// for a tenant replaces and closes that tenant's prior session; it never
// touches other tenants' sessions.
type Registry struct {
	mu    sync.Mutex
	sess  map[string]*yamux.Session
	stops map[string]func()
}

func NewRegistry() *Registry {
	return &Registry{
		sess:  map[string]*yamux.Session{},
		stops: map[string]func(){},
	}
}

// Set installs sess as tenantID's current session, closing and stopping any
// prior session for that same tenant. stop may be nil. Other tenants are
// untouched.
func (r *Registry) Set(tenantID string, sess *yamux.Session, stop func()) {
	r.mu.Lock()
	oldSess, oldStop := r.sess[tenantID], r.stops[tenantID]
	r.sess[tenantID] = sess
	if stop != nil {
		r.stops[tenantID] = stop
	} else {
		delete(r.stops, tenantID)
	}
	r.mu.Unlock()

	if oldStop != nil {
		oldStop()
	}
	if oldSess != nil {
		_ = oldSess.Close()
	}
}

// Get returns tenantID's active session, or nil when none is connected, it
// has closed, or the tenant is unknown.
func (r *Registry) Get(tenantID string) *yamux.Session {
	r.mu.Lock()
	defer r.mu.Unlock()
	sess := r.sess[tenantID]
	if sess != nil && sess.IsClosed() {
		return nil
	}
	return sess
}

// Clear removes tenantID's session if sess is still the one on record.
func (r *Registry) Clear(tenantID string, sess *yamux.Session) {
	r.mu.Lock()
	var stop func()
	if r.sess[tenantID] == sess {
		stop = r.stops[tenantID]
		delete(r.sess, tenantID)
		delete(r.stops, tenantID)
	}
	r.mu.Unlock()
	if stop != nil {
		stop()
	}
}
```

- [ ] **Step 4: `registry.go` alone won't build yet — that's expected**

`registry_test.go` (this file) tests only `Registry`, which now compiles on its own. But `relay.go` elsewhere in this same package still calls the old single-session `Registry` API (`reg.Set(sess, cancel)`, `reg.Current()`), so `go build ./internal/relay/...` fails until Step 6 below updates `relay.go` too. Do not attempt to run the package's test suite yet — continue directly to Step 5.

- [ ] **Step 5: Update `config.go`**

In `bridge/internal/config/config.go`:
- Remove the `AgentCN string` field and its doc comment.
- Add, next to the other relay-only fields:

```go
	// CACert is the path to the relay's own CA certificate (PEM). Auto-
	// created on first run if absent.
	CACert string `toml:"ca_cert"`
	// CAKey is the path to the relay's own CA private key (PEM), 0600.
	CAKey string `toml:"ca_key"`
```

- In `defaults()`, change the `TokenStore` default (it was pointing at `cmux-bridge`'s config dir, not the relay's own — a pre-existing copy-paste inconsistency, fixed here since this line is being touched anyway) and add the two new defaults:

```go
func defaults() Config {
	return Config{
		Listen:     "127.0.0.1:8765",
		CmuxBin:    "cmux",
		TokenStore: expandHome("~/.config/cmux-relay/store.db"),
		CACert:     expandHome("~/.config/cmux-relay/ca.crt"),
		CAKey:      expandHome("~/.config/cmux-relay/ca.key"),
	}
}
```

- In `Load()`, alongside the existing `cfg.TokenStore = expandHome(cfg.TokenStore)` line, add:

```go
	cfg.CACert = expandHome(cfg.CACert)
	cfg.CAKey = expandHome(cfg.CAKey)
```

- In `bridge/internal/config/agent_test.go`'s `TestConfigRelayFields`, replace the `agent_cn` reference:

```go
func TestConfigRelayFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "relay.toml")
	if err := os.WriteFile(path, []byte("relay_token=\"secret\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.RelayToken != "secret" {
		t.Fatalf("relay fields not parsed: %+v", cfg)
	}
	if cfg.CACert == "" || cfg.CAKey == "" {
		t.Fatal("CACert/CAKey should default, not be empty")
	}
}
```

- [ ] **Step 6: Replace `relay.go`**

```go
package relay

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log"
	"net/http"
	"net/http/httputil"
	"strings"
	"time"

	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/ca"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

// agentCNPrefix marks a client cert as belonging to a Mac agent, followed by
// its tenant ID (e.g. "agent:9f3a2c..."). Any other CN shape is a device.
const agentCNPrefix = "agent:"

// agentCertValidity is how long a freshly issued agent cert is valid. Cert
// rotation without losing tenant identity is out of scope for this version —
// after this window an agent must re-register, minting a new tenant ID.
const agentCertValidity = 365 * 24 * time.Hour

// Relay is the home-server rendezvous: it accepts Mac agents' tunnels and
// reverse-proxies authenticated app requests over them.
type Relay struct {
	store      *auth.Store
	reg        *Registry
	ca         *ca.CA
	relayToken string
	edgeToken  string
	proxy      *httputil.ReverseProxy
	onSession  func(context.Context, *yamux.Session)
}

// New builds a Relay. store may be nil only in tests that never hit auth
// routes. signer may be nil only in tests that never hit /tenants/register.
func New(store *auth.Store, signer *ca.CA, relayToken string) *Relay {
	reg := NewRegistry()
	return &Relay{
		store:      store,
		reg:        reg,
		ca:         signer,
		relayToken: relayToken,
		proxy:      newProxy(reg, relayToken),
	}
}

// SetSessionHook registers a callback invoked (in its own goroutine) for each
// accepted agent session; its context is cancelled when the session ends.
func (r *Relay) SetSessionHook(f func(context.Context, *yamux.Session)) { r.onSession = f }

// SetEdgeToken sets a shared secret the trusted edge (nginx) must present in
// X-Edge-Token on every request except /healthz. Empty disables the check.
func (r *Relay) SetEdgeToken(t string) { r.edgeToken = t }

// parseCN extracts the CN attribute from an RFC2253 ("CN=foo,O=bar") or legacy
// slash ("/CN=foo/O=bar") distinguished name.
func parseCN(dn string) string {
	for _, part := range strings.FieldsFunc(dn, func(r rune) bool { return r == ',' || r == '/' }) {
		part = strings.TrimSpace(part)
		if strings.HasPrefix(part, "CN=") {
			return strings.TrimPrefix(part, "CN=")
		}
	}
	return ""
}

func (r *Relay) clientCN(req *http.Request) string {
	return parseCN(req.Header.Get("X-Client-Cert-CN"))
}

// tenantFromAgentCN extracts the tenant ID from an agent CN ("agent:<id>"),
// or reports ok=false for any other CN shape (devices, or no cert at all).
func tenantFromAgentCN(cn string) (tenantID string, ok bool) {
	if !strings.HasPrefix(cn, agentCNPrefix) {
		return "", false
	}
	id := strings.TrimPrefix(cn, agentCNPrefix)
	if id == "" {
		return "", false
	}
	return id, true
}

func (r *Relay) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("/agent/tunnel", r.handleTunnel)
	mux.Handle("POST /tenants/register", http.HandlerFunc(r.handleRegisterTenant))
	mux.Handle("POST /devices/register", r.notAgent(auth.Require(r.store, http.HandlerFunc(r.handleRegister))))
	mux.Handle("/", r.notAgent(auth.Require(r.store, r.logProxy(r.proxy))))
	if r.edgeToken == "" {
		return mux
	}
	return r.requireEdge(mux)
}

// requireEdge gates every route except /healthz on a shared secret the
// trusted edge (nginx) injects. Constant-time compare so the secret can't be
// probed by timing.
func (r *Relay) requireEdge(next http.Handler) http.Handler {
	want := []byte(r.edgeToken)
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path != "/healthz" &&
			subtle.ConstantTimeCompare([]byte(req.Header.Get("X-Edge-Token")), want) != 1 {
			writeJSONErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next.ServeHTTP(w, req)
	})
}

// notAgent rejects requests bearing an agent CN on non-tunnel routes.
func (r *Relay) notAgent(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if _, ok := tenantFromAgentCN(r.clientCN(req)); ok {
			writeJSONErr(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, req)
	})
}

func (r *Relay) handleTunnel(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := tenantFromAgentCN(r.clientCN(req))
	if !ok || !r.store.TenantActive(tenantID) {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	sess, err := tunnel.Accept(w, req)
	if err != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	r.reg.Set(tenantID, sess, cancel)
	log.Printf("relay: agent tunnel up (tenant=%q)", tenantID)
	if r.onSession != nil {
		go r.onSession(ctx, sess)
	}
	<-sess.CloseChan() // block until the tunnel dies
	log.Printf("relay: agent tunnel down (tenant=%q)", tenantID)
	r.reg.Clear(tenantID, sess)
	cancel()
}

type registerReq struct {
	FCMToken string `json:"fcm_token"`
}

func (r *Relay) handleRegister(w http.ResponseWriter, req *http.Request) {
	if _, ok := auth.DeviceFromContext(req.Context()); !ok {
		writeJSONErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var rq registerReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.FCMToken == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing fcm_token")
		return
	}
	r.store.SetFCMToken(auth.BearerToken(req), rq.FCMToken)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"ok":true}`))
}

type registerTenantReq struct {
	CSR string `json:"csr"` // PEM-encoded PKCS#10 certificate signing request
}

type registerTenantResp struct {
	TenantID string `json:"tenant_id"`
	CertPEM  string `json:"cert_pem"`
	CAPEM    string `json:"ca_pem"`
}

// handleRegisterTenant mints a brand-new tenant identity for a Mac agent that
// has none yet. Reachable without a client cert by design (see
// deploy/nginx-cmux-relay-bootstrap.conf) — an unregistered agent has no cert
// to present. Rate limiting / abuse resistance is a known, tracked gap (see
// the design doc's non-goals) — this handler does only basic input hygiene.
func (r *Relay) handleRegisterTenant(w http.ResponseWriter, req *http.Request) {
	if r.ca == nil {
		writeJSONErr(w, http.StatusServiceUnavailable, "registration_unavailable")
		return
	}
	req.Body = http.MaxBytesReader(w, req.Body, 8<<10) // CSRs are a few hundred bytes
	var rq registerTenantReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.CSR == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing csr")
		return
	}
	tenantID, err := r.store.CreateTenant()
	if err != nil {
		log.Printf("relay: create tenant: %v", err)
		writeJSONErr(w, http.StatusInternalServerError, "internal_error")
		return
	}
	certPEM, serial, err := r.ca.SignCSR([]byte(rq.CSR), agentCNPrefix+tenantID, agentCertValidity)
	if err != nil {
		writeJSONErr(w, http.StatusBadRequest, "invalid_csr")
		return
	}
	if err := r.store.RecordAgentCert(tenantID, serial); err != nil {
		log.Printf("relay: record agent cert: %v", err)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(registerTenantResp{
		TenantID: tenantID,
		CertPEM:  string(certPEM),
		CAPEM:    string(r.ca.CertPEM),
	})
	log.Printf("relay: registered new tenant %q", tenantID)
}
```

- [ ] **Step 7: Replace `proxy.go`'s dial logic**

In `bridge/internal/relay/proxy.go`, change the `Transport.DialContext` to resolve the tenant from the authenticated device rather than "whatever's current":

```go
package relay

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"net/http/httputil"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

// ErrAgentOffline is returned by the proxy transport when the target
// tenant's agent has no active session.
var ErrAgentOffline = errors.New("agent offline")

func writeJSONErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(`{"error":"` + msg + `"}`))
}

// newProxy builds the reverse proxy that forwards an app request over a fresh
// yamux stream to the agent belonging to the authenticated device's tenant,
// injecting the relay token. A device can never reach another tenant's
// session: the dial target is resolved solely from auth.DeviceFromContext,
// never from "whichever session happens to be registered."
func newProxy(reg *Registry, relayToken string) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		ErrorLog: log.New(log.Writer(), "relay-proxy: ", log.Flags()),
		Director: func(req *http.Request) {
			req.URL.Scheme = "http"
			req.URL.Host = "agent" // ignored by the stream dialer below
			req.Header.Set("X-Relay-Token", relayToken)
		},
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				dev, ok := auth.DeviceFromContext(ctx)
				if !ok {
					return nil, ErrAgentOffline
				}
				sess := reg.Get(dev.TenantID)
				if sess == nil {
					return nil, ErrAgentOffline
				}
				return sess.Open()
			},
		},
		ErrorHandler: func(w http.ResponseWriter, req *http.Request, err error) {
			if errors.Is(err, ErrAgentOffline) {
				log.Printf("relay: %s %s -> agent_offline", req.Method, req.URL.Path)
				writeJSONErr(w, http.StatusServiceUnavailable, "agent_offline")
				return
			}
			log.Printf("relay: %s %s -> agent_error: %v", req.Method, req.URL.Path, err)
			writeJSONErr(w, http.StatusBadGateway, "agent_error")
		},
	}
}
```

- [ ] **Step 8: Update `relay_test.go`'s existing tests for the new signatures**

In `bridge/internal/relay/relay_test.go`:
- Change every `New(nil, "mac-agent", "tok")` to `New(nil, nil, "tok")` (3 occurrences: `TestRelayHealthz`, `TestRelayEdgeTokenGate`, `TestRelayTunnelRejectsWrongCN`).
- Rewrite `TestRelayEndToEndSessions` to register a tenant instead of assuming a constant CN:

```go
func TestRelayEndToEndSessions(t *testing.T) {
	const ws = `{"workspaces":[{"id":"E43BBF04","current_directory":"/x","preview":"u@h:~/x","terminals":[{"id":"E43BBF04-T1","current_directory":"/x","title":"~/x","is_focused":true,"is_ready":true}]}]}`
	bin := testutil.WriteFakeCmux(t, "#!/bin/sh\ncat <<'JSON'\n"+ws+"\nJSON\n")
	agentSrv := server.New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	const relayTok = "relay-secret"
	trusted := agentSrv.TrustedHandler(relayTok)

	relayStore, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := relayStore.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	devTok, _ := relayStore.Issue(tenantID, "phone")
	rl := New(relayStore, nil, relayTok)
	relayHTTP := httptest.NewServer(rl.Handler())
	defer relayHTTP.Close()

	u := "ws" + strings.TrimPrefix(relayHTTP.URL, "http") + "/agent/tunnel"
	sess, err := tunnel.Dial(context.Background(), u, nil, http.Header{"X-Client-Cert-Cn": {"CN=agent:" + tenantID}})
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()
	go func() { _ = http.Serve(sess, trusted) }()

	waitFor(t, func() bool { return rl.reg.Get(tenantID) != nil })

	req, _ := http.NewRequest("GET", relayHTTP.URL+"/sessions", nil)
	req.Header.Set("Authorization", "Bearer "+devTok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200 through relay, got %d", resp.StatusCode)
	}
	var body struct {
		Workspaces []map[string]any `json:"workspaces"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Workspaces) != 1 {
		t.Fatalf("want 1 workspace, got %d", len(body.Workspaces))
	}

	bad, err := http.Get(relayHTTP.URL + "/sessions")
	if err != nil {
		t.Fatal(err)
	}
	bad.Body.Close()
	if bad.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 without bearer, got %d", bad.StatusCode)
	}
}
```

- Update `TestParseCN` cases from `"CN=mac-agent"` style values — no change needed, `parseCN` itself is untouched; only `TestRelayTunnelRejectsWrongCN` needs its `CN=phone` case to keep failing, which it still does since `"phone"` doesn't have the `agent:` prefix.

- [ ] **Step 9: Update `proxy_test.go` if it references `reg.Current()`**

Open `bridge/internal/relay/proxy_test.go`; anywhere it calls the old `reg.Current()`/`reg.Set(sess, stop)` (single-session shape), update to the tenant-keyed shape, e.g. `reg.Set("tenant-a", sess, stop)` and construct requests whose context carries `auth.Device{TenantID: "tenant-a"}` via `auth.DeviceFromContext`-compatible test setup (wrap the request context with `context.WithValue` using the same unexported key is not possible from the test package — instead, drive these tests through `auth.Require` with a real `*auth.Store` device, exactly as `relay_test.go` does, rather than constructing the context by hand).

- [ ] **Step 10: Update `cmd/cmux-relay/serve.go`**

Replace `bridge/cmd/cmux-relay/serve.go`:

```go
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/ca"
	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/relay"
)

func defaultConfigPath() string {
	return cli.ConfigPath("cmux-relay", "config.toml")
}

func runServe(args []string) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("serve: %v", err)
		return 1
	}
	signer, err := ca.LoadOrCreate(cfg.CACert, cfg.CAKey)
	if err != nil {
		log.Printf("serve: ca: %v", err)
		return 1
	}

	rl := relay.New(store, signer, cfg.RelayToken)
	rl.SetEdgeToken(cfg.EdgeToken)

	var pusher relay.Pusher
	if cfg.FCMCredentials != "" && cfg.FCMProjectID != "" {
		if p, err := push.FromServiceAccount(context.Background(), cfg.FCMProjectID, cfg.FCMCredentials); err != nil {
			log.Printf("serve: push disabled: %v", err)
		} else {
			pusher = p
			log.Printf("serve: FCM push enabled for project %s", cfg.FCMProjectID)
		}
	}
	if pusher != nil {
		rl.SetSessionHook(func(ctx context.Context, sess *yamux.Session) {
			relay.MonitorAgent(ctx, sess, cfg.RelayToken, store, pusher)
		})
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// No SIGHUP/reload handling needed: the store reads live from SQLite on
	// every request, so a separate `cmux-relay pair`/`tenants` process's
	// writes are visible immediately without a restart or reload signal.

	httpSrv := &http.Server{Addr: cfg.Listen, Handler: rl.Handler()}
	go func() {
		<-ctx.Done()
		shutCtx, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = httpSrv.Shutdown(shutCtx)
	}()

	log.Printf("cmux-relay listening on %s", cfg.Listen)
	if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Printf("serve: %v", err)
		return 1
	}
	return 0
}
```

- [ ] **Step 11: Build and run the affected packages**

`bridge/cmd/cmux-relay` (the `commands.go`/`main.go` files, not touched by this task) still calls the pre-multi-tenant `Store.Issue(name)` single-arg signature and reads `Device.Token` — both removed in Task 1. That package is Task 7's responsibility to fix; it is expected to remain non-compiling until Task 7 lands. Do not run a whole-module `go build ./...`/`go test ./...` for this task — scope it to what this task actually owns:

Run: `cd bridge && go build ./internal/... && go test ./internal/...`
Expected: PASS across all `internal` packages, including `TestRegistrySetReplaceClosesOldForSameTenant`, `TestRegistryTenantsDoNotInterfere`, `TestRegistryClearOnlyIfCurrent`, and `TestRegistryGetUnknownTenant` from Step 1 above. If `proxy_test.go` or `pushmon_test.go` still reference old shapes, fix them the same way as Step 9 until this passes clean.

Also run: `cd bridge && go vet ./cmd/cmux-relay/... 2>&1 | grep -v commands.go` (or simply inspect the compiler errors) to confirm the *only* remaining `cmd/cmux-relay` build errors are the known `commands.go` gaps (`store.Issue` arg count, `Device.Token`) — not something this task's `serve.go` rewrite introduced. If `serve.go` itself has any new compile error, that's this task's bug and must be fixed before committing.

- [ ] **Step 12: Commit**

```bash
git add bridge/internal/relay/ bridge/internal/config/ bridge/cmd/cmux-relay/serve.go
git commit -m "relay: registry becomes tenant-keyed, route by tenant instead of a single global agent CN"
```

---

### Task 4: Tenant-scope FCM push fanout (close a cross-tenant leak in `pushmon.go`)

Not in the original plan — added after Task 3's review found it. `pushmon.go`'s
attention-push fanout calls `Store.FCMTokens()`, which returns FCM tokens
**across all tenants**. Since `MonitorAgent` is wired one-per-tunnel via
`Relay.SetSessionHook` and carries no tenant identity, an attention event
from tenant A's agent currently fans out to every tenant's registered
phones, not just tenant A's — a direct violation of this project's core
guarantee (a device must never receive another tenant's data), even though
Task 3's HTTP/WS routing paths are correctly isolated. This task plumbs
`tenantID` through the session-hook → `MonitorAgent` → `fanout` call chain
and replaces the global `FCMTokens()` query with a tenant-scoped one.

**Files:**
- Modify: `bridge/internal/auth/store.go` (remove `FCMTokens()`, add `TenantFCMTokens(tenantID string) []string`)
- Modify: `bridge/internal/auth/store_test.go` (replace `TestFCMTokens` with a tenant-scoped version)
- Modify: `bridge/internal/relay/relay.go` (`onSession`/`SetSessionHook` gain a `tenantID string` parameter; `handleTunnel` passes it)
- Modify: `bridge/internal/relay/pushmon.go` (`MonitorAgent`/`subscribeOnce`/`fanout` gain a `tenantID string` parameter; `fanout` calls `TenantFCMTokens`)
- Modify: `bridge/internal/relay/pushmon_test.go` (update the call site; add a cross-tenant isolation test)
- Modify: `bridge/cmd/cmux-relay/serve.go` (update the `SetSessionHook` closure's signature)

**Interfaces:**
- Produces: `Store.TenantFCMTokens(tenantID string) []string`; `Relay.SetSessionHook(f func(context.Context, string, *yamux.Session))`; `MonitorAgent(ctx context.Context, tenantID string, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher)`.
- Consumes: `auth.Store` (Task 1); `Relay`/`Registry`/`handleTunnel` (Task 3).

- [ ] **Step 1: Write the failing store test**

Replace `TestFCMTokens` in `bridge/internal/auth/store_test.go`:

```go
func TestTenantFCMTokensScopedPerTenant(t *testing.T) {
	s := newStore(t)
	tenantA := newTenant(t, s)
	tenantB := newTenant(t, s)
	tokA, _ := s.Issue(tenantA, "phone-a")
	tokB, _ := s.Issue(tenantB, "phone-b")

	if got := s.TenantFCMTokens(tenantA); len(got) != 0 {
		t.Fatalf("expected no FCM tokens yet, got %v", got)
	}
	if !s.SetFCMToken(tokA, "fcm-a") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}
	if !s.SetFCMToken(tokB, "fcm-b") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}
	if s.SetFCMToken("bogus", "x") {
		t.Fatal("SetFCMToken must fail for unknown device")
	}

	gotA := s.TenantFCMTokens(tenantA)
	if len(gotA) != 1 || gotA[0] != "fcm-a" {
		t.Fatalf("tenantA tokens = %v, want [fcm-a]", gotA)
	}
	gotB := s.TenantFCMTokens(tenantB)
	if len(gotB) != 1 || gotB[0] != "fcm-b" {
		t.Fatalf("tenantB tokens = %v, want [fcm-b]", gotB)
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd bridge && go test ./internal/auth/... -run TestTenantFCMTokens`
Expected: FAIL — `s.TenantFCMTokens undefined`.

- [ ] **Step 3: Replace `FCMTokens` with `TenantFCMTokens` in `store.go`**

In `bridge/internal/auth/store.go`, remove the existing `FCMTokens` method (the one whose doc comment reads "returns all non-empty FCM registration tokens across all tenants") and replace it with:

```go
// TenantFCMTokens returns all non-empty FCM registration tokens belonging to
// tenantID's own devices. Scoped per tenant so an attention push triggered by
// one tenant's agent can never fan out to another tenant's phones.
func (s *Store) TenantFCMTokens(tenantID string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT fcm_token FROM devices WHERE tenant_id = ? AND fcm_token IS NOT NULL AND fcm_token != ''`, tenantID)
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
```

- [ ] **Step 4: Run to confirm it passes**

Run: `cd bridge && go test ./internal/auth/... -v`
Expected: PASS, including `TestTenantFCMTokensScopedPerTenant`.

- [ ] **Step 5: Thread `tenantID` through `relay.go`'s session hook**

In `bridge/internal/relay/relay.go`:
- Change the `onSession` field's type from `func(context.Context, *yamux.Session)` to `func(context.Context, string, *yamux.Session)`.
- Change `SetSessionHook`'s parameter type to match: `func (r *Relay) SetSessionHook(f func(context.Context, string, *yamux.Session)) { r.onSession = f }`.
- In `handleTunnel`, change `go r.onSession(ctx, sess)` to `go r.onSession(ctx, tenantID, sess)` (the function already computes `tenantID` earlier in the same handler — no new lookup needed).

- [ ] **Step 6: Update `pushmon.go` to accept and use `tenantID`**

Replace `bridge/internal/relay/pushmon.go`:

```go
package relay

import (
	"context"
	"log"
	"net"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/server"
)

// Pusher delivers an attention push to a single device token. push.Sender
// satisfies it.
type Pusher interface {
	Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error
}

// MonitorAgent subscribes to the agent's /events over the tunnel and fans
// blocking prompts out to FCM, scoped to tenantID's own devices only. It
// returns when ctx is cancelled or the session dies. relayToken authenticates
// to the agent's trusted handler.
func MonitorAgent(ctx context.Context, tenantID string, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) {
	if push == nil {
		return
	}
	for ctx.Err() == nil {
		if err := subscribeOnce(tenantID, sess, relayToken, store, push); err != nil && sess.IsClosed() {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

func subscribeOnce(tenantID string, sess *yamux.Session, relayToken string, store *auth.Store, push Pusher) error {
	d := websocket.Dialer{
		NetDial: func(_, _ string) (net.Conn, error) { return sess.Open() },
	}
	ws, _, err := d.Dial("ws://agent/events", http.Header{"X-Relay-Token": {relayToken}})
	if err != nil {
		return err
	}
	defer ws.Close()
	for {
		var f server.EventFrame
		if err := ws.ReadJSON(&f); err != nil {
			return err
		}
		if f.NeedsAttention {
			fanout(tenantID, store, push, f)
		}
	}
}

func fanout(tenantID string, store *auth.Store, push Pusher, f server.EventFrame) {
	tokens := store.TenantFCMTokens(tenantID)
	if len(tokens) == 0 {
		return
	}
	body := f.Title
	if body == "" {
		body = f.Kind
	}
	data := map[string]string{
		"type":         "attention",
		"feed_id":      f.FeedID,
		"workspace_id": f.WorkspaceID,
		"surface_id":   f.SurfaceID,
		"kind":         f.Kind,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	sent, failed := 0, 0
	for _, tok := range tokens {
		if err := push.Send(ctx, tok, "Agent needs your attention", body, data); err != nil {
			failed++
			log.Printf("relay: attention push failed (tenant=%q kind=%s ws=%s): %v", tenantID, f.Kind, f.WorkspaceID, err)
			continue
		}
		sent++
	}
	log.Printf("relay: attention push (tenant=%q kind=%s label=%q ws=%s) sent=%d failed=%d", tenantID, f.Kind, body, f.WorkspaceID, sent, failed)
}
```

- [ ] **Step 7: Update `pushmon_test.go` — existing call site plus a new cross-tenant test**

In `bridge/internal/relay/pushmon_test.go`:
- Change `fakePusher` to also record which token each push went to:

```go
type fakePusher struct {
	mu     sync.Mutex
	tokens []string
	calls  []map[string]string
}

func (p *fakePusher) Send(_ context.Context, tok, _, _ string, data map[string]string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.tokens = append(p.tokens, tok)
	p.calls = append(p.calls, data)
	return nil
}
```

- Update `TestMonitorAgentPushesAttention`'s existing call site: change `tenant, _ := store.CreateTenant()` / `tok, _ := store.Issue(tenant, "phone")` to keep using a single `tenant`, and change the dispatch line from `go MonitorAgent(ctx, relaySess, "tok", store, fp)` to `go MonitorAgent(ctx, tenant, relaySess, "tok", store, fp)`.

- Add a new test proving the isolation property:

```go
func TestMonitorAgentScopesPushToOwnTenant(t *testing.T) {
	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}

	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/events" || r.Header.Get("X-Relay-Token") != "tok" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			ws, err := up.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			_ = ws.WriteJSON(server.EventFrame{
				Type: "feed", NeedsAttention: true, FeedID: "F1",
				Kind: "permissionRequest", Title: "Run rm -rf?",
			})
			time.Sleep(500 * time.Millisecond)
		}))
	}()

	store, err := auth.Open(t.TempDir() + "/d.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantA, _ := store.CreateTenant()
	tenantB, _ := store.CreateTenant()
	tokA, _ := store.Issue(tenantA, "phone-a")
	tokB, _ := store.Issue(tenantB, "phone-b")
	store.SetFCMToken(tokA, "fcm-a")
	store.SetFCMToken(tokB, "fcm-b")

	fp := &fakePusher{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// tenantA's agent session fires the attention event; tenantB's FCM token
	// must never receive it.
	go MonitorAgent(ctx, tenantA, relaySess, "tok", store, fp)

	waitFor(t, func() bool {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		return len(fp.tokens) > 0
	})
	fp.mu.Lock()
	defer fp.mu.Unlock()
	for _, tok := range fp.tokens {
		if tok != "fcm-a" {
			t.Fatalf("push reached a token outside tenantA: %q", tok)
		}
	}
}
```

- [ ] **Step 8: Update `serve.go`'s session-hook wiring**

In `bridge/cmd/cmux-relay/serve.go`, change:

```go
	if pusher != nil {
		rl.SetSessionHook(func(ctx context.Context, sess *yamux.Session) {
			relay.MonitorAgent(ctx, sess, cfg.RelayToken, store, pusher)
		})
	}
```

to:

```go
	if pusher != nil {
		rl.SetSessionHook(func(ctx context.Context, tenantID string, sess *yamux.Session) {
			relay.MonitorAgent(ctx, tenantID, sess, cfg.RelayToken, store, pusher)
		})
	}
```

- [ ] **Step 9: Run the affected packages**

Run: `cd bridge && go build ./internal/... && go test ./internal/... -v`
Expected: PASS, including `TestTenantFCMTokensScopedPerTenant`, `TestMonitorAgentPushesAttention`, and `TestMonitorAgentScopesPushToOwnTenant`. As with Task 3, `cmd/cmux-relay`'s `commands.go`/`main.go` gaps are still expected and out of scope — but this task's own change to `serve.go` must compile cleanly, so also run `cd bridge && go build ./cmd/cmux-relay/... 2>&1` and confirm the only errors are the same two pre-existing `commands.go` ones (`store.Issue` arg count, `Device.Token`), not a new error from `serve.go`.

- [ ] **Step 10: Commit**

```bash
git add bridge/internal/auth/ bridge/internal/relay/ bridge/cmd/cmux-relay/serve.go
git commit -m "relay: scope FCM attention-push fanout to the triggering tenant only"
```

---

### Task 5: nginx bootstrap surface for `/tenants/register`

**Files:**
- Create: `bridge/deploy/nginx-cmux-relay-bootstrap.conf`
- Modify: `bridge/deploy/nginx-cmux-relay.conf` (comment only, pointing at the new file)

**Interfaces:** none (deploy config, not code).

- [ ] **Step 1: Create the bootstrap vhost**

Create `bridge/deploy/nginx-cmux-relay-bootstrap.conf`:

```nginx
# Separate, no-client-cert-required surface for brand-new agents that have no
# identity yet — a fresh agent can't present a cert it doesn't have, so it
# can't reach the main mTLS vhost in nginx-cmux-relay.conf at all. This vhost
# proxies ONLY /tenants/register; everything else 404s. The main vhost keeps
# ssl_verify_client on, unchanged, for both the agent tunnel and all device
# traffic.
#
# Rate limiting / abuse resistance for this endpoint is a known, tracked gap
# (see docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md,
# "Explicit non-goals") — add it before exposing this publicly at any scale.
server {
    listen 8444 ssl;
    http2 on;
    server_name cmux.example.com;

    ssl_certificate     /etc/nginx/certs/cmux/server.crt;
    ssl_certificate_key /etc/nginx/certs/cmux/server.key;
    # Deliberately no ssl_client_certificate / ssl_verify_client here.

    client_max_body_size 16k;   # CSRs are a few hundred bytes

    location = /tenants/register {
        proxy_pass http://127.0.0.1:8765;
        proxy_set_header Host $host;
        # proxy_set_header X-Edge-Token "CHANGE_ME_edge_secret";  # if edge_token is set — same secret as the main vhost
    }

    location / {
        return 404;
    }
}
```

- [ ] **Step 2: Cross-reference from the main vhost**

At the top of `bridge/deploy/nginx-cmux-relay.conf`, after the existing header comment block, add:

```nginx
# A brand-new agent (no cert yet) registers through the separate, no-mTLS
# vhost in nginx-cmux-relay-bootstrap.conf — this vhost's ssl_verify_client on
# stays mandatory for both the agent tunnel and all device traffic.
```

- [ ] **Step 3: Commit**

```bash
git add bridge/deploy/nginx-cmux-relay-bootstrap.conf bridge/deploy/nginx-cmux-relay.conf
git commit -m "deploy: add no-mTLS nginx surface for agent self-registration"
```

---

### Task 6: `cmux-bridge agent` self-registers on first run

**Files:**
- Create: `bridge/cmd/cmux-bridge/register.go`
- Test: `bridge/cmd/cmux-bridge/register_test.go`
- Modify: `bridge/internal/config/agent.go` (add `BootstrapURL`)
- Modify: `bridge/internal/config/agent_test.go`
- Modify: `bridge/cmd/cmux-bridge/agent.go` (call `ensureRegistered` before `loadTLS`)
- Modify: `bridge/deploy/agent.example.toml`

**Interfaces:**
- Produces: `ensureRegistered(cfg config.AgentConfig) error`.
- Consumes: `config.AgentConfig` (extended with `BootstrapURL`).

- [ ] **Step 1: Add `BootstrapURL` to `AgentConfig`**

In `bridge/internal/config/agent.go`, add a field:

```go
	// BootstrapURL is the relay's no-mTLS registration endpoint
	// (e.g. https://cmux.example.com:8444/tenants/register), used exactly
	// once, on first run, when ClientCert/ClientKey/CACert don't exist yet.
	BootstrapURL string `toml:"bootstrap_url"`
```

- [ ] **Step 2: Write the failing test**

Create `bridge/cmd/cmux-bridge/register_test.go`:

```go
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/config"
)

func TestEnsureRegisteredCallsBootstrapAndWritesFiles(t *testing.T) {
	dir := t.TempDir()
	var gotCSR string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			CSR string `json:"csr"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		gotCSR = body.CSR
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"tenant_id": "abc123",
			"cert_pem":  "FAKE CERT",
			"ca_pem":    "FAKE CA",
		})
	}))
	defer srv.Close()

	cfg := config.AgentConfig{
		ClientCert:   filepath.Join(dir, "agent.crt"),
		ClientKey:    filepath.Join(dir, "agent.key"),
		CACert:       filepath.Join(dir, "ca.crt"),
		BootstrapURL: srv.URL,
	}
	if err := ensureRegistered(cfg); err != nil {
		t.Fatal(err)
	}
	if gotCSR == "" {
		t.Fatal("bootstrap server should have received a non-empty CSR")
	}
	cert, err := os.ReadFile(cfg.ClientCert)
	if err != nil || string(cert) != "FAKE CERT" {
		t.Fatalf("client cert not written correctly: %v %q", err, cert)
	}
	if _, err := os.ReadFile(cfg.ClientKey); err != nil {
		t.Fatalf("client key not written: %v", err)
	}
	caCert, err := os.ReadFile(cfg.CACert)
	if err != nil || string(caCert) != "FAKE CA" {
		t.Fatalf("ca cert not written correctly: %v %q", err, caCert)
	}
}

func TestEnsureRegisteredSkipsIfAlreadyRegistered(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "agent.crt")
	if err := os.WriteFile(certPath, []byte("existing"), 0o644); err != nil {
		t.Fatal(err)
	}
	called := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
	}))
	defer srv.Close()

	cfg := config.AgentConfig{ClientCert: certPath, BootstrapURL: srv.URL}
	if err := ensureRegistered(cfg); err != nil {
		t.Fatal(err)
	}
	if called {
		t.Fatal("bootstrap server must not be called when a cert already exists")
	}
}

func TestEnsureRegisteredErrorsWithNoBootstrapURL(t *testing.T) {
	dir := t.TempDir()
	cfg := config.AgentConfig{ClientCert: filepath.Join(dir, "agent.crt")}
	if err := ensureRegistered(cfg); err == nil {
		t.Fatal("want an error when no cert exists and bootstrap_url is empty")
	}
}
```

- [ ] **Step 3: Run to confirm it fails**

Run: `cd bridge && go test ./cmd/cmux-bridge/... -run TestEnsureRegistered`
Expected: FAIL — `ensureRegistered` undefined.

- [ ] **Step 4: Write `register.go`**

Create `bridge/cmd/cmux-bridge/register.go`:

```go
package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"os"
	"path/filepath"

	"github.com/sodre90/cmux-bridge/internal/config"
)

type registerResp struct {
	TenantID string `json:"tenant_id"`
	CertPEM  string `json:"cert_pem"`
	CAPEM    string `json:"ca_pem"`
}

// ensureRegistered generates a keypair and self-registers with the relay's
// bootstrap endpoint if cfg.ClientCert doesn't already exist on disk. It is a
// no-op once registration has happened once — an agent identity, once
// minted, is reused for the agent's lifetime.
func ensureRegistered(cfg config.AgentConfig) error {
	if _, err := os.Stat(cfg.ClientCert); err == nil {
		return nil
	}
	if cfg.BootstrapURL == "" {
		return fmt.Errorf("no client_cert on disk and bootstrap_url is empty — set one in agent.toml")
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("generate agent key: %w", err)
	}
	csrTmpl := &x509.CertificateRequest{Subject: pkix.Name{CommonName: "pending"}}
	csrDER, err := x509.CreateCertificateRequest(rand.Reader, csrTmpl, key)
	if err != nil {
		return fmt.Errorf("create CSR: %w", err)
	}
	csrPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER})

	body, err := json.Marshal(map[string]string{"csr": string(csrPEM)})
	if err != nil {
		return err
	}
	resp, err := http.Post(cfg.BootstrapURL, "application/json", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("register with relay: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("register with relay: status %d", resp.StatusCode)
	}
	var rr registerResp
	if err := json.NewDecoder(resp.Body).Decode(&rr); err != nil {
		return fmt.Errorf("parse register response: %w", err)
	}

	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	if err := writeNew(cfg.ClientCert, []byte(rr.CertPEM), 0o644); err != nil {
		return err
	}
	if err := writeNew(cfg.ClientKey, keyPEM, 0o600); err != nil {
		return err
	}
	if err := writeNew(cfg.CACert, []byte(rr.CAPEM), 0o644); err != nil {
		return err
	}
	fmt.Printf("agent: registered as tenant %s (cert written to %s)\n", rr.TenantID, cfg.ClientCert)
	return nil
}

func writeNew(path string, data []byte, perm os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, data, perm)
}
```

- [ ] **Step 5: Wire it into `runAgent`**

In `bridge/cmd/cmux-bridge/agent.go`, in `runAgent`, right after `cfg, err := config.LoadAgent(*cfgPath)` and its error check, before the `loadTLS` call, add:

```go
	if err := ensureRegistered(cfg); err != nil {
		log.Printf("agent: %v", err)
		return 1
	}
```

- [ ] **Step 6: Run to confirm all pass**

Run: `cd bridge && go test ./cmd/cmux-bridge/... -v`
Expected: PASS, including the two new `TestEnsureRegistered*` tests and the existing `TestNextBackoffCaps`/`TestLoadTLS*` tests.

- [ ] **Step 7: Document the new field in the example config**

In `bridge/deploy/agent.example.toml`, add a commented example near the other fields:

```toml
# bootstrap_url = "https://cmux.example.com:8444/tenants/register"  # used once, on first run
```

- [ ] **Step 8: Commit**

```bash
git add bridge/cmd/cmux-bridge/register.go bridge/cmd/cmux-bridge/register_test.go \
        bridge/cmd/cmux-bridge/agent.go bridge/internal/config/agent.go \
        bridge/internal/config/agent_test.go bridge/deploy/agent.example.toml
git commit -m "agent: self-register with the relay's bootstrap endpoint on first run"
```

---

### Task 7: `cmux-relay` CLI — tenant admin commands and tenant-scoped pairing

**Files:**
- Modify: `bridge/cmd/cmux-relay/commands.go`
- Modify: `bridge/cmd/cmux-relay/main.go`

**Interfaces:**
- Consumes: `Store.CreateTenant`, `TenantActive`, `RevokeTenant`, `ListTenants`, `Issue(tenantID, name)`, `List() []Device` with `Device.HashSuffix`/`TenantID` (all from Task 1).

- [ ] **Step 1: Update `commands.go`**

Replace `bridge/cmd/cmux-relay/commands.go`:

```go
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/sodre90/cmux-bridge/internal/cli"
)

func runPair(args []string) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	tenant := fs.String("tenant", "", "tenant id this device belongs to (see: cmux-relay tenants list)")
	name := fs.String("name", "phone", "a label for this device")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *tenant == "" {
		fmt.Fprintln(os.Stderr, "pair: -tenant is required (see: cmux-relay tenants list)")
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	if !store.TenantActive(*tenant) {
		fmt.Fprintf(os.Stderr, "pair: no active tenant %q\n", *tenant)
		return 1
	}
	tok, err := store.Issue(*tenant, *name)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	fmt.Printf("\nDevice token for %q (tenant %s, paste into the app once):\n\n    %s\n\n", *name, *tenant, tok)
	fmt.Println("Keep it secret. Revoke later with: cmux-relay devices revoke <token>")
	return 0
}

func runDevices(args []string) int {
	fs := flag.NewFlagSet("devices", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("devices: %v", err)
		return 1
	}
	rest := fs.Args()
	switch {
	case len(rest) == 0 || rest[0] == "list":
		devs := store.List()
		if len(devs) == 0 {
			fmt.Println("no paired devices")
			return 0
		}
		for _, d := range devs {
			fmt.Printf("%-16s  tenant=%s  token=...%s  fcm=%v  created=%s\n",
				d.Name, d.TenantID, d.HashSuffix, d.FCM != "", d.Created.Format(time.RFC3339))
		}
		return 0
	case rest[0] == "revoke":
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "usage: cmux-relay devices revoke <token>")
			return 2
		}
		if store.Revoke(rest[1]) {
			fmt.Println("revoked")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such token")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-relay devices [list|revoke <token>]")
		return 2
	}
}

func runTenants(args []string) int {
	fs := flag.NewFlagSet("tenants", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("tenants: %v", err)
		return 1
	}
	rest := fs.Args()
	switch {
	case len(rest) == 0 || rest[0] == "list":
		tenants, err := store.ListTenants()
		if err != nil {
			log.Printf("tenants: %v", err)
			return 1
		}
		if len(tenants) == 0 {
			fmt.Println("no tenants")
			return 0
		}
		for _, t := range tenants {
			fmt.Printf("%s  created=%s  revoked=%v\n", t.ID, t.CreatedAt.Format(time.RFC3339), t.Revoked)
		}
		return 0
	case rest[0] == "revoke":
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "usage: cmux-relay tenants revoke <id>")
			return 2
		}
		if store.RevokeTenant(rest[1]) {
			fmt.Println("revoked (this also stops all of that tenant's devices from verifying)")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such tenant, or already revoked")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-relay tenants [list|revoke <id>]")
		return 2
	}
}
```

- [ ] **Step 2: Wire `tenants` into `main.go`**

In `bridge/cmd/cmux-relay/main.go`, add a case and update usage:

```go
func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "serve":
		os.Exit(runServe(os.Args[2:]))
	case "pair":
		os.Exit(runPair(os.Args[2:]))
	case "devices":
		os.Exit(runDevices(os.Args[2:]))
	case "tenants":
		os.Exit(runTenants(os.Args[2:]))
	case "version", "--version", "-v":
		fmt.Println("cmux-relay", version.String())
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-relay <serve|pair|devices|tenants|version> [flags]")
}
```

- [ ] **Step 3: Manual smoke test**

Run:
```bash
cd bridge && go build -o /tmp/cmux-relay ./cmd/cmux-relay
/tmp/cmux-relay tenants list -config /tmp/does-not-exist.toml
```
Expected: prints "no tenants" (a fresh store at the default/missing-config path has none) and exits 0.

- [ ] **Step 4: Commit**

```bash
git add bridge/cmd/cmux-relay/
git commit -m "cli: add tenants list/revoke, scope pair/devices by tenant"
```

---

### Task 8: Adversarial cross-tenant isolation test

This is the test that proves the property the whole plan exists for: one tenant's device token can never reach another tenant's session, even when both are connected to the same relay process at once.

**Amended after first run (see "Mid-execution fix" #2 in the Self-review notes below):** the first implementer ran this test verbatim and it failed — `TestRelayIsolatesTenants` showed tenant B's device receiving tenant A's data. Root cause: `bridge/internal/relay/proxy.go`'s `newProxy` builds an `http.Transport` whose `Director` sets `req.URL.Host = "agent"` — the same constant string for every tenant. Go's `http.Transport` pools idle connections keyed by that host string alone, regardless of the custom `DialContext`'s per-request tenant resolution. Once tenant A's request opens a connection, it sits in the idle pool and can be handed to tenant B's next request without `DialContext` (and its `TenantActive`/registry lookup) ever running again — B's request rides A's already-open stream and gets A's data back. This task's scope now includes fixing that, in `proxy.go`, not just `bridge/internal/relay/multitenant_test.go` — the task's deliverable is "prove isolation holds," which isn't met by a test that merely exists.

**Files:**
- Create: `bridge/internal/relay/multitenant_test.go`
- Modify: `bridge/internal/relay/proxy.go` (fix: disable connection reuse so every request re-resolves its tenant)

**Interfaces:** none new — exercises `New`, `Registry`, `Store` end-to-end via real HTTP.

- [ ] **Step 1: Write the test**

Create `bridge/internal/relay/multitenant_test.go`:

```go
package relay

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/testutil"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

func TestRelayIsolatesTenants(t *testing.T) {
	const wsA = `{"workspaces":[{"id":"A","current_directory":"/a","preview":"tenant-a-secret","terminals":[{"id":"A-T1","current_directory":"/a","title":"~/a","is_focused":true,"is_ready":true}]}]}`
	const wsB = `{"workspaces":[{"id":"B","current_directory":"/b","preview":"tenant-b-secret","terminals":[{"id":"B-T1","current_directory":"/b","title":"~/b","is_focused":true,"is_ready":true}]}]}`
	binA := testutil.WriteFakeCmux(t, "#!/bin/sh\ncat <<'JSON'\n"+wsA+"\nJSON\n")
	binB := testutil.WriteFakeCmux(t, "#!/bin/sh\ncat <<'JSON'\n"+wsB+"\nJSON\n")
	const relayTok = "relay-secret"
	agentA := server.New(config.Config{}, &cmux.Client{Bin: binA}, nil).TrustedHandler(relayTok)
	agentB := server.New(config.Config{}, &cmux.Client{Bin: binB}, nil).TrustedHandler(relayTok)

	store, err := auth.Open(filepath.Join(t.TempDir(), "r.db"))
	if err != nil {
		t.Fatal(err)
	}
	tenantA, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tenantB, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	devA, _ := store.Issue(tenantA, "phone-a")
	devB, _ := store.Issue(tenantB, "phone-b")

	rl := New(store, nil, relayTok)
	relayHTTP := httptest.NewServer(rl.Handler())
	defer relayHTTP.Close()

	dial := func(tenantID string, h http.Handler) {
		u := "ws" + strings.TrimPrefix(relayHTTP.URL, "http") + "/agent/tunnel"
		sess, err := tunnel.Dial(context.Background(), u, nil, http.Header{"X-Client-Cert-Cn": {"CN=agent:" + tenantID}})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { sess.Close() })
		go func() { _ = http.Serve(sess, h) }()
	}
	dial(tenantA, agentA)
	dial(tenantB, agentB)

	waitFor(t, func() bool { return rl.reg.Get(tenantA) != nil && rl.reg.Get(tenantB) != nil })

	fetch := func(token string) string {
		req, _ := http.NewRequest("GET", relayHTTP.URL+"/sessions", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		return string(body)
	}

	bodyA := fetch(devA)
	if !strings.Contains(bodyA, "tenant-a-secret") {
		t.Fatalf("tenant A's device should see tenant A's data: %s", bodyA)
	}
	if strings.Contains(bodyA, "tenant-b-secret") {
		t.Fatalf("tenant A's device must never see tenant B's data: %s", bodyA)
	}

	bodyB := fetch(devB)
	if !strings.Contains(bodyB, "tenant-b-secret") {
		t.Fatalf("tenant B's device should see tenant B's data: %s", bodyB)
	}
	if strings.Contains(bodyB, "tenant-a-secret") {
		t.Fatalf("tenant B's device must never see tenant A's data: %s", bodyB)
	}
}

func TestRelayRevokedTenantCannotReconnectOrServeDevices(t *testing.T) {
	store, err := auth.Open(filepath.Join(t.TempDir(), "r.db"))
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	devTok, _ := store.Issue(tenantID, "phone")
	store.RevokeTenant(tenantID)

	rl := New(store, nil, "relay-secret")
	relayHTTP := httptest.NewServer(rl.Handler())
	defer relayHTTP.Close()

	req, _ := http.NewRequest("GET", relayHTTP.URL+"/agent/tunnel", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("revoked tenant's agent must be refused a tunnel, got %d", resp.StatusCode)
	}

	req2, _ := http.NewRequest("GET", relayHTTP.URL+"/sessions", nil)
	req2.Header.Set("Authorization", "Bearer "+devTok)
	resp2, err := http.DefaultClient.Do(req2)
	if err != nil {
		t.Fatal(err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusUnauthorized {
		t.Fatalf("a revoked tenant's device token must stop verifying, got %d", resp2.StatusCode)
	}
}
```

- [ ] **Step 2: Run it, confirm the pre-existing leak, then fix `proxy.go`**

Run: `cd bridge && go test ./internal/relay/... -run TestRelayIsolatesTenants -v`
Expected (before the fix below): FAIL — tenant B's fetch contains `"tenant-a-secret"`.

In `bridge/internal/relay/proxy.go`, add `DisableKeepAlives: true` to the `http.Transport` inside `newProxy`, so every proxied request is forced through a fresh `DialContext` call (and therefore a fresh `TenantActive`/registry lookup) instead of potentially reusing another tenant's pooled connection:

```go
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
```

- [ ] **Step 3: Run to confirm it passes**

Run: `cd bridge && go test ./internal/relay/... -run TestRelayIsolatesTenants -v && go test ./internal/relay/... -run TestRelayRevokedTenant -v`
Expected: PASS for both.

- [ ] **Step 4: Run the full suite one more time**

Run: `cd bridge && go build ./... && go vet ./... && go test ./...`
Expected: PASS, no vet warnings.

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/relay/multitenant_test.go bridge/internal/relay/proxy.go
git commit -m "relay: add adversarial cross-tenant isolation test, fix connection-pool leak it found"
```

---

### Task 9: Update docs for the multi-tenant model

**Files:**
- Modify: `bridge/README.md`
- Modify: `README.md` (repo root)

**Interfaces:** none (documentation).

- [ ] **Step 1: Update `bridge/README.md`'s pairing/relay sections**

Find the section describing `cmux-relay pair` and the relay's device/agent-CN model (matches today's "Pair a device" and "Relay (home server)" headings). Update it to describe:
- The relay auto-generates its own CA at `~/.config/cmux-relay/ca.crt`/`ca.key` on first run (no more hand-rolled `openssl` CA for agents).
- A new Mac agent self-registers the first time it runs, via `bootstrap_url` in `agent.toml` pointed at the no-mTLS bootstrap vhost (`nginx-cmux-relay-bootstrap.conf`, port 8444 in the example) — it prints the tenant ID it was assigned on success.
- Pairing a phone is still operator-driven for now: `cmux-relay pair -tenant <id> -name <name>` (tenant IDs come from `cmux-relay tenants list`). Device certs (`.p12`) are still generated by hand with `openssl`, signed against the relay's new CA files instead of a separately hand-rolled one — the openssl invocation itself is unchanged apart from pointing `-CA`/`-CAkey` at `~/.config/cmux-relay/ca.crt`/`ca.key`.
- Self-service phone pairing (QR code, no manual cert wrangling) and end-to-end content encryption are tracked in a follow-up spec/plan, not yet implemented — link `docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md`.
- Add `cmux-relay tenants list` / `cmux-relay tenants revoke <id>` to the CLI reference alongside the existing `pair`/`devices` commands.

- [ ] **Step 2: Update the root `README.md`**

In the "Security model" section, add a bullet:

```markdown
- **Per-tenant isolation** — the relay serves many independent Mac agents at
  once; each gets its own client cert (`CN=agent:<tenant-id>`) and its own
  tunnel slot, and a device's bearer token is scoped to exactly one tenant.
  A bug in one tenant's traffic can't spill into another's — enforced by an
  adversarial test (`internal/relay/multitenant_test.go`), not just by
  convention.
```

In the "How it fits together" section, mention that `cmux-relay` is now its own CA and that agents self-register.

- [ ] **Step 3: Commit**

```bash
git add bridge/README.md README.md
git commit -m "docs: describe the multi-tenant relay model"
```

---

## Self-review notes (for the plan author, not a task to execute)

- **Spec coverage:** Layer 1 (transport/routing) of the design spec is fully covered — per-tenant registry and per-tenant certs via the relay's own CA (Task 2, 3), tenant-scoped device tokens with hashing (Task 1), tenant-scoped FCM push fanout (Task 4), agent self-registration (Task 5, 6), revocation (Task 1, 7), and the adversarial isolation test the spec explicitly calls for (Task 8). Layer 2 (E2E content encryption, QR pairing) is intentionally **not** covered — it's the next plan, built on top of this one.
- **Explicit limitation carried forward from the spec:** no per-cert-serial revocation with stable tenant-ID preservation; re-registration mints a new tenant ID. Documented in Global Constraints and in Task 1's `RecordAgentCert` comment.
- **Type consistency check:** `Device.TenantID`/`HashSuffix` (Task 1) match their use in `proxy.go`'s `dev.TenantID` (Task 3) and `commands.go`'s `d.TenantID`/`d.HashSuffix` (Task 7). `relay.New(store, signer, relayToken)` (Task 3) matches its call sites in `relay_test.go`, `multitenant_test.go` (Task 8), and `cmd/cmux-relay/serve.go` (Task 3, then re-touched by Task 4's `SetSessionHook` signature change). `Registry.Get/Set/Clear(tenantID, ...)` (Task 3) matches all call sites in `relay.go` and both test files, all landing together within Task 3 itself.
- **Pre-flight fix (before dispatch):** the original draft split registry.go (old Task 3) from relay.go/proxy.go/config.go/serve.go (old Task 4) as if independently testable — they are not, since registry.go and relay.go share a package and relay.go calls Registry's changed methods immediately. Merged into one task before dispatch; see Task 3's intro paragraph.
- **Second pre-flight fix (found while briefing Task 3, before dispatch):** Task 3's original build/test step ran `go build ./... && go test ./...` and expected a full pass, but `cmd/cmux-relay/commands.go`/`main.go` (the CLI task's files, not Task 3's) still call the pre-multi-tenant `Store.Issue(name)`/`Device.Token` API removed in Task 1 — that package cannot compile until the CLI task lands. Scoped the step to `./internal/...` only, with an explicit note on the expected gap. Same category of defect as the first pre-flight fix (an assumed build/test boundary that doesn't actually hold), just depending on a task several steps further out instead of the immediately-adjacent one.
- **Mid-execution fix (found by Task 3's task reviewer, after Task 3 was implemented and approved):** `pushmon.go`'s attention-push fanout called `Store.FCMTokens()`, scoped across **all** tenants, with no tenant identity threaded through `Relay.SetSessionHook`/`MonitorAgent` — so tenant A's agent events would have paged every tenant's phones, not just tenant A's. This is a direct violation of the project's core isolation guarantee and wasn't caught by the original 8-task plan (no task touched `pushmon.go`). Added as Task 4, inserted immediately after Task 3 (the task that owns the session-hook wiring this fix threads a tenant ID through) and before the previously-Task-4 nginx work, renumbering everything after it up by one. Unlike the two pre-flight fixes above, this one only surfaced once real code existed for a reviewer to read — a static plan read couldn't have caught it, which is exactly why the per-task review loop exists.
- **Mid-execution fix #2 (found by Task 8's own adversarial test, exactly as designed):** `proxy.go`'s `newProxy` (Task 3) sets `req.URL.Host = "agent"` — a constant, identical for every tenant — as the `Director`'s placeholder target. Go's `http.Transport` pools idle connections keyed by that host string alone, independent of the custom `DialContext`'s per-request tenant resolution. Once tenant A's request opened a connection, it could sit in the idle pool and be handed to tenant B's next request without `DialContext` (and its tenant check) ever running again, serving B tenant A's data. Confirmed via `TestRelayIsolatesTenants` failing exactly this way on first run. User decided (2026-07-01, via AskUserQuestion) to fix this within Task 8 itself rather than spin out a new task, since Task 8's whole deliverable is proving this property holds — amended Task 8's Files/Steps in place (see the note at the top of Task 8) instead of renumbering. Fix: `DisableKeepAlives: true` on the proxy's `http.Transport`, forcing a fresh `DialContext` call — and therefore a fresh tenant check — on every single proxied request; no connection is ever reused across requests, so no connection can ever be reused across tenants either.
