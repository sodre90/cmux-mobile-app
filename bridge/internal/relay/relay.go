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

// Relay is the home-server rendezvous: it accepts Mac agents' tunnels and
// reverse-proxies authenticated app requests over them.
type Relay struct {
	store      *auth.Store
	reg        *Registry
	ca         *ca.CA
	relayToken string
	edgeToken  string
	proxy      *httputil.ReverseProxy
	onSession  func(context.Context, *yamux.Session)
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
func (r *Relay) SetSessionHook(f func(context.Context, *yamux.Session)) { r.onSession = f }

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

func (r *Relay) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("/agent/tunnel", r.handleTunnel)
	mux.Handle("POST /tenants/register", http.HandlerFunc(r.handleRegisterTenant))
	mux.Handle("POST /devices/register", r.notAgent(auth.Require(r.store, http.HandlerFunc(r.handleRegister))))
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

// notAgent rejects requests bearing an agent CN on non-tunnel routes.
func (r *Relay) notAgent(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if _, ok := tenantFromAgentCN(r.clientCN(req)); ok {
			writeJSONErr(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, req)
	})
}

func (r *Relay) handleTunnel(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := tenantFromAgentCN(r.clientCN(req))
	if !ok || !r.store.TenantActive(tenantID) {
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
		go r.onSession(ctx, sess)
	}
	<-sess.CloseChan() // block until the tunnel dies
	log.Printf("relay: agent tunnel down (tenant=%q)", tenantID)
	r.reg.Clear(tenantID, sess)
	cancel()
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
	CAPEM    string `json:"ca_pem"`
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
		CAPEM:    string(r.ca.CertPEM),
	})
	log.Printf("relay: registered new tenant %q", tenantID)
}
