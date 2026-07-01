package ca

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
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
