package e2e

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/hex"
	"testing"
)

func TestFixedCipherVector(t *testing.T) {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	plaintext := []byte("cmux-bridge e2e test vector")
	nonce := Nonce(DirAgentToDevice, 42)

	ct, err := Seal(key, nonce, plaintext)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	want := "3adf930c2c38c2dc6de9e1fab5be816f607fea9f2d9e503a7f22277d65a588c593c28255c0dc93cac7a52a"
	if got := hex.EncodeToString(ct); got != want {
		t.Fatalf("ciphertext mismatch:\n got: %s\nwant: %s", got, want)
	}

	pt, err := Open(key, nonce, ct)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if string(pt) != string(plaintext) {
		t.Fatalf("round-trip mismatch: got %q want %q", pt, plaintext)
	}
}

func TestDeriveSharedSecretFixedVector(t *testing.T) {
	agentRaw := make([]byte, 32)
	deviceRaw := make([]byte, 32)
	for i := range agentRaw {
		agentRaw[i] = 0x01
		deviceRaw[i] = 0x02
	}
	agentPriv, err := ecdh.X25519().NewPrivateKey(agentRaw)
	if err != nil {
		t.Fatalf("agent NewPrivateKey: %v", err)
	}
	devicePriv, err := ecdh.X25519().NewPrivateKey(deviceRaw)
	if err != nil {
		t.Fatalf("device NewPrivateKey: %v", err)
	}

	wantAgentPub := "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209"[:64]
	wantDevicePub := "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59"[:64]
	if got := hex.EncodeToString(agentPriv.PublicKey().Bytes()); got != wantAgentPub {
		t.Fatalf("agent pubkey mismatch:\n got: %s\nwant: %s", got, wantAgentPub)
	}
	if got := hex.EncodeToString(devicePriv.PublicKey().Bytes()); got != wantDevicePub {
		t.Fatalf("device pubkey mismatch:\n got: %s\nwant: %s", got, wantDevicePub)
	}

	agentSide, err := DeriveSharedSecret(agentPriv, devicePriv.PublicKey())
	if err != nil {
		t.Fatalf("DeriveSharedSecret (agent side): %v", err)
	}
	deviceSide, err := DeriveSharedSecret(devicePriv, agentPriv.PublicKey())
	if err != nil {
		t.Fatalf("DeriveSharedSecret (device side): %v", err)
	}
	wantSecret := "0c657b7b4a6f6eede1d9f03bad4f9c898e9291c22eeb4cd09f12df79394837d6"[:64]
	if got := hex.EncodeToString(agentSide); got != wantSecret {
		t.Fatalf("agent-side shared secret mismatch:\n got: %s\nwant: %s", got, wantSecret)
	}
	if got := hex.EncodeToString(deviceSide); got != wantSecret {
		t.Fatalf("device-side shared secret mismatch:\n got: %s\nwant: %s", got, wantSecret)
	}

	ct, err := Seal(agentSide, Nonce(DirAgentToDevice, 0), []byte("hello from agent"))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	wantCT := "WI1V8JTQV+ypKcvWgSHUxv/C4quvVNDn/NUBnveC+zA="
	if got := base64.StdEncoding.EncodeToString(ct); got != wantCT {
		t.Fatalf("ciphertext mismatch:\n got: %s\nwant: %s", got, wantCT)
	}

	pt, err := Open(deviceSide, Nonce(DirAgentToDevice, 0), ct)
	if err != nil {
		t.Fatalf("Open (device side): %v", err)
	}
	if string(pt) != "hello from agent" {
		t.Fatalf("round-trip mismatch: got %q", pt)
	}
}

func TestOpenRejectsWrongKey(t *testing.T) {
	key1 := make([]byte, 32)
	key2 := make([]byte, 32)
	key2[0] = 0xff
	nonce := Nonce(DirAgentToDevice, 0)
	ct, err := Seal(key1, nonce, []byte("secret"))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if _, err := Open(key2, nonce, ct); err == nil {
		t.Fatal("expected Open with wrong key to fail")
	}
}

func TestOpenRejectsWrongDirection(t *testing.T) {
	key := make([]byte, 32)
	ct, err := Seal(key, Nonce(DirAgentToDevice, 0), []byte("secret"))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if _, err := Open(key, Nonce(DirDeviceToAgent, 0), ct); err == nil {
		t.Fatal("expected Open with mismatched direction tag to fail, proving disjoint nonce spaces")
	}
}
