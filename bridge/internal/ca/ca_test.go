package ca

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func generateCSR(t *testing.T, cn string) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.CertificateRequest{Subject: pkix.Name{CommonName: cn}}
	der, err := x509.CreateCertificateRequest(rand.Reader, tmpl, key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: der})
}

func mustParseCert(t *testing.T, certPEM []byte) *x509.Certificate {
	t.Helper()
	block, _ := pem.Decode(certPEM)
	if block == nil {
		t.Fatal("no PEM block")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func TestLoadOrCreatePersistsAndReloads(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "ca.crt")
	keyPath := filepath.Join(dir, "ca.key")

	ca1, err := LoadOrCreate(certPath, keyPath)
	if err != nil {
		t.Fatal(err)
	}
	ca2, err := LoadOrCreate(certPath, keyPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(ca1.CertPEM) != string(ca2.CertPEM) {
		t.Fatal("second LoadOrCreate should reuse the persisted CA, not mint a new one")
	}
}

func TestSignCSRIgnoresCSRsOwnCNAndProducesVerifiableCert(t *testing.T) {
	dir := t.TempDir()
	c, err := LoadOrCreate(filepath.Join(dir, "ca.crt"), filepath.Join(dir, "ca.key"))
	if err != nil {
		t.Fatal(err)
	}
	// The CSR itself requests a CN of "whatever-the-caller-wants" — the CA
	// must ignore that and use the CN the relay assigns, or a hostile caller
	// could self-request another tenant's identity.
	csr := generateCSR(t, "agent:someone-elses-tenant-id")
	certPEM, serial, err := c.SignCSR(csr, "agent:the-real-tenant-id", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if serial == "" {
		t.Fatal("want a non-empty serial")
	}
	leaf := mustParseCert(t, certPEM)
	if leaf.Subject.CommonName != "agent:the-real-tenant-id" {
		t.Fatalf("CN = %q, want the assigned CN, not the CSR's requested one", leaf.Subject.CommonName)
	}
	roots := x509.NewCertPool()
	roots.AddCert(mustParseCert(t, c.CertPEM))
	if _, err := leaf.Verify(x509.VerifyOptions{Roots: roots, KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}); err != nil {
		t.Fatalf("issued cert does not verify against the CA root: %v", err)
	}
}

// writeHandRolledRSACA generates a self-signed RSA CA cert/key (mimicking a
// CA created by hand with `openssl req -x509 ...`, as opposed to one this
// package generated) and writes it to certPath/keyPath, with the private key
// PEM-encoded as keyType ("PRIVATE KEY" for PKCS8, "RSA PRIVATE KEY" for
// PKCS1) — the two encodings a hand-rolled CA commonly uses.
func writeHandRolledRSACA(t *testing.T, certPath, keyPath, keyType string) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "hand-rolled-client-ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		t.Fatal(err)
	}
	var keyDER []byte
	switch keyType {
	case "PRIVATE KEY":
		keyDER, err = x509.MarshalPKCS8PrivateKey(key)
		if err != nil {
			t.Fatal(err)
		}
	case "RSA PRIVATE KEY":
		keyDER = x509.MarshalPKCS1PrivateKey(key)
	default:
		t.Fatalf("unsupported keyType %q", keyType)
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: keyType, Bytes: keyDER})
	if err := os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestLoadOrCreateAdoptsHandRolledRSACAWithPKCS8Key(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "ca.crt")
	keyPath := filepath.Join(dir, "ca.key")
	writeHandRolledRSACA(t, certPath, keyPath, "PRIVATE KEY")

	c, err := LoadOrCreate(certPath, keyPath)
	if err != nil {
		t.Fatalf("LoadOrCreate should adopt an existing RSA CA in PKCS8 form: %v", err)
	}
	if mustParseCert(t, c.CertPEM).Subject.CommonName != "hand-rolled-client-ca" {
		t.Fatal("LoadOrCreate minted a new CA instead of adopting the existing hand-rolled one")
	}
	csr := generateCSR(t, "irrelevant")
	certPEM, _, err := c.SignCSR(csr, "agent:some-tenant", time.Hour)
	if err != nil {
		t.Fatalf("SignCSR with an adopted RSA CA: %v", err)
	}
	leaf := mustParseCert(t, certPEM)
	roots := x509.NewCertPool()
	roots.AddCert(mustParseCert(t, c.CertPEM))
	if _, err := leaf.Verify(x509.VerifyOptions{Roots: roots, KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}); err != nil {
		t.Fatalf("cert issued by an adopted RSA CA does not verify against its root: %v", err)
	}
}

func TestLoadOrCreateAdoptsHandRolledRSACAWithPKCS1Key(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "ca.crt")
	keyPath := filepath.Join(dir, "ca.key")
	writeHandRolledRSACA(t, certPath, keyPath, "RSA PRIVATE KEY")

	if _, err := LoadOrCreate(certPath, keyPath); err != nil {
		t.Fatalf("LoadOrCreate should adopt an existing RSA CA in PKCS1 form: %v", err)
	}
}

func TestSignCSRRejectsGarbage(t *testing.T) {
	dir := t.TempDir()
	c, err := LoadOrCreate(filepath.Join(dir, "ca.crt"), filepath.Join(dir, "ca.key"))
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.SignCSR([]byte("not a csr"), "agent:x", time.Hour); err == nil {
		t.Fatal("want an error for a garbage CSR")
	}
}
