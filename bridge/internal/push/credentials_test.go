package push

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"os"
	"path/filepath"
	"testing"
)

func writeServiceAccount(t *testing.T) string {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(priv),
	})
	sa := map[string]string{
		"type":         "service_account",
		"project_id":   "p",
		"private_key":  string(keyPEM),
		"client_email": "x@p.iam.gserviceaccount.com",
		"token_uri":    "https://oauth2.googleapis.com/token",
	}
	b, err := json.Marshal(sa)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "sa.json")
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestFromServiceAccountValid(t *testing.T) {
	s, err := FromServiceAccount(context.Background(), "proj-123", writeServiceAccount(t))
	if err != nil {
		t.Fatal(err)
	}
	if s.ProjectID != "proj-123" {
		t.Fatalf("ProjectID = %q", s.ProjectID)
	}
	if s.Token == nil {
		t.Fatal("Token func should be set")
	}
}

func TestFromServiceAccountMissingFile(t *testing.T) {
	if _, err := FromServiceAccount(context.Background(), "p", "/no/such/file"); err == nil {
		t.Fatal("want error for missing credentials file")
	}
}

func TestFromServiceAccountMalformed(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.json")
	if err := os.WriteFile(path, []byte("not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := FromServiceAccount(context.Background(), "p", path); err == nil {
		t.Fatal("want error for malformed credentials")
	}
}
