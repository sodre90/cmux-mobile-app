package e2e

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// writeLegacyStore writes a pre-SQLite sessions.json holding one device, and
// returns the shared secret it should import as.
func writeLegacyStore(t *testing.T, path, deviceID string) []byte {
	t.Helper()
	secret := []byte("0123456789abcdef0123456789abcdef")
	raw, err := json.Marshal(fileFormat{Devices: map[string]deviceSession{
		deviceID: {
			DevicePubKey:   base64.StdEncoding.EncodeToString([]byte("pubkey-bytes")),
			SharedSecret:   base64.StdEncoding.EncodeToString(secret),
			SendCounter:    7,
			RecvHighest:    3,
			RecvHighestSet: true,
			LastActiveUnix: 1700000000,
		},
	}})
	if err != nil {
		t.Fatalf("marshal legacy store: %v", err)
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatalf("write legacy store: %v", err)
	}
	return secret
}

func onDisk(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func assertImported(t *testing.T, s *Store, deviceID string, want []byte) {
	t.Helper()
	got, ok := s.SharedSecret(deviceID)
	if !ok {
		t.Fatalf("device %q was not imported; store holds %v", deviceID, s.DeviceIDs())
	}
	if string(got) != string(want) {
		t.Fatalf("imported shared secret = %q, want %q", got, want)
	}
}

// The ordinary upgrade: sessions.db alongside the sessions.json an older
// install left behind. Nothing covered this before, which is how the
// collision below shipped.
func TestOpenImportsLegacyJSONSibling(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "sessions.db")
	legacy := filepath.Join(dir, "sessions.json")
	secret := writeLegacyStore(t, legacy, "dev-1")

	s := mustOpen(t, dbPath)

	assertImported(t, s, "dev-1", secret)
	if onDisk(legacy) {
		t.Error("the imported legacy file should have been renamed aside")
	}
	if !onDisk(legacy + ".migrated") {
		t.Error("the legacy file must be kept as .migrated, not deleted")
	}
}

// The bug this file exists for (cmux-app-lgc): an operator whose
// session_store still names the pre-SQLite sessions.json. The legacy store is
// then AT the database path, and before the fix SQLite reported SQLITE_NOTADB,
// the recovery path renamed the only copy of the pairings to .corrupt.<ts>
// and told the operator to re-pair every device, and the import looked for
// its source at a path that had collapsed onto the database itself.
func TestOpenImportsLegacyJSONFoundAtTheDatabasePath(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	secret := writeLegacyStore(t, path, "dev-1")

	logBuf := captureLog(t)
	s := mustOpen(t, path)

	assertImported(t, s, "dev-1", secret)

	if logged := logBuf.String(); strings.Contains(logged, "must re-pair") {
		t.Errorf("a legacy store must not be reported as corruption: %s", logged)
	}
	corrupt, err := filepath.Glob(path + ".corrupt.*")
	if err != nil {
		t.Fatalf("glob: %v", err)
	}
	if len(corrupt) != 0 {
		t.Errorf("legacy store was moved aside as corrupt: %v", corrupt)
	}
	if !onDisk(path + ".legacy.migrated") {
		t.Error("the legacy file must be kept as a forensic copy after import")
	}
}

// Reopening must not re-import, and must not mistake the database for a
// legacy file now that it lives at a .json path.
func TestReopeningAStoreMigratedInPlaceIsQuiet(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	secret := writeLegacyStore(t, path, "dev-1")
	mustOpen(t, path)

	logBuf := captureLog(t)
	s := mustOpen(t, path)

	assertImported(t, s, "dev-1", secret)
	if logged := logBuf.String(); logged != "" {
		t.Errorf("reopening a migrated store should be silent, logged: %s", logged)
	}
}

// A store whose devices were all removed must not resurrect them from the
// forensic copy on the next open.
func TestAnEmptiedStoreDoesNotReimportTheForensicCopy(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	writeLegacyStore(t, path, "dev-1")
	s := mustOpen(t, path)
	if _, err := s.RemoveDevice("dev-1"); err != nil {
		t.Fatalf("RemoveDevice: %v", err)
	}

	reopened := mustOpen(t, path)

	if ids := reopened.DeviceIDs(); len(ids) != 0 {
		t.Fatalf("removed device came back after reopen: %v", ids)
	}
}

// JSON that is not a legacy store gets no special treatment -- it is not
// something to import, so it stays where it is for the corruption path to
// judge.
func TestUnrelatedJSONAtTheDatabasePathIsStillTreatedAsCorrupt(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	if err := os.WriteFile(path, []byte(`{"something":"else"}`), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	mustOpen(t, path)

	corrupt, err := filepath.Glob(path + ".corrupt.*")
	if err != nil {
		t.Fatalf("glob: %v", err)
	}
	if len(corrupt) != 1 {
		t.Fatalf("expected the unrecognised file to be moved aside as corrupt, got %v", corrupt)
	}
}
