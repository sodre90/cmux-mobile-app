package e2e

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type deviceSession struct {
	DevicePubKey   string `json:"device_pubkey"`
	SharedSecret   string `json:"shared_secret"`
	SendCounter    uint64 `json:"send_counter"`
	RecvHighest    uint64 `json:"recv_highest"`
	RecvHighestSet bool   `json:"recv_highest_set"`
	RecvWindowBits uint64 `json:"recv_window_bits"`
	// LastActiveUnix is the last time this device successfully sent the agent
	// a decryptable request body or frame (ValidateAndCommitRecvCounter),
	// seeded to the pairing time so a brand-new device isn't immediately
	// treated as inactive before it has sent anything. Building or sending a
	// push for a device (EncryptFrame/NextSendCounter) never updates this --
	// only genuine inbound traffic proves the device is still in use; if
	// pushes counted, an unpaired-but-never-explicitly-removed device would
	// stay "active" forever just by virtue of the agent trying to push to it.
	LastActiveUnix int64 `json:"last_active_unix"`
}

type fileFormat struct {
	Devices map[string]deviceSession `json:"devices"`
}

type Store struct {
	path string
	mu   sync.Mutex
}

func OpenStore(path string) *Store {
	return &Store{path: path}
}

func (s *Store) load() (fileFormat, error) {
	f := fileFormat{Devices: map[string]deviceSession{}}
	raw, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return f, nil
		}
		return f, fmt.Errorf("read session store %s: %w", s.path, err)
	}
	if len(raw) == 0 {
		return f, nil
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		return f, fmt.Errorf("parse session store %s: %w", s.path, err)
	}
	if f.Devices == nil {
		f.Devices = map[string]deviceSession{}
	}
	return f, nil
}

func (s *Store) save(f fileFormat) error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return fmt.Errorf("create dir for session store: %w", err)
	}
	raw, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal session store: %w", err)
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return fmt.Errorf("write session store: %w", err)
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) AddDevice(deviceID string, devicePub *ecdh.PublicKey, sharedSecret []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return err
	}
	f.Devices[deviceID] = deviceSession{
		DevicePubKey:   base64.StdEncoding.EncodeToString(devicePub.Bytes()),
		SharedSecret:   base64.StdEncoding.EncodeToString(sharedSecret),
		LastActiveUnix: time.Now().Unix(),
	}
	return s.save(f)
}

// DeviceIDs returns every deviceID this agent has ever paired with (direct or
// relay-mediated alike -- AddDevice is called identically by both pairing
// flows, so this one local file is the complete list). This includes devices
// that have gone stale (see ActiveDeviceIDs); callers that need to build
// per-device push payloads should use ActiveDeviceIDs instead.
func (s *Store) DeviceIDs() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return nil
	}
	out := make([]string, 0, len(f.Devices))
	for id := range f.Devices {
		out = append(out, id)
	}
	return out
}

// staleDeviceAge is how long a device may go without successfully decrypting
// a request/frame (proving it still holds the shared secret and is in
// active use) before ActiveDeviceIDs stops considering it paired. There is
// no cross-process revocation signal from auth.Store reaching this agent-side
// file in relay mode (see cmd/cmux-relay/commands.go's Revoke, which only
// touches the relay's own SQLite store), so recent activity is the only
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
	f, err := s.load()
	if err != nil {
		return nil
	}
	cutoff := time.Now().Add(-staleDeviceAge).Unix()
	out := make([]string, 0, len(f.Devices))
	for id, d := range f.Devices {
		if d.LastActiveUnix >= cutoff {
			out = append(out, id)
		}
	}
	return out
}

func (s *Store) SharedSecret(deviceID string) (secret []byte, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return nil, false
	}
	d, found := f.Devices[deviceID]
	if !found {
		return nil, false
	}
	secret, err = base64.StdEncoding.DecodeString(d.SharedSecret)
	if err != nil {
		return nil, false
	}
	return secret, true
}

func (s *Store) NextSendCounter(deviceID string) (uint64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return 0, err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return 0, fmt.Errorf("unknown device %q", deviceID)
	}
	n := d.SendCounter
	d.SendCounter++
	f.Devices[deviceID] = d
	if err := s.save(f); err != nil {
		return 0, err
	}
	return n, nil
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
func (s *Store) ValidateAndCommitRecvCounter(deviceID string, n uint64, decrypt func() ([]byte, error)) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	f, err := s.load()
	if err != nil {
		return nil, err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return nil, fmt.Errorf("unknown device %q", deviceID)
	}
	if !canAcceptRecvCounter(d.RecvHighest, d.RecvHighestSet, d.RecvWindowBits, n) {
		return nil, fmt.Errorf("decrypt_failed")
	}

	pt, err := decrypt()
	if err != nil {
		return nil, err
	}

	d.RecvHighest, d.RecvWindowBits = commitRecvCounter(d.RecvHighest, d.RecvHighestSet, d.RecvWindowBits, n)
	d.RecvHighestSet = true
	d.LastActiveUnix = time.Now().Unix()
	f.Devices[deviceID] = d
	if err := s.save(f); err != nil {
		return nil, err
	}
	return pt, nil
}
