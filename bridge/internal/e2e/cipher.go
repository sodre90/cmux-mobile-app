package e2e

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	DirAgentToDevice byte = 0x00
	DirDeviceToAgent byte = 0x01
)

// canonicalPair orders two raw public keys the same way regardless of which
// caller passes which argument first, so both peers in an exchange derive
// identical output from a pair of keys they each hold in opposite roles.
func canonicalPair(pubA, pubB []byte) (lo, hi []byte) {
	if bytes.Compare(pubA, pubB) > 0 {
		return pubB, pubA
	}
	return pubA, pubB
}

func buildInfo(pubA, pubB []byte) []byte {
	a, b := canonicalPair(pubA, pubB)
	info := []byte("cmux-bridge e2e v1|")
	info = append(info, a...)
	info = append(info, '|')
	info = append(info, b...)
	return info
}

// PairingFingerprint is a short authentication string (SAS) both peers in a
// device-pairing exchange compute locally, purely from public keys they
// already hold, and a human compares by eye before either side commits
// trust -- see docs/superpowers/specs/2026-07-10-pairing-mitm-fingerprint-design.md.
// Order-independent: either peer may pass its own two keys in either
// argument order and get the same result.
func PairingFingerprint(pubkeyA, pubkeyB []byte) string {
	lo, hi := canonicalPair(pubkeyA, pubkeyB)
	sum := sha256.Sum256(append(append([]byte{}, lo...), hi...))
	return fmt.Sprintf("%X-%X-%X", sum[0:2], sum[2:4], sum[4:6])
}

func DeriveSharedSecret(myPriv *ecdh.PrivateKey, theirPub *ecdh.PublicKey) ([]byte, error) {
	secret, err := myPriv.ECDH(theirPub)
	if err != nil {
		return nil, err
	}
	info := buildInfo(myPriv.PublicKey().Bytes(), theirPub.Bytes())
	return hkdf.Key(sha256.New, secret, nil, string(info), 32)
}

func Nonce(direction byte, counter uint64) []byte {
	n := make([]byte, chacha20poly1305.NonceSizeX)
	n[15] = direction
	binary.BigEndian.PutUint64(n[16:], counter)
	return n
}

func Seal(key, nonce, plaintext []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	return aead.Seal(nil, nonce, plaintext, nil), nil
}

func Open(key, nonce, ciphertext []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	pt, err := aead.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, errors.New("decrypt_failed")
	}
	return pt, nil
}
