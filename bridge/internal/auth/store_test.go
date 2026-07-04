// bridge/internal/auth/store_test.go
package auth

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"
)

const testPubkey = "test-device-pubkey-b64"

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
	tok, err := s.Issue(tenant, "phone", testPubkey)
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
	if dev.DevicePubkey != testPubkey {
		t.Fatalf("Verify DevicePubkey = %q want %q", dev.DevicePubkey, testPubkey)
	}
	if dev.TokenHash == "" || len(dev.TokenHash) != 64 {
		t.Fatalf("Verify TokenHash should be a full 64-char hex digest, got %q", dev.TokenHash)
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

func TestIssueRequiresDevicePubkey(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	if _, err := s.Issue(tenant, "phone", ""); err == nil {
		t.Fatal("Issue with an empty device pubkey must return an error")
	}
}

func TestVerifyFailsClosedWhenTenantRevoked(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
	s.RevokeTenant(tenant)
	if _, ok := s.Verify(tok); ok {
		t.Fatal("a device token must stop verifying once its tenant is revoked")
	}
}

func TestTokensAreHashedAtRest(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
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
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	tok, gotTenant, ok := s.RedeemPairingCode(code, "phone", testPubkey)
	if !ok || tok == "" {
		t.Fatal("first redeem should succeed")
	}
	if gotTenant != tenant {
		t.Fatalf("redeemed tenant = %q want %q", gotTenant, tenant)
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); ok {
		t.Fatal("reuse of a code must fail")
	}
	if _, ok := s.Verify(tok); !ok {
		t.Fatal("redeemed token should verify")
	}
}

func TestRedeemPairingCodeRequiresDevicePubkey(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone", ""); ok {
		t.Fatal("redeeming with an empty device pubkey must fail")
	}
}

func TestPairingCodeExpiry(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, _ := s.NewPairingCode(tenant, testPubkey, -time.Second) // already expired
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); ok {
		t.Fatal("expired code must fail")
	}
}

func TestPairingCodeStatusReflectsRedemption(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	if _, _, redeemed, ok := s.PairingCodeStatus(tenant, code); !ok || redeemed {
		t.Fatalf("fresh code should exist and be unredeemed, got redeemed=%v ok=%v", redeemed, ok)
	}

	tok, _, ok := s.RedeemPairingCode(code, "phone", testPubkey)
	if !ok {
		t.Fatal("redeem should succeed")
	}

	pub, hash, redeemed, ok := s.PairingCodeStatus(tenant, code)
	if !ok || !redeemed {
		t.Fatalf("code should report redeemed, got ok=%v redeemed=%v", ok, redeemed)
	}
	if pub != testPubkey {
		t.Fatalf("PairingCodeStatus pubkey = %q want %q", pub, testPubkey)
	}
	wantHash := hashToken(tok)
	if hash != wantHash {
		t.Fatalf("PairingCodeStatus tokenHash = %q want %q", hash, wantHash)
	}
}

func TestPairingCodeStatusUnknownCode(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	if _, _, _, ok := s.PairingCodeStatus(tenant, "nonexistent"); ok {
		t.Fatal("unknown code should report ok=false")
	}
}

func TestPairingCodeStatusScopedToTenant(t *testing.T) {
	s := newStore(t)
	tenantA := newTenant(t, s)
	tenantB := newTenant(t, s)
	code, err := s.NewPairingCode(tenantA, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, ok := s.PairingCodeStatus(tenantB, code); ok {
		t.Fatal("a pairing code must not be visible under a different tenant id")
	}
}

func TestPairingCodeInfoReturnsAgentPubkeyUnscoped(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	// Unlike PairingCodeStatus, PairingCodeInfo takes no tenant argument --
	// the caller (a phone pairing manually) doesn't know its tenant yet.
	agentPubkey, gotTenant, expiresAt, ok := s.PairingCodeInfo(code)
	if !ok {
		t.Fatal("fresh code should resolve")
	}
	if agentPubkey != testPubkey {
		t.Fatalf("agentPubkey = %q, want %q", agentPubkey, testPubkey)
	}
	if gotTenant != tenant {
		t.Fatalf("tenant = %q, want %q", gotTenant, tenant)
	}
	if expiresAt == "" {
		t.Fatal("expected a non-empty expiresAt")
	}
}

func TestPairingCodeInfoUnknownCode(t *testing.T) {
	s := newStore(t)
	if _, _, _, ok := s.PairingCodeInfo("nonexistent"); ok {
		t.Fatal("unknown code should report ok=false")
	}
}

func TestPairingCodeInfoExpiredCode(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, -time.Second) // already expired
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, ok := s.PairingCodeInfo(code); ok {
		t.Fatal("expired code must not resolve")
	}
}

func TestPairingCodeInfoRedeemedCode(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); !ok {
		t.Fatal("redeem should succeed")
	}
	if _, _, _, ok := s.PairingCodeInfo(code); ok {
		t.Fatal("an already-redeemed code must not resolve, matching single-use semantics")
	}
}

func TestPersistenceAcrossReopen(t *testing.T) {
	p := filepath.Join(t.TempDir(), "d.db")
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
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
	tok, _ := s.Issue(tenant, "phone", testPubkey)
	list := s.List()
	if len(list) != 1 {
		t.Fatalf("want 1 device, got %d", len(list))
	}
	if list[0].HashSuffix == "" || len(list[0].HashSuffix) != 6 {
		t.Fatalf("want a 6-char hash suffix, got %q", list[0].HashSuffix)
	}
	if list[0].DevicePubkey != testPubkey {
		t.Fatalf("List DevicePubkey = %q want %q", list[0].DevicePubkey, testPubkey)
	}
	for _, want := range []string{tok, tok[len(tok)-6:]} {
		if list[0].HashSuffix == want {
			t.Fatal("List must never expose anything derived from the raw token")
		}
	}
}

func TestTenantFCMDevicesScopedPerTenant(t *testing.T) {
	s := newStore(t)
	tenantA := newTenant(t, s)
	tenantB := newTenant(t, s)
	tokA, _ := s.Issue(tenantA, "phone-a", testPubkey)
	tokB, _ := s.Issue(tenantB, "phone-b", testPubkey)

	if got := s.TenantFCMDevices(tenantA); len(got) != 0 {
		t.Fatalf("expected no FCM devices yet, got %v", got)
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

	gotA := s.TenantFCMDevices(tenantA)
	if len(gotA) != 1 || gotA[0].FCMToken != "fcm-a" || gotA[0].DeviceID != hashToken(tokA) {
		t.Fatalf("tenantA devices = %+v, want [{%s fcm-a}]", gotA, hashToken(tokA))
	}
	gotB := s.TenantFCMDevices(tenantB)
	if len(gotB) != 1 || gotB[0].FCMToken != "fcm-b" || gotB[0].DeviceID != hashToken(tokB) {
		t.Fatalf("tenantB devices = %+v, want [{%s fcm-b}]", gotB, hashToken(tokB))
	}
}

func TestTenantFCMDevicesDedupesRepeatedPairingsKeepingNewest(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)

	tok1, _ := s.Issue(tenant, "phone", testPubkey)
	tok2, _ := s.Issue(tenant, "phone", testPubkey)
	tok3, _ := s.Issue(tenant, "phone", testPubkey)
	if !s.SetFCMToken(tok1, "fcm-shared") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}
	if !s.SetFCMToken(tok2, "fcm-shared") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}
	if !s.SetFCMToken(tok3, "fcm-shared") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}

	got := s.TenantFCMDevices(tenant)
	if len(got) != 1 || got[0].FCMToken != "fcm-shared" {
		t.Fatalf("TenantFCMDevices = %+v, want exactly one [{_ fcm-shared}] despite 3 device rows sharing it", got)
	}
	if got[0].DeviceID != hashToken(tok3) {
		t.Fatalf("TenantFCMDevices deviceID = %q, want the newest row's %q (tok3) -- its shared secret is the one still live on the phone", got[0].DeviceID, hashToken(tok3))
	}
}

func TestOpenCreatesParentDirectory(t *testing.T) {
	// Verify that Open creates parent directories that don't exist yet.
	// This is a regression test: the old JSON-based store called os.MkdirAll
	// before persisting, and failing to do so causes fresh invocations to crash
	// when the config directory doesn't exist.
	path := filepath.Join(t.TempDir(), "nested", "does-not-exist-yet", "store.db")
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open with non-existent parent dirs: %v", err)
	}

	// Verify the store is usable by calling a cheap read method.
	list, err := s.ListTenants()
	if err != nil {
		t.Fatalf("ListTenants after Open: %v", err)
	}
	if len(list) != 0 {
		t.Fatalf("fresh store should have zero tenants, got %d", len(list))
	}
}

func TestMigrationAddsDevicePubkeyColumn(t *testing.T) {
	path := filepath.Join(t.TempDir(), "d.db")
	// Simulate a pre-migration database file: apply only the original schema
	// (no device_pubkey / pairing-code columns), bypassing Open (which always
	// applies both schema and migrations).
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(schema); err != nil {
		t.Fatal(err)
	}
	db.Close()

	// Opening through the real Open must apply the migration without error,
	// even though the file already exists and predates the new columns.
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open on pre-migration db: %v", err)
	}
	tenant, err := s.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tok, err := s.Issue(tenant, "phone", testPubkey)
	if err != nil {
		t.Fatalf("Issue after migration: %v", err)
	}
	dev, ok := s.Verify(tok)
	if !ok {
		t.Fatal("issued token should verify after migration")
	}
	if dev.DevicePubkey != testPubkey {
		t.Fatalf("DevicePubkey = %q, want %q", dev.DevicePubkey, testPubkey)
	}
}

func TestMigrationAddsAgentPubkeyColumn(t *testing.T) {
	path := filepath.Join(t.TempDir(), "d.db")
	// Simulate a pre-migration database file: apply only the original schema
	// (no agent_pubkey column), bypassing Open (which always applies both
	// schema and migrations).
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(schema); err != nil {
		t.Fatal(err)
	}
	db.Close()

	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open on pre-migration db: %v", err)
	}
	tenant, err := s.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatalf("NewPairingCode after migration: %v", err)
	}
	agentPubkey, _, _, ok := s.PairingCodeInfo(code)
	if !ok || agentPubkey != testPubkey {
		t.Fatalf("PairingCodeInfo after migration = (%q, ok=%v), want (%q, true)", agentPubkey, ok, testPubkey)
	}
}
