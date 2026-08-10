package e2e

import (
	"crypto/ecdh"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	sqlite "modernc.org/sqlite"
	sqlite3 "modernc.org/sqlite/lib"
)

// deviceSession and fileFormat are the pre-migration JSON on-disk shape --
// kept only so importLegacyJSON can decode a sessions.json left behind by an
// install that predates the SQLite-backed Store.
type deviceSession struct {
	DevicePubKey   string `json:"device_pubkey"`
	SharedSecret   string `json:"shared_secret"`
	SendCounter    uint64 `json:"send_counter"`
	RecvHighest    uint64 `json:"recv_highest"`
	RecvHighestSet bool   `json:"recv_highest_set"`
	RecvWindowBits uint64 `json:"recv_window_bits"`
	LastActiveUnix int64  `json:"last_active_unix"`
}

type fileFormat struct {
	Devices map[string]deviceSession `json:"devices"`
}

// Store persists paired devices' e2e shared secrets and replay counters in a
// local SQLite database. Safe to call from multiple short-lived processes
// (the running `agent` and the short-lived `pair-device` CLI) against the
// same file -- SQLite handles the locking, so there is no in-memory cache to
// fall out of sync (see TestCrossProcessVisibilityNoInMemoryCache).
type Store struct {
	mu sync.Mutex
	db *sql.DB
}

const schema = `
CREATE TABLE IF NOT EXISTS devices (
	device_id         TEXT PRIMARY KEY,
	device_pubkey     TEXT NOT NULL,
	shared_secret     TEXT NOT NULL,
	send_counter      INTEGER NOT NULL DEFAULT 0,
	recv_highest      INTEGER NOT NULL DEFAULT 0,
	recv_highest_set  INTEGER NOT NULL DEFAULT 0,
	recv_window_bits  INTEGER NOT NULL DEFAULT 0,
	last_active_unix  INTEGER NOT NULL
);
`

// OpenStore opens (creating if absent) the SQLite database at path, applies
// the schema, and imports a legacy sessions.json sibling on first run (see
// importLegacyJSON). Mirrors auth.Store.Open's shape (busy_timeout pragma,
// schema-on-Exec) with one addition: a corrupt file at path is recovered
// from rather than left to fail every call silently (see recoverIfCorrupt).
//
// Named OpenStore, not Open, because this package already has a top-level
// Open (cipher.go's AEAD decrypt primitive, used by frame.go/envelope.go and
// by internal/server's tests as e2e.Open) -- reusing that name here would
// shadow/collide with it.
func OpenStore(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create dir for session store: %w", err)
	}
	dsn := path + "?_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open session store %s: %w", path, err)
	}
	if _, err := db.Exec(schema); err != nil {
		_ = db.Close()
		recovered, rerr := recoverIfCorrupt(path, err)
		if rerr != nil {
			return nil, fmt.Errorf("session store %s: %w", path, rerr)
		}
		if recovered {
			return OpenStore(path) // bad file moved aside; retry fresh
		}
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	s := &Store{db: db}
	if err := s.importLegacyJSON(path); err != nil {
		// A broken legacy import must not block startup -- log loudly and
		// proceed with whatever was imported before the error, same posture
		// as a corrupt file: the operator needs a signal, not a crashed agent.
		slog.Error("e2e: legacy session import incomplete", "path", legacyPath(path), "err", err)
	}
	return s, nil
}

// recoverIfCorrupt inspects openErr (from OpenStore's schema-creation Exec)
// for the two SQLite error codes that mean the file at path isn't a usable
// SQLite database -- SQLITE_NOTADB (a garbage/foreign file) or
// SQLITE_CORRUPT (a SQLite file with a damaged page) -- confirmed
// empirically against the exact vendored modernc.org/sqlite v1.53.0: opening
// a garbage file and running CREATE TABLE against it returns *sqlite.Error
// with Code() == 26 == sqlite3.SQLITE_NOTADB. On either code, the bad file
// is renamed aside (timestamped, so a second corruption event doesn't
// clobber the first forensic copy) and logged loudly, and recovered=true
// tells OpenStore to retry fresh. Any other error is left for OpenStore to
// propagate as a real failure -- this is not a general-purpose error handler.
func recoverIfCorrupt(path string, openErr error) (recovered bool, err error) {
	var sqliteErr *sqlite.Error
	if !errors.As(openErr, &sqliteErr) {
		return false, nil
	}
	if sqliteErr.Code() != sqlite3.SQLITE_NOTADB && sqliteErr.Code() != sqlite3.SQLITE_CORRUPT {
		return false, nil
	}
	corrupt := fmt.Sprintf("%s.corrupt.%d", path, time.Now().Unix())
	if err := os.Rename(path, corrupt); err != nil {
		return false, fmt.Errorf("rename corrupt store aside: %w", err)
	}
	slog.Error("e2e: session store was corrupt, moved aside -- every paired device must re-pair", "path", path, "moved_to", corrupt, "err", openErr)
	return true, nil
}

// legacyPath derives the pre-migration JSON sessions file path from the new
// SQLite database path by swapping the extension, e.g. "sessions.db" ->
// "sessions.json". Works for both the new default path and any
// operator-customized session_store value, as long as it ends in a
// recognizable extension.
func legacyPath(dbPath string) string {
	return strings.TrimSuffix(dbPath, filepath.Ext(dbPath)) + ".json"
}

// importLegacyJSON one-time-imports a legacy sessions.json sibling of dbPath
// into the (already schema'd) database. The trigger condition is "devices is
// still empty", not "the .db file didn't exist yet" -- idempotent and safe
// to re-attempt on every Open until a device genuinely exists, unlike a
// file-existence check that a crash mid-migration could leave permanently
// wrong. A successful import renames the source .json to .json.migrated
// (kept, not deleted, as a forensic/rollback copy) so the empty-devices
// condition naturally never fires again. An unreadable legacy file is logged
// and skipped rather than blocking startup.
func (s *Store) importLegacyJSON(dbPath string) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil // already has real data; never re-import
	}
	raw, err := os.ReadFile(legacyPath(dbPath))
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var f fileFormat
	if err := json.Unmarshal(raw, &f); err != nil {
		slog.Warn("e2e: legacy session store unreadable, starting empty instead of blocking startup", "path", legacyPath(dbPath), "err", err)
		return nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }() //nolint:errcheck // no-op after a successful Commit
	for id, d := range f.Devices {
		secret, err := base64.StdEncoding.DecodeString(d.SharedSecret)
		if err != nil {
			continue // skip an unparseable row rather than aborting the whole import
		}
		if _, err := tx.Exec(`INSERT INTO devices (device_id, device_pubkey, shared_secret, send_counter, recv_highest, recv_highest_set, recv_window_bits, last_active_unix) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			id, d.DevicePubKey, base64.StdEncoding.EncodeToString(secret), int64(d.SendCounter), int64(d.RecvHighest), boolToInt(d.RecvHighestSet), int64(d.RecvWindowBits), d.LastActiveUnix); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	migratedTo := legacyPath(dbPath) + ".migrated"
	if err := os.Rename(legacyPath(dbPath), migratedTo); err != nil {
		slog.Warn("e2e: imported devices but could not rename legacy file aside -- remove it manually", "count", len(f.Devices), "path", legacyPath(dbPath), "err", err)
		return nil
	}
	slog.Info("e2e: migrated paired devices to sqlite", "count", len(f.Devices), "from", legacyPath(dbPath), "to", dbPath)
	return nil
}

func boolToInt(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

// ErrSharedSecretReused rejects a pairing that would key a second device row
// with a secret an existing row already holds. Each row carries its own frame
// counters and AddDevice resets them to zero, while the AEAD nonce is a pure
// function of (direction, counter) -- so two rows sharing a key means reused
// nonces. Correct key derivation makes this unreachable; asserting it turns a
// future derivation regression into a loud pairing failure instead of silent
// nonce reuse (see
// docs/superpowers/specs/2026-08-10-per-pairing-key-separation-design.md).
var ErrSharedSecretReused = errors.New("shared secret already paired to a different device")

// AddDevice persists a newly paired device, keyed by deviceID. Re-pairing an
// already-known deviceID unconditionally overwrites its row -- including
// resetting its send/recv counters to zero -- matching the pre-SQLite Store's
// map-assignment semantics exactly. Re-keying a *different* deviceID with an
// existing row's secret fails with [ErrSharedSecretReused].
func (s *Store) AddDevice(deviceID string, devicePub *ecdh.PublicKey, sharedSecret []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	secretB64 := base64.StdEncoding.EncodeToString(sharedSecret)
	// The guard is a WHERE NOT EXISTS on the insert itself rather than a
	// separate SELECT: reading first would open the transaction for read and
	// then upgrade it to write, which two concurrent pairing processes can
	// deadlock on (SQLITE_BUSY, not resolvable by busy_timeout). One
	// statement takes the write lock once -- see
	// TestConcurrentPairAndCounterCommitLosesNeither.
	res, err := s.db.Exec(`
		INSERT INTO devices (device_id, device_pubkey, shared_secret, send_counter, recv_highest, recv_highest_set, recv_window_bits, last_active_unix)
		SELECT ?, ?, ?, 0, 0, 0, 0, ?
		WHERE NOT EXISTS (SELECT 1 FROM devices WHERE shared_secret = ? AND device_id <> ?)
		ON CONFLICT(device_id) DO UPDATE SET
			device_pubkey    = excluded.device_pubkey,
			shared_secret    = excluded.shared_secret,
			send_counter     = excluded.send_counter,
			recv_highest     = excluded.recv_highest,
			recv_highest_set = excluded.recv_highest_set,
			recv_window_bits = excluded.recv_window_bits,
			last_active_unix = excluded.last_active_unix`,
		deviceID,
		base64.StdEncoding.EncodeToString(devicePub.Bytes()),
		secretB64,
		time.Now().Unix(),
		secretB64,
		deviceID,
	)
	if err != nil {
		return fmt.Errorf("add device %s: %w", deviceID, err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("add device %s: %w", deviceID, err)
	}
	if n == 0 {
		return fmt.Errorf("add device %s: %w (already paired as %s)", deviceID, ErrSharedSecretReused, s.deviceIDForSecret(secretB64, deviceID))
	}
	return nil
}

// deviceIDForSecret names the row that tripped AddDevice's reuse guard. Only
// ever called on the error path, so a racing write changing the answer
// between the two statements costs nothing but a less precise message.
func (s *Store) deviceIDForSecret(secretB64, excludingDeviceID string) string {
	var id string
	if err := s.db.QueryRow(
		`SELECT device_id FROM devices WHERE shared_secret = ? AND device_id <> ? LIMIT 1`,
		secretB64, excludingDeviceID,
	).Scan(&id); err != nil {
		return "unknown"
	}
	return id
}

// DeviceIDs returns every deviceID this agent has ever paired with (direct or
// relay-mediated alike -- AddDevice is called identically by both pairing
// flows, so this one local store is the complete list). This includes devices
// that have gone stale (see ActiveDeviceIDs); callers that need to build
// per-device push payloads should use ActiveDeviceIDs instead.
func (s *Store) DeviceIDs() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT device_id FROM devices`)
	if err != nil {
		slog.Error("e2e: list device ids", "err", err)
		return nil
	}
	defer func() { _ = rows.Close() }()
	out := make([]string, 0)
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			slog.Error("e2e: scan device id", "err", err)
			continue
		}
		out = append(out, id)
	}
	if err := rows.Err(); err != nil {
		slog.Error("e2e: list device ids", "err", err)
	}
	return out
}

// staleDeviceAge is how long a device may go without successfully decrypting
// a request/frame (proving it still holds the shared secret and is in
// active use) before ActiveDeviceIDs stops considering it paired. There is
// no cross-process revocation signal from auth.Store reaching this agent-side
// store (see cmd/cmux-relay/commands.go's Revoke, which only touches the
// relay's own SQLite store), so recent activity is the only
// locally-available proxy for "still paired" -- generous on purpose to avoid
// dropping a device that's simply been offline for a while.
const staleDeviceAge = 30 * 24 * time.Hour

// ActiveDeviceIDs returns DeviceIDs filtered to devices that have
// successfully decrypted a request or frame within staleDeviceAge (or were
// paired that recently and have not sent anything yet). Use this, not
// DeviceIDs, when the result drives an action taken on the device's behalf
// (e.g. building an encrypted push payload) -- DeviceIDs' full history is
// intentionally broader than what should be treated as currently paired.
func (s *Store) ActiveDeviceIDs() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	cutoff := time.Now().Add(-staleDeviceAge).Unix()
	rows, err := s.db.Query(`SELECT device_id FROM devices WHERE last_active_unix >= ?`, cutoff)
	if err != nil {
		slog.Error("e2e: list active device ids", "err", err)
		return nil
	}
	defer func() { _ = rows.Close() }()
	out := make([]string, 0)
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			slog.Error("e2e: scan active device id", "err", err)
			continue
		}
		out = append(out, id)
	}
	if err := rows.Err(); err != nil {
		slog.Error("e2e: list active device ids", "err", err)
	}
	return out
}

func (s *Store) SharedSecret(deviceID string) (secret []byte, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var encoded string
	err := s.db.QueryRow(`SELECT shared_secret FROM devices WHERE device_id = ?`, deviceID).Scan(&encoded)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			slog.Error("e2e: query shared secret", "device", deviceLogID(deviceID), "err", err)
		}
		return nil, false
	}
	secret, err = base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		slog.Error("e2e: decode shared secret", "device", deviceLogID(deviceID), "err", err)
		return nil, false
	}
	return secret, true
}

// deviceLogID returns the last 6 hex characters of a device ID (itself the
// full SHA-256 hash of a bearer token, see auth.Device.TokenHash) -- enough
// to correlate log lines for one device without ever logging the full hash,
// mirroring auth.Device.HashSuffix.
func deviceLogID(deviceID string) string {
	if len(deviceID) < 6 {
		return deviceID
	}
	return deviceID[len(deviceID)-6:]
}

// NextSendCounter atomically increments deviceID's send counter and returns
// the pre-increment value (the same contract as the pre-SQLite Store: the
// first call after pairing returns 0). The UPDATE...RETURNING form is a
// single SQL statement, so it's atomic against a concurrent NextSendCounter
// from another process's *Store on the same file with no explicit
// transaction needed -- s.mu.Lock() below only orders concurrent callers
// within this one process, layered on top of SQLite's own cross-process
// safety, matching auth.Store's mutex layering.
func (s *Store) NextSendCounter(deviceID string) (uint64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var newCounter int64
	err := s.db.QueryRow(`UPDATE devices SET send_counter = send_counter + 1 WHERE device_id = ? RETURNING send_counter`, deviceID).Scan(&newCounter)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, fmt.Errorf("unknown device %q", deviceID)
		}
		return 0, fmt.Errorf("next send counter: %w", err)
	}
	return uint64(newCounter) - 1, nil
}

const replayWindowSize = 64

// canAcceptRecvCounter reports whether n is new (never committed) and
// within the last replayWindowSize counters of the current high-water
// mark. Mirrors the Android Session.ReplayWindow algorithm exactly (see
// android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/ReplayWindow.kt)
// -- both sides must tolerate the same degree of cross-channel reordering,
// since a phone's HTTP responses, /terminal WS, and /events WS frames all
// draw from one agent-side send counter with no cross-channel ordering
// guarantee.
func canAcceptRecvCounter(highest uint64, highestSet bool, windowBits uint64, n uint64) bool {
	if !highestSet || n > highest {
		return true
	}
	age := highest - n
	if age >= replayWindowSize {
		return false
	}
	return windowBits&(1<<age) == 0
}

// commitRecvCounter records n as seen, sliding the window forward if n is a
// new high-water mark.
func commitRecvCounter(highest uint64, highestSet bool, windowBits uint64, n uint64) (newHighest, newWindowBits uint64) {
	if !highestSet {
		return n, 1
	}
	if n > highest {
		shift := n - highest
		if shift >= replayWindowSize {
			return n, 1
		}
		return n, (windowBits << shift) | 1
	}
	age := highest - n
	if age >= replayWindowSize {
		return highest, windowBits
	}
	return highest, windowBits | (1 << age)
}

// ValidateAndCommitRecvCounter atomically checks whether n is acceptable for
// deviceID and, if so, runs decrypt and persists n as seen -- all under one
// held lock, with no gap between the replay check and the commit. This
// closes a TOCTOU window that existed when validate and commit were separate
// locked calls with AEAD Open in between: two concurrent decrypts of the
// same captured counter could both pass the replay check before either
// recorded it as seen, so both would decrypt successfully. decrypt is only
// invoked once n has passed the replay check, and n is only persisted if
// decrypt succeeds, so a garbage envelope with a guessed-but-unused counter
// can't burn that counter and cause the legitimate message to be rejected
// later.
//
// This method is called exclusively from the running agent process's own
// request/frame handlers (pair.go never calls it), so s.mu.Lock() -- an
// in-process mutex -- is sufficient to close the TOCTOU race; it does not
// itself need cross-process locking the way AddDevice/NextSendCounter do.
//
// recv_highest and recv_window_bits are read/written via int64<->uint64
// bit-reinterpretation (never Scanned directly into a uint64 destination):
// database/sql's default converter rejects a negative driver int64 when the
// Scan destination is *uint64, so a stored value with bit 63 set (which
// recv_window_bits can legitimately have -- replayWindowSize == 64 means
// shifts up to 63 occur) would fail to load. Scanning into int64 and
// converting via uint64(n) in Go is a lossless reinterpretation of the same
// 64 bits and has no such restriction.
func (s *Store) ValidateAndCommitRecvCounter(deviceID string, n uint64, decrypt func() ([]byte, error)) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var highestRaw, windowBitsRaw, highestSetRaw int64
	err := s.db.QueryRow(`SELECT recv_highest, recv_highest_set, recv_window_bits FROM devices WHERE device_id = ?`, deviceID).
		Scan(&highestRaw, &highestSetRaw, &windowBitsRaw)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("unknown device %q", deviceID)
		}
		return nil, fmt.Errorf("validate recv counter: %w", err)
	}
	highest := uint64(highestRaw)
	windowBits := uint64(windowBitsRaw)
	highestSet := highestSetRaw != 0

	if !canAcceptRecvCounter(highest, highestSet, windowBits, n) {
		return nil, fmt.Errorf("decrypt_failed")
	}

	pt, err := decrypt()
	if err != nil {
		return nil, err
	}

	newHighest, newWindowBits := commitRecvCounter(highest, highestSet, windowBits, n)
	if _, err := s.db.Exec(`UPDATE devices SET recv_highest = ?, recv_window_bits = ?, recv_highest_set = 1, last_active_unix = ? WHERE device_id = ?`,
		int64(newHighest), int64(newWindowBits), time.Now().Unix(), deviceID); err != nil {
		return nil, fmt.Errorf("commit recv counter: %w", err)
	}
	return pt, nil
}
