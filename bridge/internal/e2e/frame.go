package e2e

import (
	"encoding/binary"
	"errors"
	"fmt"
)

func EncodeFrame(key []byte, direction byte, counter uint64, plaintext []byte) ([]byte, error) {
	ct, err := Seal(key, Nonce(direction, counter), plaintext)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 8+len(ct))
	binary.BigEndian.PutUint64(out[:8], counter)
	copy(out[8:], ct)
	return out, nil
}

func DecodeFrame(key []byte, direction byte, frame []byte) (counter uint64, plaintext []byte, err error) {
	if len(frame) < 8 {
		return 0, nil, errors.New("decrypt_failed")
	}
	counter = binary.BigEndian.Uint64(frame[:8])
	pt, err := Open(key, Nonce(direction, counter), frame[8:])
	if err != nil {
		return 0, nil, err
	}
	return counter, pt, nil
}

func (s *Store) EncryptFrame(deviceID string, plaintext []byte) ([]byte, error) {
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	n, err := s.NextSendCounter(deviceID)
	if err != nil {
		return nil, err
	}
	return EncodeFrame(secret, DirAgentToDevice, n, plaintext)
}

func (s *Store) DecryptFrame(deviceID string, frame []byte) ([]byte, error) {
	if len(frame) < 8 {
		return nil, fmt.Errorf("decrypt_failed")
	}
	n := binary.BigEndian.Uint64(frame[:8])
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	valid, err := s.ValidateRecvCounter(deviceID, n)
	if err != nil {
		return nil, err
	}
	if !valid {
		return nil, fmt.Errorf("decrypt_failed")
	}
	pt, err := Open(secret, Nonce(DirDeviceToAgent, n), frame[8:])
	if err != nil {
		return nil, fmt.Errorf("decrypt_failed")
	}
	if err := s.CommitRecvCounter(deviceID, n); err != nil {
		return nil, err
	}
	return pt, nil
}
