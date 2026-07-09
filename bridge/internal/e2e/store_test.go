package e2e

import (
	"crypto/ecdh"
	"crypto/rand"
	"fmt"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
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

func TestActiveDeviceIDsIncludesFreshlyPairedDevice(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	got := s.ActiveDeviceIDs()
	if len(got) != 1 || got[0] != "dev1" {
		t.Fatalf("ActiveDeviceIDs = %v, want [dev1]", got)
	}
}

// setLastActive backdates dev's LastActiveUnix directly in the store file,
// simulating a device that hasn't sent anything in a while -- there's no
// public API for this since real staleness only ever accrues with time.
func setLastActive(t *testing.T, s *Store, deviceID string, when time.Time) {
	t.Helper()
	f, err := s.load()
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		t.Fatalf("device %q not found", deviceID)
	}
	d.LastActiveUnix = when.Unix()
	f.Devices[deviceID] = d
	if err := s.save(f); err != nil {
		t.Fatalf("save: %v", err)
	}
}

func TestActiveDeviceIDsExcludesStaleDevice(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret1")); err != nil {
		t.Fatalf("AddDevice dev1: %v", err)
	}
	if err := s.AddDevice("dev2", testPubKey(t), []byte("secret2")); err != nil {
		t.Fatalf("AddDevice dev2: %v", err)
	}
	setLastActive(t, s, "dev1", time.Now().Add(-staleDeviceAge-time.Hour))

	active := s.ActiveDeviceIDs()
	if len(active) != 1 || active[0] != "dev2" {
		t.Fatalf("ActiveDeviceIDs = %v, want [dev2] (dev1 stale)", active)
	}

	all := s.DeviceIDs()
	if len(all) != 2 {
		t.Fatalf("DeviceIDs = %v, want both dev1 and dev2 (full history, not filtered)", all)
	}
}

func TestValidateAndCommitRecvCounterRefreshesLastActive(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	setLastActive(t, s, "dev1", time.Now().Add(-staleDeviceAge-time.Hour))
	if got := s.ActiveDeviceIDs(); len(got) != 0 {
		t.Fatalf("expected dev1 to start stale, ActiveDeviceIDs = %v", got)
	}

	if !acceptCounter(t, s, "dev1", 0) {
		t.Fatal("expected counter 0 accepted")
	}

	if got := s.ActiveDeviceIDs(); len(got) != 1 || got[0] != "dev1" {
		t.Fatalf("expected a successful decrypt to refresh dev1 back to active, got %v", got)
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

// acceptCounter is a store_test.go helper mirroring how frame.go/envelope.go
// drive ValidateAndCommitRecvCounter: decrypt is a no-op stand-in so these
// tests can exercise the replay-window bookkeeping without real ciphertext.
func acceptCounter(t *testing.T, s *Store, deviceID string, n uint64) bool {
	t.Helper()
	_, err := s.ValidateAndCommitRecvCounter(deviceID, n, func() ([]byte, error) {
		return []byte("ok"), nil
	})
	return err == nil
}

func TestValidateAndCommitRecvCounter(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	if !acceptCounter(t, s, "dev1", 0) {
		t.Fatal("expected counter 0 accepted on fresh device")
	}
	if acceptCounter(t, s, "dev1", 0) {
		t.Fatal("expected counter 0 to be rejected as replay after commit")
	}
	if !acceptCounter(t, s, "dev1", 1) {
		t.Fatal("expected counter 1 accepted after committing 0")
	}
}

func TestOutOfOrderWithinWindowIsAccepted(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	if !acceptCounter(t, s, "dev1", 10) {
		t.Fatal("expected counter 10 accepted on fresh device")
	}

	// 7 arrives late (e.g. a slower HTTP response overtaken by a faster WS
	// frame) but was never seen and is within the last 64 counters -- must
	// be accepted, not rejected as "old."
	if !acceptCounter(t, s, "dev1", 7) {
		t.Fatal("expected counter 7 accepted (out-of-order, within window)")
	}
	if acceptCounter(t, s, "dev1", 7) {
		t.Fatal("expected counter 7 to now be rejected as a replay")
	}
	if acceptCounter(t, s, "dev1", 10) {
		t.Fatal("expected counter 10 to still be rejected as a replay (it was committed first)")
	}
}

func TestTooOldOutsideWindowIsRejected(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	if !acceptCounter(t, s, "dev1", 1000) {
		t.Fatal("expected counter 1000 accepted on fresh device")
	}

	if acceptCounter(t, s, "dev1", 1000-64) { // exactly at the boundary: too old
		t.Fatal("expected counter 1000-64 to be rejected as outside the window")
	}
	if !acceptCounter(t, s, "dev1", 1000-63) { // one inside the boundary: still fine
		t.Fatal("expected counter 1000-63 accepted (just inside window)")
	}
}

func TestValidateAndCommitRecvCounterRejectsWithoutDecrypting(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	if !acceptCounter(t, s, "dev1", 5) {
		t.Fatal("expected counter 5 accepted on fresh device")
	}

	decryptCalled := false
	_, err := s.ValidateAndCommitRecvCounter("dev1", 5, func() ([]byte, error) {
		decryptCalled = true
		return []byte("ok"), nil
	})
	if err == nil {
		t.Fatal("expected replayed counter 5 to be rejected")
	}
	if decryptCalled {
		t.Fatal("decrypt must not run for a counter that fails the replay check")
	}
}

func TestValidateAndCommitRecvCounterDoesNotAdvanceOnDecryptFailure(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	decryptErr := fmt.Errorf("decrypt_failed")
	_, err := s.ValidateAndCommitRecvCounter("dev1", 3, func() ([]byte, error) {
		return nil, decryptErr
	})
	if err == nil {
		t.Fatal("expected decrypt failure to propagate")
	}

	// A failed decrypt must not burn the counter: a forged envelope with a
	// guessed, not-yet-used counter and garbage ciphertext must not be able
	// to make the legitimate sender's real future message at that counter
	// get rejected as a replay.
	if !acceptCounter(t, s, "dev1", 3) {
		t.Fatal("expected counter 3 to still be acceptable after a failed decrypt attempt")
	}
}

// TestConcurrentDecryptOfSameCounterAcceptsExactlyOnce is the adversarial
// regression test for the TOCTOU replay race: two goroutines racing to
// decrypt the identical captured frame at the same counter must not both
// succeed. Before ValidateAndCommitRecvCounter combined the check and the
// commit into one locked transaction, both could pass the replay check
// (neither had committed yet), both call decrypt, and both "succeed."
func TestConcurrentDecryptOfSameCounterAcceptsExactlyOnce(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	const n = uint64(42)
	const races = 32

	var wg sync.WaitGroup
	var successes int64
	for i := 0; i < races; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := s.ValidateAndCommitRecvCounter("dev1", n, func() ([]byte, error) {
				// Simulate AEAD Open taking a moment, widening the race
				// window that the old two-call Validate/Commit design left
				// open between the replay check and the commit.
				time.Sleep(time.Millisecond)
				return []byte("plaintext"), nil
			})
			if err == nil {
				atomic.AddInt64(&successes, 1)
			}
		}()
	}
	wg.Wait()

	if successes != 1 {
		t.Fatalf("expected exactly 1 of %d concurrent decrypts of the same counter to succeed, got %d", races, successes)
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
