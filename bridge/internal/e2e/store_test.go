package e2e

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
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

// mustOpen opens a Store at path, failing the test on error -- every test
// below stands in for a fresh agent/pair-device process opening its own
// handle on the store file.
func mustOpen(t *testing.T, path string) *Store {
	t.Helper()
	s, err := OpenStore(path)
	if err != nil {
		t.Fatalf("OpenStore(%q): %v", path, err)
	}
	return s
}

func TestAddDeviceAndSharedSecret(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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

// Two device rows sharing one key is the cmux-app-1fx defect: each row keeps
// its own counters and AddDevice resets them to zero, so a shared key means
// the same (direction, counter) nonce is used twice. Correct derivation makes
// this unreachable -- the point of the guard is that a regression in it fails
// loudly here instead of silently reusing nonces in production.
func TestAddDeviceRejectsSecretAlreadyPairedToAnotherDevice(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
	secret := []byte("0123456789abcdef0123456789abcdef")

	if err := s.AddDevice("dev1", testPubKey(t), secret); err != nil {
		t.Fatalf("AddDevice dev1: %v", err)
	}
	if _, err := s.NextSendCounter("dev1"); err != nil {
		t.Fatalf("NextSendCounter dev1: %v", err)
	}

	err := s.AddDevice("dev2", testPubKey(t), secret)
	if !errors.Is(err, ErrSharedSecretReused) {
		t.Fatalf("AddDevice dev2 = %v, want ErrSharedSecretReused", err)
	}
	if _, ok := s.SharedSecret("dev2"); ok {
		t.Fatal("rejected device must not be persisted")
	}
	// The rejected pairing must not have disturbed the live row -- in
	// particular its counter must not be rolled back to zero.
	if n, err := s.NextSendCounter("dev1"); err != nil || n != 1 {
		t.Fatalf("dev1 NextSendCounter = %d, %v; want 1, nil (existing row left intact)", n, err)
	}
}

// Re-pairing the same device is the legitimate path through the same guard:
// the row being replaced is not a *different* device, so resetting its
// counters alongside a fresh key stays allowed.
func TestAddDeviceAllowsRepairingSameDeviceID(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
	secret := []byte("0123456789abcdef0123456789abcdef")

	if err := s.AddDevice("dev1", testPubKey(t), secret); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	if _, err := s.NextSendCounter("dev1"); err != nil {
		t.Fatalf("NextSendCounter: %v", err)
	}
	if err := s.AddDevice("dev1", testPubKey(t), secret); err != nil {
		t.Fatalf("re-pair dev1: %v", err)
	}
	if n, err := s.NextSendCounter("dev1"); err != nil || n != 0 {
		t.Fatalf("NextSendCounter after re-pair = %d, %v; want 0, nil (counters reset)", n, err)
	}
}

func TestSharedSecretUnknownDevice(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
	if _, ok := s.SharedSecret("nope"); ok {
		t.Fatal("expected SharedSecret to fail for unknown device")
	}
}

func TestDeviceIDsListsAllPairedDevices(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	got := s.ActiveDeviceIDs()
	if len(got) != 1 || got[0] != "dev1" {
		t.Fatalf("ActiveDeviceIDs = %v, want [dev1]", got)
	}
}

// setLastActive backdates dev's LastActiveUnix directly in the store,
// simulating a device that hasn't sent anything in a while -- there's no
// public API for this since real staleness only ever accrues with time.
func setLastActive(t *testing.T, s *Store, deviceID string, when time.Time) {
	t.Helper()
	res, err := s.db.Exec(`UPDATE devices SET last_active_unix = ? WHERE device_id = ?`, when.Unix(), deviceID)
	if err != nil {
		t.Fatalf("backdate last_active_unix: %v", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		t.Fatalf("backdate last_active_unix: rows affected: %v", err)
	}
	if n == 0 {
		t.Fatalf("device %q not found", deviceID)
	}
}

func TestActiveDeviceIDsExcludesStaleDevice(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	s1 := mustOpen(t, path)
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

	s2 := mustOpen(t, path)
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
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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

func TestValidateAndCommitRecvCounterDistinguishesAReplayRejectionFromADecryptFailure(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	// Leave the window far ahead of where a fresh pairing's counters start,
	// reproducing the state a superseded pairing used to strand (cmux-app-a3g).
	if _, err := s.ValidateAndCommitRecvCounter("dev1", 246410, func() ([]byte, error) {
		return []byte("ok"), nil
	}); err != nil {
		t.Fatalf("seeding the window: %v", err)
	}

	_, err := s.ValidateAndCommitRecvCounter("dev1", 0, func() ([]byte, error) {
		t.Fatal("decrypt must not run for a counter the window refused")
		return nil, nil
	})
	if !errors.Is(err, ErrReplayRejected) {
		t.Fatalf("a stale window must report ErrReplayRejected, got %v", err)
	}
	// The gap is the diagnosis, so both numbers have to be in the message.
	if msg := err.Error(); !strings.Contains(msg, "counter=0") || !strings.Contains(msg, "highest_seen=246410") {
		t.Fatalf("message must name the counter and the window, got %q", msg)
	}

	// The other half of the distinction: a genuine AEAD failure must NOT be
	// reported as a replay rejection, or the dead end just moves.
	_, err = s.ValidateAndCommitRecvCounter("dev1", 246411, func() ([]byte, error) {
		return nil, fmt.Errorf("decrypt_failed")
	})
	if errors.Is(err, ErrReplayRejected) {
		t.Fatalf("an AEAD failure must not masquerade as a replay rejection, got %v", err)
	}
}

func TestValidateAndCommitRecvCounterDoesNotAdvanceOnDecryptFailure(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
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
	writer := mustOpen(t, path)
	reader := mustOpen(t, path)
	pub := testPubKey(t)

	if err := writer.AddDevice("dev1", pub, []byte("secret")); err != nil {
		t.Fatalf("AddDevice via writer: %v", err)
	}
	if _, ok := reader.SharedSecret("dev1"); !ok {
		t.Fatal("expected a second independent *Store instance on the same file to see the write immediately, with no reload call")
	}
}

// TestConcurrentPairAndCounterCommitLosesNeither is the direct regression
// test for the cross-process clobber bug this migration fixes: two
// independent *Store handles on the same file (standing in for the running
// `agent` process and the short-lived `pair-device` process) racing
// AddDevice against NextSendCounter must not lose either side's write.
//
// Run unmodified against the pre-migration JSON-backed Store, this test is
// expected to fail or flake: two JSON-backed *Store handles racing
// AddDevice/NextSendCounter on the same file can clobber each other's
// writes, since whichever save()'s os.Rename lands last wins in full,
// discarding whatever the other process's stale load() didn't already have.
func TestConcurrentPairAndCounterCommitLosesNeither(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	s1 := mustOpen(t, path)
	s2 := mustOpen(t, path)

	secret1 := []byte("dev1-shared-secret-0123456789ab")
	if err := s1.AddDevice("dev1", testPubKey(t), secret1); err != nil {
		t.Fatalf("AddDevice dev1: %v", err)
	}

	secret2 := []byte("dev2-shared-secret-0123456789ab")
	pub2 := testPubKey(t)

	const m = 200
	counters := make([]uint64, m)

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		if err := s2.AddDevice("dev2", pub2, secret2); err != nil {
			t.Errorf("AddDevice dev2 (from second Store handle): %v", err)
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < m; i++ {
			n, err := s1.NextSendCounter("dev1")
			if err != nil {
				t.Errorf("NextSendCounter[%d]: %v", i, err)
				return
			}
			counters[i] = n
		}
	}()
	wg.Wait()

	gotSecret, ok := s1.SharedSecret("dev2")
	if !ok {
		t.Fatal("expected dev2's AddDevice (from the second, concurrent Store handle) to not be lost")
	}
	if string(gotSecret) != string(secret2) {
		t.Fatalf("dev2 shared secret = %q, want %q", gotSecret, secret2)
	}

	seen := make(map[uint64]bool, m)
	for _, c := range counters {
		if seen[c] {
			t.Fatalf("duplicate counter value %d among %v -- a NextSendCounter increment was lost", c, counters)
		}
		seen[c] = true
	}
	for i := uint64(0); i < m; i++ {
		if !seen[i] {
			t.Fatalf("missing counter value %d among %v -- a NextSendCounter increment was lost", i, counters)
		}
	}
}

// TestNextSendCounterCostDoesNotScaleWithDeviceCount is a proxy for "no
// full-file rewrite per frame," not a literal instruction-count assertion:
// it checks that a single NextSendCounter call's cost does not grow with
// the total number of paired devices, whereas the pre-migration JSON
// design's cost was O(total devices) per call (a whole-map marshal every
// time) regardless of which device was touched. A ratio bound (not an
// absolute latency bound) keeps this robust to test-machine speed while
// still clearly failing under the old design at a 50x device-count
// increase (500 vs 10 devices).
func TestNextSendCounterCostDoesNotScaleWithDeviceCount(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))

	pairDevices := func(from, to int) {
		t.Helper()
		for i := from; i < to; i++ {
			if err := s.AddDevice(fmt.Sprintf("dev%d", i), testPubKey(t), []byte(fmt.Sprintf("secret%d", i))); err != nil {
				t.Fatalf("AddDevice dev%d: %v", i, err)
			}
		}
	}

	const calls = 2000
	timeNextSendCounter := func() time.Duration {
		t.Helper()
		// Warm up -- discard first-call filesystem/page-cache effects.
		for i := 0; i < 20; i++ {
			if _, err := s.NextSendCounter("dev0"); err != nil {
				t.Fatalf("warm-up NextSendCounter: %v", err)
			}
		}
		start := time.Now()
		for i := 0; i < calls; i++ {
			if _, err := s.NextSendCounter("dev0"); err != nil {
				t.Fatalf("NextSendCounter: %v", err)
			}
		}
		return time.Since(start) / calls
	}

	pairDevices(0, 10)
	avgSmall := timeNextSendCounter()

	pairDevices(10, 500)
	avgLarge := timeNextSendCounter()

	if avgLarge > avgSmall*3 {
		t.Fatalf("NextSendCounter average cost grew with device count: avgSmall(10 devices)=%v avgLarge(500 devices)=%v (ratio %.2fx, want <= 3x)",
			avgSmall, avgLarge, float64(avgLarge)/float64(avgSmall))
	}
}

// captureLog redirects the default slog logger to a buffer for the duration
// of the test, restoring it on cleanup.
func captureLog(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	prev := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	t.Cleanup(func() { slog.SetDefault(prev) })
	return &buf
}

// TestOpenRecoversFromCorruptFileLoudly is the acceptance test for "loud
// corruption is not silent at every call": a genuinely corrupt (not valid
// SQLite) store file must not block agent startup, must be moved aside
// intact for forensics, must be logged loudly, and the resulting fresh
// store must be immediately usable.
func TestOpenRecoversFromCorruptFileLoudly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	garbage := []byte("not a database")
	if err := os.WriteFile(path, garbage, 0o600); err != nil {
		t.Fatalf("write garbage file: %v", err)
	}

	logBuf := captureLog(t)

	s, err := OpenStore(path)
	if err != nil {
		t.Fatalf("OpenStore on corrupt file: %v", err)
	}
	if s == nil {
		t.Fatal("expected a non-nil *Store even after recovering from a corrupt file")
	}

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read recovered path: %v", err)
	}
	if bytes.Equal(got, garbage) {
		t.Fatal("expected the corrupt garbage to no longer be at the original path")
	}

	matches, err := filepath.Glob(path + ".corrupt.*")
	if err != nil {
		t.Fatalf("glob corrupt sibling: %v", err)
	}
	if len(matches) != 1 {
		t.Fatalf("expected exactly one <path>.corrupt.* sibling, got %v", matches)
	}
	corruptContent, err := os.ReadFile(matches[0])
	if err != nil {
		t.Fatalf("read corrupt sibling: %v", err)
	}
	if !bytes.Equal(corruptContent, garbage) {
		t.Fatalf("corrupt sibling content = %q, want original garbage %q", corruptContent, garbage)
	}

	logged := logBuf.String()
	if logged == "" {
		t.Fatal("expected non-empty log output for a corrupt store recovery")
	}
	if !strings.Contains(logged, path) {
		t.Fatalf("expected log output to name the original path %q, got: %s", path, logged)
	}
	if !strings.Contains(strings.ToLower(logged), "corrupt") {
		t.Fatalf("expected log output to mention corruption, got: %s", logged)
	}

	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("expected the recovered store to be immediately usable, AddDevice failed: %v", err)
	}
}

// TestRecvWindowBitsBit63RoundTrips guards the int64<->uint64
// bit-reinterpretation used to persist recv_window_bits: replayWindowSize
// is 64, so commitRecvCounter can legitimately set bit 63 of the returned
// bitmask (age == replayWindowSize-1). SQLite's INTEGER column is a signed
// 64-bit value, and Go's database/sql refuses to Scan a negative driver
// int64 directly into a *uint64 destination -- so this value must round
// trip via an explicit int64<->uint64 reinterpretation, not a direct
// uint64 Scan (see ValidateAndCommitRecvCounter's doc comment). This test
// proves that reinterpretation is wired correctly end to end, reading back
// through a second, independent *Store handle so the assertion is against
// what SQLite actually persisted, not a Go-side value that never left
// process memory.
func TestRecvWindowBitsBit63RoundTrips(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	s1 := mustOpen(t, path)
	if err := s1.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	// Commit highest=1000, then accept n=1000-63: age = highest-n = 63, so
	// commitRecvCounter ORs in (1 << 63) -- the exact bit this test exists
	// to cover.
	if !acceptCounter(t, s1, "dev1", 1000) {
		t.Fatal("expected counter 1000 accepted on fresh device")
	}
	if !acceptCounter(t, s1, "dev1", 1000-63) {
		t.Fatal("expected counter 1000-63 accepted (sets replay-window bit 63)")
	}

	s2 := mustOpen(t, path)
	var raw int64
	if err := s2.db.QueryRow(`SELECT recv_window_bits FROM devices WHERE device_id = ?`, "dev1").Scan(&raw); err != nil {
		t.Fatalf("query recv_window_bits: %v", err)
	}
	gotBits := uint64(raw)
	if gotBits&(1<<63) == 0 {
		t.Fatalf("recv_window_bits = %#x, want bit 63 set", gotBits)
	}

	// The replay-window semantics for that bit must still behave correctly
	// after the round trip: counter 1000-63 (age 63, the bit just verified
	// above) must now be rejected as a replay.
	if acceptCounter(t, s2, "dev1", 1000-63) {
		t.Fatal("expected counter 1000-63 to now be rejected as a replay (bit 63 of the persisted window)")
	}
}
