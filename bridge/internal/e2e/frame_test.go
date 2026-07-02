package e2e

import "testing"

func TestEncodeDecodeFrameRoundTrip(t *testing.T) {
	key := make([]byte, 32)
	frame, err := EncodeFrame(key, DirAgentToDevice, 7, []byte("payload"))
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	counter, pt, err := DecodeFrame(key, DirAgentToDevice, frame)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	if counter != 7 {
		t.Fatalf("counter mismatch: got %d want 7", counter)
	}
	if string(pt) != "payload" {
		t.Fatalf("plaintext mismatch: got %q", pt)
	}
}

func TestDecodeFrameRejectsWrongDirection(t *testing.T) {
	key := make([]byte, 32)
	frame, err := EncodeFrame(key, DirAgentToDevice, 0, []byte("payload"))
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	if _, _, err := DecodeFrame(key, DirDeviceToAgent, frame); err == nil {
		t.Fatal("expected DecodeFrame with mismatched direction to fail")
	}
}

func TestStoreEncryptDecryptFrameRoundTrip(t *testing.T) {
	s, deviceID := setupPairedStore(t)

	frame, err := s.EncryptFrame(deviceID, []byte("term output"))
	if err != nil {
		t.Fatalf("EncryptFrame: %v", err)
	}
	secret, _ := s.SharedSecret(deviceID)
	counter, pt, err := DecodeFrame(secret, DirAgentToDevice, frame)
	if err != nil {
		t.Fatalf("device-side DecodeFrame: %v", err)
	}
	if counter != 0 || string(pt) != "term output" {
		t.Fatalf("unexpected decode: counter=%d pt=%q", counter, pt)
	}
}

func TestStoreDecryptFrameRejectsReplay(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	frame, err := EncodeFrame(secret, DirDeviceToAgent, 0, []byte("input"))
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	if _, err := s.DecryptFrame(deviceID, frame); err != nil {
		t.Fatalf("first DecryptFrame: %v", err)
	}
	if _, err := s.DecryptFrame(deviceID, frame); err == nil {
		t.Fatal("expected second DecryptFrame with same counter to be rejected as replay")
	}
}
