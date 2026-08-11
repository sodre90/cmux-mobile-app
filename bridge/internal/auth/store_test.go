// bridge/internal/auth/store_test.go
package auth

import (
	"database/sql"
	"errors"
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
	dev, err := s.Verify(tok)
	if err != nil {
		t.Fatalf("issued token should verify: %v", err)
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
	if _, err := s.Verify("bogus"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("bogus token must not verify, got err=%v", err)
	}
	if _, err := s.Verify(""); !errors.Is(err, ErrNotFound) {
		t.Fatalf("empty token must not verify, got err=%v", err)
	}
	if err := s.Revoke(tok); err != nil {
		t.Fatalf("revoke should report removal: %v", err)
	}
	if _, err := s.Verify(tok); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked token must not verify, got err=%v", err)
	}
	if err := s.Revoke(tok); !errors.Is(err, ErrNotFound) {
		t.Fatalf("double revoke should report ErrNotFound, got err=%v", err)
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
	if _, err := s.Verify(tok); !errors.Is(err, ErrNotFound) {
		t.Fatalf("a device token must stop verifying once its tenant is revoked, got err=%v", err)
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
	if _, err := s.Verify(tok); err != nil {
		t.Fatalf("redeemed token should verify: %v", err)
	}
}

// cmux-app-af1: the token is minted at redemption, before the operator is
// ever asked to confirm the fingerprint. Refusing therefore has to destroy a
// credential that already exists and already works.
func TestAbortPairingDestroysTheRedeemedToken(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	tok, _, ok := s.RedeemPairingCode(code, "phone", testPubkey)
	if !ok {
		t.Fatal("redeem should succeed")
	}
	if _, err := s.Verify(tok); err != nil {
		t.Fatalf("token should verify before the abort: %v", err)
	}

	revoked, err := s.AbortPairing(tenant, code)
	if err != nil {
		t.Fatalf("AbortPairing: %v", err)
	}
	if !revoked {
		t.Fatal("AbortPairing must report that it destroyed a token the phone was holding")
	}
	if _, err := s.Verify(tok); !errors.Is(err, ErrNotFound) {
		t.Fatalf("the refused pairing's token still verifies: %v", err)
	}
	// The row itself now survives the abort so the waiting phone has
	// something to read, so what has to hold is that it is unredeemable and
	// says refused -- not that it is gone.
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); ok {
		t.Fatal("an aborted code must not survive to be redeemed again")
	}
	if state, ok := s.PairingConfirmationState(code, time.Minute); !ok || state != PairingRefused {
		t.Fatalf("the phone must be able to read the refusal: state=%q ok=%v", state, ok)
	}
}

// An abort that lands before the phone ever redeemed still burns the code --
// otherwise the operator walks away and a scanned QR can mint a token
// nobody is left watching for.
func TestAbortPairingBeforeRedemptionRevokesNothingButKillsTheCode(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	revoked, err := s.AbortPairing(tenant, code)
	if err != nil {
		t.Fatalf("AbortPairing: %v", err)
	}
	if revoked {
		t.Fatal("nothing had been issued yet, so nothing can have been revoked")
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); ok {
		t.Fatal("an aborted code must no longer be redeemable")
	}
}

// The same isolation PairingCodeStatus enforces: a code is not a capability
// another tenant's agent can act on by guessing it.
func TestAbortPairingIsTenantScoped(t *testing.T) {
	s := newStore(t)
	owner := newTenant(t, s)
	stranger := newTenant(t, s)
	code, err := s.NewPairingCode(owner, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	tok, _, ok := s.RedeemPairingCode(code, "phone", testPubkey)
	if !ok {
		t.Fatal("redeem should succeed")
	}

	if _, err := s.AbortPairing(stranger, code); !errors.Is(err, ErrNotFound) {
		t.Fatalf("another tenant must not be able to abort this pairing, got %v", err)
	}
	if _, err := s.Verify(tok); err != nil {
		t.Fatalf("the owner's token must survive a stranger's abort: %v", err)
	}
	if _, err := s.AbortPairing(owner, "NOSUCHCODE"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("an unknown code must report not-found, got %v", err)
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
	if _, err := s2.Verify(tok); err != nil {
		t.Fatalf("token must survive reopening the database file: %v", err)
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
	if err := s.SetFCMToken(tokA, "fcm-a"); err != nil {
		t.Fatalf("SetFCMToken should succeed for a known device: %v", err)
	}
	if err := s.SetFCMToken(tokB, "fcm-b"); err != nil {
		t.Fatalf("SetFCMToken should succeed for a known device: %v", err)
	}
	if err := s.SetFCMToken("bogus", "x"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("SetFCMToken must fail for unknown device, got err=%v", err)
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
	if err := s.SetFCMToken(tok1, "fcm-shared"); err != nil {
		t.Fatalf("SetFCMToken should succeed for a known device: %v", err)
	}
	if err := s.SetFCMToken(tok2, "fcm-shared"); err != nil {
		t.Fatalf("SetFCMToken should succeed for a known device: %v", err)
	}
	if err := s.SetFCMToken(tok3, "fcm-shared"); err != nil {
		t.Fatalf("SetFCMToken should succeed for a known device: %v", err)
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
	dev, err := s.Verify(tok)
	if err != nil {
		t.Fatalf("issued token should verify after migration: %v", err)
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

// redeemedPairing sets up the state every confirmation test starts from: a
// code that a phone has redeemed and whose operator has not yet answered.
func redeemedPairing(t *testing.T, s *Store, tenant string) string {
	t.Helper()
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); !ok {
		t.Fatal("redeem should succeed")
	}
	return code
}

func TestConfirmPairingIsWhatMovesAPairingOutOfPending(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code := redeemedPairing(t, s, tenant)

	if state, ok := s.PairingConfirmationState(code, time.Minute); !ok || state != PairingPending {
		t.Fatalf("a redeemed, unanswered pairing = (%q, ok=%v), want pending", state, ok)
	}
	if err := s.ConfirmPairing(tenant, code, time.Minute); err != nil {
		t.Fatalf("ConfirmPairing: %v", err)
	}
	if state, ok := s.PairingConfirmationState(code, time.Minute); !ok || state != PairingConfirmed {
		t.Fatalf("after confirming = (%q, ok=%v), want confirmed", state, ok)
	}
}

// The agent's confirm is a plain HTTP POST it may legitimately retry, so a
// second one must not turn a good pairing into an error.
func TestConfirmPairingIsIdempotent(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code := redeemedPairing(t, s, tenant)

	if err := s.ConfirmPairing(tenant, code, time.Minute); err != nil {
		t.Fatalf("first ConfirmPairing: %v", err)
	}
	if err := s.ConfirmPairing(tenant, code, time.Minute); err != nil {
		t.Fatalf("a repeated confirm must be a no-op, got %v", err)
	}
}

// The same isolation AbortPairing enforces: confirming is not a capability
// another tenant's agent can exercise by guessing a code.
func TestConfirmPairingIsTenantScoped(t *testing.T) {
	s := newStore(t)
	owner := newTenant(t, s)
	stranger := newTenant(t, s)
	code := redeemedPairing(t, s, owner)

	if err := s.ConfirmPairing(stranger, code, time.Minute); !errors.Is(err, ErrNotFound) {
		t.Fatalf("a stranger confirming another tenant's pairing = %v, want ErrNotFound", err)
	}
	if state, _ := s.PairingConfirmationState(code, time.Minute); state != PairingPending {
		t.Fatalf("the stranger's attempt changed the row: state=%q", state)
	}
}

func TestConfirmPairingCannotOverrideARefusal(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code := redeemedPairing(t, s, tenant)
	if _, err := s.AbortPairing(tenant, code); err != nil {
		t.Fatal(err)
	}

	if err := s.ConfirmPairing(tenant, code, time.Minute); !errors.Is(err, ErrPairingRefused) {
		t.Fatalf("confirming a refused pairing = %v, want ErrPairingRefused", err)
	}
	if state, _ := s.PairingConfirmationState(code, time.Minute); state != PairingRefused {
		t.Fatalf("state after the rejected confirm = %q, want refused", state)
	}
}

// An agent that comes back from the dead after the phone has already given
// up must be told its confirmation did not take, rather than silently
// disagreeing with a phone that moved on.
func TestConfirmPairingIsRefusedOnceTheWindowElapsed(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code := redeemedPairing(t, s, tenant)

	const alreadyElapsed = -time.Second
	if err := s.ConfirmPairing(tenant, code, alreadyElapsed); !errors.Is(err, ErrPairingConfirmExpired) {
		t.Fatalf("confirming past the window = %v, want ErrPairingConfirmExpired", err)
	}
}

// The timeout is computed on read, so it needs no sweeper and no surviving
// process: an agent killed at its own prompt still resolves the pairing.
func TestAnUnansweredPairingReadsRefusedOnceTheWindowElapses(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code := redeemedPairing(t, s, tenant)

	const alreadyElapsed = -time.Second
	if state, ok := s.PairingConfirmationState(code, alreadyElapsed); !ok || state != PairingRefused {
		t.Fatalf("an unanswered pairing past its window = (%q, ok=%v), want refused", state, ok)
	}
	// Nothing was written to get there -- the same row still reads pending
	// inside a live window.
	if state, _ := s.PairingConfirmationState(code, time.Minute); state != PairingPending {
		t.Fatalf("the elapsed read mutated the row: state=%q, want pending", state)
	}
}

// A phone polls with a code and no credential, so an unknown code has to be
// distinguishable from a real one rather than answering pending forever.
func TestPairingConfirmationStateRejectsAnUnknownCode(t *testing.T) {
	s := newStore(t)
	if state, ok := s.PairingConfirmationState("NOSUCHCD", time.Minute); ok {
		t.Fatalf("an unknown code reported state %q", state)
	}
}

// A refusal that lands before the phone ever redeemed is still readable --
// the phone may be polling for a code it never got to redeem.
func TestAPairingRefusedBeforeRedemptionStillReadsRefused(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AbortPairing(tenant, code); err != nil {
		t.Fatal(err)
	}

	if state, ok := s.PairingConfirmationState(code, time.Minute); !ok || state != PairingRefused {
		t.Fatalf("state = (%q, ok=%v), want refused", state, ok)
	}
}

// PairingCodeInfo resolving a refused code would let a phone start a pairing
// the operator has already killed.
func TestPairingCodeInfoRefusesARefusedCode(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, testPubkey, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AbortPairing(tenant, code); err != nil {
		t.Fatal(err)
	}

	if _, _, _, ok := s.PairingCodeInfo(code); ok {
		t.Fatal("a refused code must not keep resolving to the agent's pubkey")
	}
}

func TestRevokeByHashRemovesTheDevice(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
	dev, err := s.Verify(tok)
	if err != nil {
		t.Fatal(err)
	}

	if err := s.RevokeByHash(tenant, dev.TokenHash); err != nil {
		t.Fatalf("RevokeByHash should succeed for a known device: %v", err)
	}
	if _, err := s.Verify(tok); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked token must stop verifying, got err=%v", err)
	}
}

func TestRevokeByHashRejectsAnUnknownHash(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	if err := s.RevokeByHash(tenant, "not-a-real-hash"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("RevokeByHash of an unknown hash must be ErrNotFound, got %v", err)
	}
}

// The whole point of the tenant scope: an agent holding a valid cert for its
// own tenant must not be able to revoke a device belonging to another one,
// even knowing its hash (which is not secret -- it appears in logs and in
// the X-Device-ID header).
func TestRevokeByHashCannotReachAnotherTenantsDevice(t *testing.T) {
	s := newStore(t)
	tenantA := newTenant(t, s)
	tenantB := newTenant(t, s)
	tokA, _ := s.Issue(tenantA, "phone-a", testPubkey)
	devA, err := s.Verify(tokA)
	if err != nil {
		t.Fatal(err)
	}

	if err := s.RevokeByHash(tenantB, devA.TokenHash); !errors.Is(err, ErrNotFound) {
		t.Fatalf("cross-tenant revoke must be ErrNotFound, got %v", err)
	}
	if _, err := s.Verify(tokA); err != nil {
		t.Fatalf("tenant A's device must survive tenant B's revoke attempt: %v", err)
	}
}

func TestListByTenantReturnsOnlyThatTenantsDevices(t *testing.T) {
	s := newStore(t)
	tenantA := newTenant(t, s)
	tenantB := newTenant(t, s)
	if _, err := s.Issue(tenantA, "phone-a", testPubkey); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Issue(tenantB, "phone-b", testPubkey); err != nil {
		t.Fatal(err)
	}

	listA := s.ListByTenant(tenantA)
	if len(listA) != 1 {
		t.Fatalf("want 1 device for tenant A, got %d", len(listA))
	}
	if listA[0].Name != "phone-a" || listA[0].TenantID != tenantA {
		t.Fatalf("ListByTenant leaked another tenant's device: %+v", listA[0])
	}
	if len(s.List()) != 2 {
		t.Fatal("List must still see across tenants -- it is the operator's inventory")
	}
}
