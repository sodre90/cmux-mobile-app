package auth

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "d.json"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestIssueVerifyRevoke(t *testing.T) {
	s := newStore(t)
	tok, err := s.Issue("phone")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Verify(tok); !ok {
		t.Fatal("issued token should verify")
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

func TestPairingCodeSingleUse(t *testing.T) {
	s := newStore(t)
	code := s.NewPairingCode(time.Minute)
	tok, ok := s.RedeemPairingCode(code, "phone")
	if !ok || tok == "" {
		t.Fatal("first redeem should succeed")
	}
	if _, ok := s.RedeemPairingCode(code, "phone"); ok {
		t.Fatal("reuse of a code must fail")
	}
	if _, ok := s.Verify(tok); !ok {
		t.Fatal("redeemed token should verify")
	}
}

func TestPairingCodeExpiry(t *testing.T) {
	s := newStore(t)
	code := s.NewPairingCode(-time.Second) // already expired
	if _, ok := s.RedeemPairingCode(code, "phone"); ok {
		t.Fatal("expired code must fail")
	}
}

func TestPersistenceReload(t *testing.T) {
	p := filepath.Join(t.TempDir(), "d.json")
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	tok, _ := s.Issue("phone")
	s2, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := s2.Verify(tok); !ok {
		t.Fatal("token must survive reload")
	}
}

func TestReloadPicksUpExternalChanges(t *testing.T) {
	p := filepath.Join(t.TempDir(), "d.json")
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	tok1, _ := s.Issue("phone-1")

	// Simulate an external `cmux-relay pair` process that appended a device:
	// it read the current file (preserving phone-1) and wrote both back.
	external := []Device{
		{Token: tok1, Name: "phone-1"},
		{Token: "TOK2", Name: "phone-2"},
	}
	writeStore(t, p, external)

	// The new device is unknown until we reload.
	if _, ok := s.Verify("TOK2"); ok {
		t.Fatal("TOK2 should be unknown before reload")
	}
	n, err := s.Reload()
	if err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Fatalf("want 2 devices after reload, got %d", n)
	}
	if _, ok := s.Verify("TOK2"); !ok {
		t.Fatal("TOK2 should be known after reload")
	}
	if _, ok := s.Verify(tok1); !ok {
		t.Fatal("phone-1 should still be known after reload")
	}
}

func TestReloadKeepsDevicesOnError(t *testing.T) {
	p := filepath.Join(t.TempDir(), "d.json")
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	tok, _ := s.Issue("phone")

	if err := os.WriteFile(p, []byte("{ not valid json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Reload(); err == nil {
		t.Fatal("reload of a corrupt file must return an error")
	}
	if _, ok := s.Verify(tok); !ok {
		t.Fatal("devices must be preserved when reload fails")
	}
}

func writeStore(t *testing.T, path string, devs []Device) {
	t.Helper()
	data, err := json.MarshalIndent(devs, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestListRedactsTokens(t *testing.T) {
	s := newStore(t)
	tok, _ := s.Issue("phone")
	list := s.List()
	if len(list) != 1 {
		t.Fatalf("want 1 device, got %d", len(list))
	}
	if list[0].Token == tok {
		t.Fatal("List must redact the full token")
	}
	if len(list[0].Token) > 9 { // "..." + 6 chars
		t.Fatalf("redacted token too long: %q", list[0].Token)
	}
}

func TestFCMTokens(t *testing.T) {
	s := newStore(t)
	tok, _ := s.Issue("phone")
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
