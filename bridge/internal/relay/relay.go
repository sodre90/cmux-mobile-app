package relay

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log"
	"net/http"
	"net/http/httputil"
	"strings"
	"time"

	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/ca"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

// agentCNPrefix marks a client cert as belonging to a Mac agent, followed by
// its tenant ID (e.g. "agent:9f3a2c..."). Any other CN shape is a device.
const agentCNPrefix = "agent:"

// agentCertValidity is how long a freshly issued agent cert is valid. Cert
// rotation without losing tenant identity is out of scope for this version —
// after this window an agent must re-register, minting a new tenant ID.
const agentCertValidity = 365 * 24 * time.Hour

// pairingCodeTTL is how long a self-service pairing code stays redeemable.
const pairingCodeTTL = 10 * time.Minute

// Relay is the home-server rendezvous: it accepts Mac agents' tunnels and
// reverse-proxies authenticated app requests over them.
type Relay struct {
	store      *auth.Store
	reg        *Registry
	ca         *ca.CA
	relayToken string
	edgeToken  string
	proxy      *httputil.ReverseProxy
	onSession  func(context.Context, string, *yamux.Session)
}

// New builds a Relay. store may be nil only in tests that never hit auth
// routes. signer may be nil only in tests that never hit /tenants/register.
func New(store *auth.Store, signer *ca.CA, relayToken string) *Relay {
	reg := NewRegistry()
	return &Relay{
		store:      store,
		reg:        reg,
		ca:         signer,
		relayToken: relayToken,
		proxy:      newProxy(reg, relayToken),
	}
}

// SetSessionHook registers a callback invoked (in its own goroutine) for each
// accepted agent session; its context is cancelled when the session ends.
func (r *Relay) SetSessionHook(f func(context.Context, string, *yamux.Session)) { r.onSession = f }

// SetEdgeToken sets a shared secret the trusted edge (nginx) must present in
// X-Edge-Token on every request except /healthz. Empty disables the check.
func (r *Relay) SetEdgeToken(t string) { r.edgeToken = t }

// parseCN extracts the CN attribute from an RFC2253 ("CN=foo,O=bar") or legacy
// slash ("/CN=foo/O=bar") distinguished name.
func parseCN(dn string) string {
	for _, part := range strings.FieldsFunc(dn, func(r rune) bool { return r == ',' || r == '/' }) {
		part = strings.TrimSpace(part)
		if strings.HasPrefix(part, "CN=") {
			return strings.TrimPrefix(part, "CN=")
		}
	}
	return ""
}

func (r *Relay) clientCN(req *http.Request) string {
	return parseCN(req.Header.Get("X-Client-Cert-CN"))
}

// tenantFromAgentCN extracts the tenant ID from an agent CN ("agent:<id>"),
// or reports ok=false for any other CN shape (devices, or no cert at all).
func tenantFromAgentCN(cn string) (tenantID string, ok bool) {
	if !strings.HasPrefix(cn, agentCNPrefix) {
		return "", false
	}
	id := strings.TrimPrefix(cn, agentCNPrefix)
	if id == "" {
		return "", false
	}
	return id, true
}

// agentOnly extracts the calling agent's tenant ID from its mTLS CN,
// rejecting any request that isn't a valid, currently-active, VERIFIED
// agent. Used by the agent-facing pairing-code endpoints, which
// authenticate via mTLS CN rather than auth.Require's device bearer token.
func (r *Relay) agentOnly(req *http.Request) (string, bool) {
	return r.verifiedAgentTenant(req)
}

// verifiedAgentTenant extracts the calling agent's tenant ID from its mTLS
// CN, requiring nginx to report the presented certificate as independently
// verified (X-Client-Cert-Verify: SUCCESS) before trusting the CN at all.
//
// Before this method existed, every agent-CN check (handleTunnel, notAgent,
// agentOnly) trusted X-Client-Cert-CN on its own -- safe only because
// ssl_verify_client was mandatory ("on"), so nginx guaranteed any request
// that reached the relay had already presented a cert chaining to the
// trusted CA, or the TLS handshake would have failed before the request
// ever arrived. Now that ssl_verify_client is optional (see
// deploy/nginx-cmux-relay.conf, changed below in this same task, to let
// certless paired devices connect), nginx forwards X-Client-Cert-CN for ANY
// presented certificate -- including a trivial self-signed one with
// CN=agent:<any-tenant-id> -- so a bare CN match is no longer proof of agent
// identity; only a verified cert is.
func (r *Relay) verifiedAgentTenant(req *http.Request) (string, bool) {
	if req.Header.Get("X-Client-Cert-Verify") != "SUCCESS" {
		return "", false
	}
	tenantID, ok := tenantFromAgentCN(r.clientCN(req))
	if !ok || !r.store.TenantActive(tenantID) {
		return "", false
	}
	return tenantID, true
}

func (r *Relay) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("/agent/tunnel", r.handleTunnel)
	mux.Handle("POST /agent/pairing-code", http.HandlerFunc(r.handleNewPairingCode))
	mux.Handle("GET /agent/pairing-code/{code}", http.HandlerFunc(r.handlePairingCodeStatus))
	mux.Handle("POST /tenants/register", http.HandlerFunc(r.handleRegisterTenant))
	mux.Handle("POST /devices/register", r.notAgent(auth.Require(r.store, http.HandlerFunc(r.handleRegister))))
	mux.Handle("POST /devices/pair", http.HandlerFunc(r.handleDevicePair))
	mux.Handle("GET /devices/pair-info/{code}", http.HandlerFunc(r.handlePairingCodeInfo))
	mux.Handle("/", r.notAgent(auth.Require(r.store, r.logProxy(r.proxy))))
	if r.edgeToken == "" {
		return mux
	}
	return r.requireEdge(mux)
}

// requireEdge gates every route except /healthz on a shared secret the
// trusted edge (nginx) injects. Constant-time compare so the secret can't be
// probed by timing.
func (r *Relay) requireEdge(next http.Handler) http.Handler {
	want := []byte(r.edgeToken)
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path != "/healthz" &&
			subtle.ConstantTimeCompare([]byte(req.Header.Get("X-Edge-Token")), want) != 1 {
			writeJSONErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next.ServeHTTP(w, req)
	})
}

// notAgent rejects requests bearing a verified agent CN on non-tunnel
// routes.
func (r *Relay) notAgent(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if _, ok := r.verifiedAgentTenant(req); ok {
			writeJSONErr(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, req)
	})
}

func (r *Relay) handleTunnel(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := r.verifiedAgentTenant(req)
	if !ok {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	sess, err := tunnel.Accept(w, req)
	if err != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	r.reg.Set(tenantID, sess, cancel)
	log.Printf("relay: agent tunnel up (tenant=%q)", tenantID)
	if r.onSession != nil {
		go r.onSession(ctx, tenantID, sess)
	}
	<-sess.CloseChan() // block until the tunnel dies
	log.Printf("relay: agent tunnel down (tenant=%q)", tenantID)
	r.reg.Clear(tenantID, sess)
	cancel()
}

type pairingCodeResp struct {
	Code      string `json:"code"`
	ExpiresAt string `json:"expires_at"`
	TenantID  string `json:"tenant_id"`
}

type newPairingCodeReq struct {
	AgentPubkey string `json:"agent_pubkey"`
}

// handleNewPairingCode lets an already-registered agent request a fresh
// single-use pairing code to embed in a QR code (see
// cmd/cmux-bridge/pair.go). Agent-CN-gated: only a request presenting a
// valid, active agent's mTLS certificate may call this. TenantID is echoed
// back so the QR payload can carry it for display, even though /devices/pair
// itself never needs it in the request (see the Global Constraint on that
// endpoint's simplified request/response shapes) — the pairing code alone is
// resolved to a tenant server-side. The agent's e2e public key is stored
// alongside the code (not just embedded in the QR) so a phone pairing via
// manual entry can resolve it too, via handlePairingCodeInfo.
func (r *Relay) handleNewPairingCode(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := r.agentOnly(req)
	if !ok {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	var rq newPairingCodeReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.AgentPubkey == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing agent_pubkey")
		return
	}
	code, err := r.store.NewPairingCode(tenantID, rq.AgentPubkey, pairingCodeTTL)
	if err != nil {
		log.Printf("relay: new pairing code: %v", err)
		writeJSONErr(w, http.StatusInternalServerError, "internal_error")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(pairingCodeResp{
		Code:      code,
		ExpiresAt: time.Now().Add(pairingCodeTTL).UTC().Format(time.RFC3339),
		TenantID:  tenantID,
	})
}

type pairingCodeInfoResp struct {
	AgentPubkey string `json:"agent_pubkey"`
	ExpiresAt   string `json:"expires_at"`
	TenantID    string `json:"tenant_id"`
}

// handlePairingCodeInfo lets a phone that can't scan the QR (no camera, or
// pairing remotely) resolve a manually-entered pairing code to the same
// {agent_pubkey, expires_at, tenant_id} the QR itself carries, so it can
// complete /devices/pair exactly like the QR path does. Public, no auth --
// mirrors /devices/pair's own reachability (see deploy/nginx-cmux-relay.conf's
// ssl_verify_client optional change: a brand-new phone has no cert to
// present yet). Not tenant-scoped, unlike PairingCodeStatus: the caller
// doesn't know its tenant, that's what it's asking for. Collapses
// not-found/expired/already-redeemed into the same 410, matching
// /devices/pair's own error handling.
func (r *Relay) handlePairingCodeInfo(w http.ResponseWriter, req *http.Request) {
	code := req.PathValue("code")
	agentPubkey, tenantID, expiresAt, ok := r.store.PairingCodeInfo(code)
	if !ok {
		writeJSONErr(w, http.StatusGone, "pairing_code_invalid")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(pairingCodeInfoResp{
		AgentPubkey: agentPubkey,
		ExpiresAt:   expiresAt,
		TenantID:    tenantID,
	})
}

type pairingCodeStatusResp struct {
	Redeemed     bool   `json:"redeemed"`
	DevicePubkey string `json:"device_pubkey,omitempty"`
	TokenHash    string `json:"token_hash,omitempty"`
}

// handlePairingCodeStatus lets the agent that requested a pairing code poll
// for its redemption. Scoped to the caller's own tenant (via agentOnly), so
// one tenant's agent can never observe another tenant's pairing codes.
func (r *Relay) handlePairingCodeStatus(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := r.agentOnly(req)
	if !ok {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	code := req.PathValue("code")
	pubkey, hash, redeemed, ok := r.store.PairingCodeStatus(tenantID, code)
	if !ok {
		writeJSONErr(w, http.StatusNotFound, "not_found")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(pairingCodeStatusResp{
		Redeemed:     redeemed,
		DevicePubkey: pubkey,
		TokenHash:    hash,
	})
}

type registerReq struct {
	FCMToken string `json:"fcm_token"`
}

func (r *Relay) handleRegister(w http.ResponseWriter, req *http.Request) {
	if _, ok := auth.DeviceFromContext(req.Context()); !ok {
		writeJSONErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var rq registerReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.FCMToken == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing fcm_token")
		return
	}
	r.store.SetFCMToken(auth.BearerToken(req), rq.FCMToken)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"ok":true}`))
}

type registerTenantReq struct {
	CSR string `json:"csr"` // PEM-encoded PKCS#10 certificate signing request
}

type registerTenantResp struct {
	TenantID string `json:"tenant_id"`
	CertPEM  string `json:"cert_pem"`
}

// handleRegisterTenant mints a brand-new tenant identity for a Mac agent that
// has none yet. Reachable without a client cert by design (see
// deploy/nginx-cmux-relay-bootstrap.conf) — an unregistered agent has no cert
// to present. Rate limiting / abuse resistance is a known, tracked gap (see
// the design doc's non-goals) — this handler does only basic input hygiene.
func (r *Relay) handleRegisterTenant(w http.ResponseWriter, req *http.Request) {
	if r.ca == nil {
		writeJSONErr(w, http.StatusServiceUnavailable, "registration_unavailable")
		return
	}
	req.Body = http.MaxBytesReader(w, req.Body, 8<<10) // CSRs are a few hundred bytes
	var rq registerTenantReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.CSR == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing csr")
		return
	}
	tenantID, err := r.store.CreateTenant()
	if err != nil {
		log.Printf("relay: create tenant: %v", err)
		writeJSONErr(w, http.StatusInternalServerError, "internal_error")
		return
	}
	certPEM, serial, err := r.ca.SignCSR([]byte(rq.CSR), agentCNPrefix+tenantID, agentCertValidity)
	if err != nil {
		// The tenant row was already created above; a bad CSR must not leave
		// it active-but-unusable, so revoke it rather than orphan it.
		if !r.store.RevokeTenant(tenantID) {
			log.Printf("relay: failed to revoke orphaned tenant %q after invalid CSR", tenantID)
		}
		writeJSONErr(w, http.StatusBadRequest, "invalid_csr")
		return
	}
	if err := r.store.RecordAgentCert(tenantID, serial); err != nil {
		log.Printf("relay: record agent cert: %v", err)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(registerTenantResp{
		TenantID: tenantID,
		CertPEM:  string(certPEM),
	})
	log.Printf("relay: registered new tenant %q", tenantID)
}

type devicePairReq struct {
	Code         string `json:"code"`
	DevicePubkey string `json:"device_pubkey"`
	Name         string `json:"name"`
}

type devicePairResp struct {
	Token    string `json:"token"`
	TenantID string `json:"tenant_id"`
}

// handleDevicePair is the public, no-auth endpoint a phone hits directly
// after scanning the agent's pairing QR code. Reachable without a client
// cert (see deploy/nginx-cmux-relay.conf's ssl_verify_client optional
// change below) — a brand-new phone has no cert to present yet, mirroring
// handleRegisterTenant's bootstrap story for agents. The response omits the
// agent's e2e public key (the phone already has it from the QR code payload
// itself, cmd/cmux-bridge/pair.go — the relay never needs to hold or
// forward e2e key material) but keeps tenant_id, informationally, so the
// app knows which workspace it just joined.
func (r *Relay) handleDevicePair(w http.ResponseWriter, req *http.Request) {
	req.Body = http.MaxBytesReader(w, req.Body, 4<<10)
	var rq devicePairReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.Code == "" || rq.DevicePubkey == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing code or device_pubkey")
		return
	}
	name := rq.Name
	if name == "" {
		name = "phone"
	}
	tok, tenantID, ok := r.store.RedeemPairingCode(rq.Code, name, rq.DevicePubkey)
	if !ok {
		// RedeemPairingCode's bool return doesn't distinguish not-found,
		// expired, and already-redeemed -- per the spec's error-handling
		// section, all three map to the same response.
		writeJSONErr(w, http.StatusGone, "pairing_code_invalid")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(devicePairResp{Token: tok, TenantID: tenantID})
	log.Printf("relay: device paired via QR code")
}
