package main

import (
	"bytes"
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"time"

	"github.com/mdp/qrterminal/v3"

	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
)

// pairingQR is the JSON payload rendered into the QR code. The phone scans
// it, generates its own e2e keypair, and POSTs PairURL directly — it needs
// nothing else to complete self-service pairing.
type pairingQR struct {
	PairURL     string `json:"pair_url"`
	Code        string `json:"code"`
	AgentPubkey string `json:"agent_pubkey"` // base64 X25519 public key
	ExpiresAt   string `json:"expires_at"`
	TenantID    string `json:"tenant_id"` // informational only -- /devices/pair never needs it in the request
}

// httpsBaseFromRelayURL converts the agent's wss:// tunnel URL
// (e.g. "wss://cmux.example.com/agent/tunnel") into the https base the same
// mTLS vhost serves the agent-facing pairing-code endpoints on
// (e.g. "https://cmux.example.com").
func httpsBaseFromRelayURL(relayURL string) (string, error) {
	u, err := url.Parse(relayURL)
	if err != nil {
		return "", fmt.Errorf("parse relay_url: %w", err)
	}
	switch u.Scheme {
	case "wss":
		u.Scheme = "https"
	case "ws":
		u.Scheme = "http"
	default:
		return "", fmt.Errorf("relay_url must be ws:// or wss://, got %q", u.Scheme)
	}
	u.Path = ""
	return u.String(), nil
}

func requestPairingCode(client *http.Client, agentBase, agentPubkeyB64 string) (code, expiresAt, tenantID string, err error) {
	payload, err := json.Marshal(struct {
		AgentPubkey string `json:"agent_pubkey"`
	}{AgentPubkey: agentPubkeyB64})
	if err != nil {
		return "", "", "", err
	}
	resp, err := client.Post(agentBase+"/agent/pairing-code", "application/json", bytes.NewReader(payload))
	if err != nil {
		return "", "", "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", "", fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var body struct {
		Code      string `json:"code"`
		ExpiresAt string `json:"expires_at"`
		TenantID  string `json:"tenant_id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", "", "", err
	}
	return body.Code, body.ExpiresAt, body.TenantID, nil
}

func pollPairingCode(client *http.Client, agentBase, code string) (devicePubkey, tokenHash string, redeemed bool, err error) {
	resp, err := client.Get(agentBase + "/agent/pairing-code/" + code)
	if err != nil {
		return "", "", false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", false, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var body struct {
		Redeemed     bool   `json:"redeemed"`
		DevicePubkey string `json:"device_pubkey"`
		TokenHash    string `json:"token_hash"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", "", false, err
	}
	return body.DevicePubkey, body.TokenHash, body.Redeemed, nil
}

// pairDevice runs one pairing session: request a fresh pairing code from the
// relay, render it (with the agent's e2e public key and the phone's
// no-cert pairing URL) as a QR code to out, then poll agentBase until the
// code is redeemed or deadline passes. On redemption it derives the shared
// secret with the redeeming device and persists it to sessions, keyed by the
// device's token hash — the same key the relay's proxy Director injects as
// X-Device-ID on every subsequent request from that device.
func pairDevice(client *http.Client, agentBase, devicePairURL string, identity *e2e.Identity, sessions *e2e.Store, out io.Writer, pollPeriod time.Duration, deadline time.Time) error {
	agentPubkeyB64 := base64.StdEncoding.EncodeToString(identity.PublicKey().Bytes())
	code, expiresAt, tenantID, err := requestPairingCode(client, agentBase, agentPubkeyB64)
	if err != nil {
		return fmt.Errorf("request pairing code: %w", err)
	}

	qr := pairingQR{
		PairURL:     devicePairURL,
		Code:        code,
		AgentPubkey: agentPubkeyB64,
		ExpiresAt:   expiresAt,
		TenantID:    tenantID,
	}
	qrJSON, err := json.Marshal(qr)
	if err != nil {
		return fmt.Errorf("marshal QR payload: %w", err)
	}
	fmt.Fprintf(out, "Scan this QR code with the cmux app (code expires %s):\n\n", expiresAt)
	qrterminal.GenerateHalfBlock(string(qrJSON), qrterminal.L, out)
	fmt.Fprintf(out, "\nOr enter this code manually: %s\n\n", code)

	for {
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out waiting for pairing code %s to be redeemed", code)
		}
		devicePubkeyB64, tokenHash, redeemed, err := pollPairingCode(client, agentBase, code)
		if err != nil {
			// Transient relay errors (network blip, brief 5xx) shouldn't abort
			// the whole pairing attempt -- keep polling until the deadline.
			fmt.Fprintf(out, "poll error (will retry): %v\n", err)
			time.Sleep(pollPeriod)
			continue
		}
		if !redeemed {
			time.Sleep(pollPeriod)
			continue
		}
		devicePubkeyRaw, err := base64.StdEncoding.DecodeString(devicePubkeyB64)
		if err != nil {
			return fmt.Errorf("decode device pubkey: %w", err)
		}
		devicePub, err := ecdh.X25519().NewPublicKey(devicePubkeyRaw)
		if err != nil {
			return fmt.Errorf("parse device pubkey: %w", err)
		}
		secret, err := e2e.DeriveSharedSecret(identity.Priv, devicePub)
		if err != nil {
			return fmt.Errorf("derive shared secret: %w", err)
		}
		if err := sessions.AddDevice(tokenHash, devicePub, secret); err != nil {
			return fmt.Errorf("persist paired device: %w", err)
		}
		fmt.Fprintf(out, "Device paired successfully.\n")
		return nil
	}
}

func runPairDevice(args []string) int {
	fs := flag.NewFlagSet("pair-device", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultAgentConfigPath(), "path to agent.toml")
	timeout := fs.Duration("timeout", 5*time.Minute, "how long to wait for the phone to scan and redeem the code")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, err := config.LoadAgent(*cfgPath)
	if err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	if cfg.RelayURL == "" {
		log.Printf("pair-device: relay_url is required")
		return 1
	}
	agentBase, err := httpsBaseFromRelayURL(cfg.RelayURL)
	if err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	// /devices/pair is public on the same main vhost as the agent-facing
	// pairing-code endpoints (see Global Constraints in the plan) -- the
	// bootstrap vhost only serves /tenants/register.
	devicePairURL := agentBase + "/devices/pair"
	tlsCfg, err := loadTLS(cfg.ClientCert, cfg.ClientKey, cfg.CACert)
	if err != nil {
		log.Printf("pair-device: tls: %v", err)
		return 1
	}
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: tlsCfg}}

	identity, err := e2e.LoadOrCreateIdentity(cfg.IdentityKey)
	if err != nil {
		log.Printf("pair-device: e2e identity: %v", err)
		return 1
	}
	sessions := e2e.OpenStore(cfg.SessionStore)

	if err := pairDevice(client, agentBase, devicePairURL, identity, sessions, os.Stdout, 2*time.Second, time.Now().Add(*timeout)); err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	return 0
}
