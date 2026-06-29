package main

import (
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

func TestNextBackoffCaps(t *testing.T) {
	d := time.Second
	for i := 0; i < 10; i++ {
		d = nextBackoff(d)
	}
	if d != 30*time.Second {
		t.Fatalf("backoff should cap at 30s, got %v", d)
	}
	if got := nextBackoff(time.Second); got != 2*time.Second {
		t.Fatalf("nextBackoff(1s)=%v want 2s", got)
	}
}

func writeSelfSigned(t *testing.T) (certPath, keyPath string) {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "mac-agent"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	certPath = filepath.Join(dir, "c.pem")
	keyPath = filepath.Join(dir, "k.pem")
	cf, _ := os.Create(certPath)
	_ = pem.Encode(cf, &pem.Block{Type: "CERTIFICATE", Bytes: der})
	cf.Close()
	kf, _ := os.Create(keyPath)
	_ = pem.Encode(kf, &pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(priv)})
	kf.Close()
	return certPath, keyPath
}

func TestLoadTLS(t *testing.T) {
	cert, key := writeSelfSigned(t)
	cfg, err := loadTLS(cert, key, cert) // self-signed: cert doubles as its CA
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Certificates) != 1 {
		t.Fatalf("want 1 client cert, got %d", len(cfg.Certificates))
	}
	if cfg.RootCAs == nil {
		t.Fatal("want RootCAs set from ca_cert")
	}
}

func TestLoadTLSEmptyCAUsesSystemRoots(t *testing.T) {
	// A Let's Encrypt server cert is publicly trusted, so an empty ca_cert must
	// not error and must leave RootCAs nil (Go falls back to the system roots).
	cert, key := writeSelfSigned(t)
	cfg, err := loadTLS(cert, key, "")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.RootCAs != nil {
		t.Fatal("empty ca_cert should leave RootCAs nil (system roots)")
	}
	if len(cfg.Certificates) != 1 {
		t.Fatalf("want 1 client cert, got %d", len(cfg.Certificates))
	}
}

func TestLoadTLSMissingFileErrors(t *testing.T) {
	if _, err := loadTLS("/no/cert", "/no/key", "/no/ca"); err == nil {
		t.Fatal("want error for missing cert files")
	}
}
