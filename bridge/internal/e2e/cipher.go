package e2e

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/binary"
	"errors"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	DirAgentToDevice byte = 0x00
	DirDeviceToAgent byte = 0x01
)

func buildInfo(pubA, pubB []byte) []byte {
	a, b := pubA, pubB
	if bytes.Compare(a, b) > 0 {
		a, b = b, a
	}
	info := []byte("cmux-bridge e2e v1|")
	info = append(info, a...)
	info = append(info, '|')
	info = append(info, b...)
	return info
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
