// Package auth manages device tokens and one-time pairing codes for the bridge.
// Device tokens are long-lived bearer credentials persisted to disk; pairing
// codes are short, single-use, in-memory codes exchanged for a token during
// first-run pairing.
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Device is a paired client. FCM is the optional push registration token.
type Device struct {
	Token   string    `json:"token"`
	Name    string    `json:"name"`
	FCM     string    `json:"fcm,omitempty"`
	Created time.Time `json:"created"`
}

// pairingCode is an unredeemed one-time code held in memory only.
type pairingCode struct {
	expires time.Time
}

// Store holds devices (persisted) and pending pairing codes (in-memory).
type Store struct {
	path  string
	mu    sync.Mutex
	devs  map[string]Device      // token -> device
	codes map[string]pairingCode // code -> metadata
}

// pairing-code alphabet: unambiguous (no 0/O/1/I).
const codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

// Open loads the store from path, creating an empty one if the file is absent.
func Open(path string) (*Store, error) {
	s := &Store{
		path:  path,
		devs:  map[string]Device{},
		codes: map[string]pairingCode{},
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return s, nil
		}
		return nil, fmt.Errorf("read token store: %w", err)
	}
	var list []Device
	if len(data) > 0 {
		if err := json.Unmarshal(data, &list); err != nil {
			return nil, fmt.Errorf("parse token store: %w", err)
		}
	}
	for _, d := range list {
		s.devs[d.Token] = d
	}
	return s, nil
}

// Reload re-reads the device file from disk and atomically replaces the
// in-memory device map, returning the new device count. Pending in-memory
// pairing codes are left untouched. On a read or parse error the current
// devices are kept and the error is returned, so a missing or corrupt file
// never wipes a running relay's devices. This lets a newly paired device
// (written by a separate `cmux-relay pair` process) take effect on SIGHUP
// without restarting the relay.
func (s *Store) Reload() (int, error) {
	data, err := os.ReadFile(s.path)
	if err != nil {
		return 0, fmt.Errorf("reload token store: %w", err)
	}
	var list []Device
	if len(data) > 0 {
		if err := json.Unmarshal(data, &list); err != nil {
			return 0, fmt.Errorf("parse token store: %w", err)
		}
	}
	next := make(map[string]Device, len(list))
	for _, d := range list {
		next[d.Token] = d
	}
	s.mu.Lock()
	s.devs = next
	s.mu.Unlock()
	return len(next), nil
}

// Issue creates and persists a new device token.
func (s *Store) Issue(name string) (string, error) {
	tok, err := randomHex(32)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	s.devs[tok] = Device{Token: tok, Name: name, Created: time.Now().UTC()}
	err = s.persistLocked()
	s.mu.Unlock()
	if err != nil {
		return "", err
	}
	return tok, nil
}

// Verify returns the device for a token using a constant-time comparison.
func (s *Store) Verify(token string) (Device, bool) {
	if token == "" {
		return Device{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for t, d := range s.devs {
		if subtle.ConstantTimeCompare([]byte(t), []byte(token)) == 1 {
			return d, true
		}
	}
	return Device{}, false
}

// List returns devices with tokens redacted to the last 6 characters.
func (s *Store) List() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Device, 0, len(s.devs))
	for _, d := range s.devs {
		red := d
		if len(d.Token) > 6 {
			red.Token = "..." + d.Token[len(d.Token)-6:]
		}
		out = append(out, red)
	}
	return out
}

// Revoke removes a device by token. It returns whether a device was removed.
func (s *Store) Revoke(token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.devs[token]; !ok {
		return false
	}
	delete(s.devs, token)
	_ = s.persistLocked()
	return true
}

// SetFCMToken records the FCM registration token for a device token.
func (s *Store) SetFCMToken(token, fcm string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devs[token]
	if !ok {
		return false
	}
	d.FCM = fcm
	s.devs[token] = d
	_ = s.persistLocked()
	return true
}

// FCMTokens returns all non-empty FCM registration tokens.
func (s *Store) FCMTokens() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []string{}
	for _, d := range s.devs {
		if d.FCM != "" {
			out = append(out, d.FCM)
		}
	}
	return out
}

// NewPairingCode generates a single-use pairing code valid for ttl.
func (s *Store) NewPairingCode(ttl time.Duration) string {
	code := randomCode(8)
	s.mu.Lock()
	s.codes[code] = pairingCode{expires: time.Now().Add(ttl)}
	s.mu.Unlock()
	return code
}

// RedeemPairingCode exchanges a valid, unexpired code for a freshly issued
// device token. The code is consumed regardless of success to prevent reuse.
func (s *Store) RedeemPairingCode(code, name string) (string, bool) {
	s.mu.Lock()
	pc, ok := s.codes[code]
	if ok {
		delete(s.codes, code)
	}
	s.mu.Unlock()
	if !ok || time.Now().After(pc.expires) {
		return "", false
	}
	tok, err := s.Issue(name)
	if err != nil {
		return "", false
	}
	return tok, true
}

// persistLocked atomically writes the device list. Caller must hold s.mu.
func (s *Store) persistLocked() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	list := make([]Device, 0, len(s.devs))
	for _, d := range s.devs {
		list = append(list, d)
	}
	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func randomCode(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	out := make([]byte, n)
	for i := range b {
		out[i] = codeAlphabet[int(b[i])%len(codeAlphabet)]
	}
	return string(out)
}
