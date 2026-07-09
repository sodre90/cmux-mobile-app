package e2e

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
)

type bodyEnvelope struct {
	V  int    `json:"v"`
	N  uint64 `json:"n"`
	CT string `json:"ct"`
}

func (s *Store) EncryptBody(deviceID string, plaintext []byte) ([]byte, error) {
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	n, err := s.NextSendCounter(deviceID)
	if err != nil {
		return nil, err
	}
	ct, err := Seal(secret, Nonce(DirAgentToDevice, n), plaintext)
	if err != nil {
		return nil, err
	}
	return json.Marshal(bodyEnvelope{V: 1, N: n, CT: base64.StdEncoding.EncodeToString(ct)})
}

func (s *Store) DecryptBody(deviceID string, envelope []byte) ([]byte, error) {
	var env bodyEnvelope
	if err := json.Unmarshal(envelope, &env); err != nil || env.V != 1 {
		return nil, fmt.Errorf("decrypt_failed")
	}
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	ct, err := base64.StdEncoding.DecodeString(env.CT)
	if err != nil {
		return nil, fmt.Errorf("decrypt_failed")
	}
	return s.ValidateAndCommitRecvCounter(deviceID, env.N, func() ([]byte, error) {
		pt, err := Open(secret, Nonce(DirDeviceToAgent, env.N), ct)
		if err != nil {
			return nil, fmt.Errorf("decrypt_failed")
		}
		return pt, nil
	})
}
