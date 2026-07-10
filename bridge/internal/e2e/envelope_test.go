package e2e

import (
	"encoding/base64"
	"encoding/json"
	"path/filepath"
	"testing"
)

func setupPairedStore(t *testing.T) (*Store, string) {
	t.Helper()
	dir := t.TempDir()
	s := mustOpen(t, filepath.Join(dir, "sessions.json"))
	pub := testPubKey(t)
	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i)
	}
	if err := s.AddDevice("dev1", pub, secret); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	return s, "dev1"
}

func TestEncryptBodyDeviceCanDecrypt(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	envelope, err := s.EncryptBody(deviceID, []byte(`{"hello":"world"}`))
	if err != nil {
		t.Fatalf("EncryptBody: %v", err)
	}

	var env bodyEnvelope
	if err := json.Unmarshal(envelope, &env); err != nil {
		t.Fatalf("unmarshal envelope: %v", err)
	}
	ct, err := base64.StdEncoding.DecodeString(env.CT)
	if err != nil {
		t.Fatalf("decode ct: %v", err)
	}
	pt, err := Open(secret, Nonce(DirAgentToDevice, env.N), ct)
	if err != nil {
		t.Fatalf("device-side Open: %v", err)
	}
	if string(pt) != `{"hello":"world"}` {
		t.Fatalf("plaintext mismatch: got %q", pt)
	}
}

func TestDecryptBodyAcceptsDeviceMessage(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	ct, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	envelope, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal envelope: %v", err)
	}

	pt, err := s.DecryptBody(deviceID, envelope)
	if err != nil {
		t.Fatalf("DecryptBody: %v", err)
	}
	if string(pt) != `{"reply":"ok"}` {
		t.Fatalf("plaintext mismatch: got %q", pt)
	}
}

func TestDecryptBodyRejectsReplay(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	ct, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	envelope, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal envelope: %v", err)
	}

	if _, err := s.DecryptBody(deviceID, envelope); err != nil {
		t.Fatalf("first DecryptBody: %v", err)
	}
	if _, err := s.DecryptBody(deviceID, envelope); err == nil {
		t.Fatal("expected second DecryptBody with same counter to be rejected as replay")
	}
}

func TestDecryptBodyRejectsCorruptedCiphertextWithoutDesyncingCounter(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	ct, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	ct[0] ^= 0xff // corrupt
	corrupted, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal corrupted envelope: %v", err)
	}
	if _, err := s.DecryptBody(deviceID, corrupted); err == nil {
		t.Fatal("expected DecryptBody to reject corrupted ciphertext")
	}

	// The real message at counter 0 must still be acceptable — a corrupted
	// message must never advance the watermark.
	realCT, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	real, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(realCT)})
	if err != nil {
		t.Fatalf("marshal real envelope: %v", err)
	}
	if _, err := s.DecryptBody(deviceID, real); err != nil {
		t.Fatalf("expected the real message at counter 0 to still succeed after a corrupted attempt, got: %v", err)
	}
}
