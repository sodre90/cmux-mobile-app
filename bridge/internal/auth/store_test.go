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
