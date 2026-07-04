package e2e

import (
	"crypto/ecdh"
	"crypto/rand"
	"path/filepath"
	"testing"
)

func testPubKey(t *testing.T) *ecdh.PublicKey {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	return priv.PublicKey()
}

func TestAddDeviceAndSharedSecret(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	pub := testPubKey(t)
	secret := []byte("0123456789abcdef0123456789abcdef")

	if err := s.AddDevice("dev1", pub, secret); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	got, ok := s.SharedSecret("dev1")
	if !ok {
		t.Fatal("expected SharedSecret to find dev1")
	}
	if string(got) != string(secret) {
		t.Fatalf("shared secret mismatch: got %q want %q", got, secret)
	}
}

func TestSharedSecretUnknownDevice(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if _, ok := s.SharedSecret("nope"); ok {
		t.Fatal("expected SharedSecret to fail for unknown device")
	}
}

func TestDeviceIDsListsAllPairedDevices(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if got := s.DeviceIDs(); len(got) != 0 {
		t.Fatalf("expected no devices yet, got %v", got)
	}
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret1")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	if err := s.AddDevice("dev2", testPubKey(t), []byte("secret2")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	got := s.DeviceIDs()
	want := map[string]bool{"dev1": true, "dev2": true}
	if len(got) != len(want) {
		t.Fatalf("DeviceIDs = %v, want exactly %v", got, want)
	}
	for _, id := range got {
		if !want[id] {
			t.Fatalf("unexpected deviceID %q in %v", id, got)
		}
	}
}

func TestNextSendCounterIncrementsAndPersistsAcrossInstances(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	s1 := OpenStore(path)
	if err := s1.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	for i, want := range []uint64{0, 1, 2} {
		got, err := s1.NextSendCounter("dev1")
		if err != nil {
			t.Fatalf("NextSendCounter[%d]: %v", i, err)
		}
		if got != want {
			t.Fatalf("NextSendCounter[%d] = %d, want %d", i, got, want)
		}
	}

	s2 := OpenStore(path)
	got, err := s2.NextSendCounter("dev1")
	if err != nil {
		t.Fatalf("NextSendCounter on fresh Store instance: %v", err)
	}
	if got != 3 {
		t.Fatalf("expected counter to persist across Store instances, got %d want 3", got)
	}
}

func TestValidateAndCommitRecvCounter(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	valid, err := s.ValidateRecvCounter("dev1", 0)
	if err != nil || !valid {
		t.Fatalf("expected counter 0 valid on fresh device, got valid=%v err=%v", valid, err)
	}
	if err := s.CommitRecvCounter("dev1", 0); err != nil {
		t.Fatalf("CommitRecvCounter(0): %v", err)
	}

	valid, err = s.ValidateRecvCounter("dev1", 0)
	if err != nil {
		t.Fatalf("ValidateRecvCounter replay check: %v", err)
	}
	if valid {
		t.Fatal("expected counter 0 to be rejected as replay after commit")
	}

	valid, err = s.ValidateRecvCounter("dev1", 1)
	if err != nil || !valid {
		t.Fatalf("expected counter 1 valid after committing 0, got valid=%v err=%v", valid, err)
	}
}

func TestOutOfOrderWithinWindowIsAccepted(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	if err := s.CommitRecvCounter("dev1", 10); err != nil {
		t.Fatalf("CommitRecvCounter(10): %v", err)
	}

	// 7 arrives late (e.g. a slower HTTP response overtaken by a faster WS
	// frame) but was never seen and is within the last 64 counters -- must
	// be accepted, not rejected as "old."
	valid, err := s.ValidateRecvCounter("dev1", 7)
	if err != nil || !valid {
		t.Fatalf("expected counter 7 valid (out-of-order, within window), got valid=%v err=%v", valid, err)
	}
	if err := s.CommitRecvCounter("dev1", 7); err != nil {
		t.Fatalf("CommitRecvCounter(7): %v", err)
	}

	valid, err = s.ValidateRecvCounter("dev1", 7)
	if err != nil {
		t.Fatalf("ValidateRecvCounter replay check: %v", err)
	}
	if valid {
		t.Fatal("expected counter 7 to now be rejected as a replay")
	}

	valid, err = s.ValidateRecvCounter("dev1", 10)
	if err != nil {
		t.Fatalf("ValidateRecvCounter: %v", err)
	}
	if valid {
		t.Fatal("expected counter 10 to still be rejected as a replay (it was committed first)")
	}
}

func TestTooOldOutsideWindowIsRejected(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	if err := s.CommitRecvCounter("dev1", 1000); err != nil {
		t.Fatalf("CommitRecvCounter(1000): %v", err)
	}

	valid, err := s.ValidateRecvCounter("dev1", 1000-64) // exactly at the boundary: too old
	if err != nil {
		t.Fatalf("ValidateRecvCounter: %v", err)
	}
	if valid {
		t.Fatal("expected counter 1000-64 to be rejected as outside the window")
	}

	valid, err = s.ValidateRecvCounter("dev1", 1000-63) // one inside the boundary: still fine
	if err != nil || !valid {
		t.Fatalf("expected counter 1000-63 valid (just inside window), got valid=%v err=%v", valid, err)
	}
}

func TestCrossProcessVisibilityNoInMemoryCache(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	writer := OpenStore(path)
	reader := OpenStore(path)
	pub := testPubKey(t)

	if err := writer.AddDevice("dev1", pub, []byte("secret")); err != nil {
		t.Fatalf("AddDevice via writer: %v", err)
	}
	if _, ok := reader.SharedSecret("dev1"); !ok {
		t.Fatal("expected a second independent *Store instance on the same file to see the write immediately, with no reload call")
	}
}
