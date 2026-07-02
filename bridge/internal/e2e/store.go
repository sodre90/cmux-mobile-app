package e2e

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

type deviceSession struct {
	DevicePubKey   string `json:"device_pubkey"`
	SharedSecret   string `json:"shared_secret"`
	SendCounter    uint64 `json:"send_counter"`
	RecvCounter    uint64 `json:"recv_counter"`
	RecvCounterSet bool   `json:"recv_counter_set"`
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
		DevicePubKey: base64.StdEncoding.EncodeToString(devicePub.Bytes()),
		SharedSecret: base64.StdEncoding.EncodeToString(sharedSecret),
	}
	return s.save(f)
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

func (s *Store) ValidateRecvCounter(deviceID string, n uint64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return false, err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return false, fmt.Errorf("unknown device %q", deviceID)
	}
	if d.RecvCounterSet && n <= d.RecvCounter {
		return false, nil
	}
	return true, nil
}

func (s *Store) CommitRecvCounter(deviceID string, n uint64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return fmt.Errorf("unknown device %q", deviceID)
	}
	if d.RecvCounterSet && n <= d.RecvCounter {
		return nil
	}
	d.RecvCounter = n
	d.RecvCounterSet = true
	f.Devices[deviceID] = d
	return s.save(f)
}
