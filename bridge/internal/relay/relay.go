package relay

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log"
	"net/http"
	"net/http/httputil"
	"strings"

	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)

// Relay is the home-server rendezvous: it accepts the Mac agent's tunnel and
// reverse-proxies authenticated app requests over it.
type Relay struct {
	store      *auth.Store
	reg        *Registry
	agentCN    string
	relayToken string
	edgeToken  string
	proxy      *httputil.ReverseProxy
	onSession  func(context.Context, *yamux.Session)
}

// New builds a Relay. store may be nil only in tests that never hit auth routes.
func New(store *auth.Store, agentCN, relayToken string) *Relay {
	reg := NewRegistry()
	return &Relay{
		store:      store,
		reg:        reg,
		agentCN:    agentCN,
		relayToken: relayToken,
		proxy:      newProxy(reg, relayToken),
	}
}

// SetSessionHook registers a callback invoked (in its own goroutine) for each
// accepted agent session; its context is cancelled when the session ends.
func (r *Relay) SetSessionHook(f func(context.Context, *yamux.Session)) { r.onSession = f }

// SetEdgeToken sets a shared secret the trusted edge (nginx) must present in
// X-Edge-Token on every request except /healthz. Empty disables the check (used
// when nginx runs on the same host and the relay is loopback-only).
func (r *Relay) SetEdgeToken(t string) { r.edgeToken = t }

// Current exposes the active agent session (nil if offline) — used in tests.
func (r *Relay) Current() *yamux.Session { return r.reg.Current() }

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

func (r *Relay) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("/agent/tunnel", r.handleTunnel)
	mux.Handle("POST /devices/register", r.notAgent(auth.Require(r.store, http.HandlerFunc(r.handleRegister))))
	mux.Handle("/", r.notAgent(auth.Require(r.store, r.logProxy(r.proxy))))
	if r.edgeToken == "" {
		return mux
	}
	return r.requireEdge(mux)
}

// requireEdge gates every route except /healthz on a shared secret the trusted
// edge (nginx) injects. With the relay's port reachable on the LAN, this ensures
// only the edge — not a direct LAN client spoofing X-Client-Cert-CN — can drive
// it. Constant-time compare so the secret can't be probed by timing.
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

// notAgent rejects requests bearing the agent CN on non-tunnel routes.
func (r *Relay) notAgent(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if r.agentCN != "" && r.clientCN(req) == r.agentCN {
			writeJSONErr(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, req)
	})
}

func (r *Relay) handleTunnel(w http.ResponseWriter, req *http.Request) {
	if r.agentCN == "" || r.clientCN(req) != r.agentCN {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	sess, err := tunnel.Accept(w, req)
	if err != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	r.reg.Set(sess, cancel)
	log.Printf("relay: agent tunnel up (cn=%q)", r.clientCN(req))
	if r.onSession != nil {
		go r.onSession(ctx, sess)
	}
	<-sess.CloseChan() // block until the tunnel dies
	log.Printf("relay: agent tunnel down (cn=%q)", r.clientCN(req))
	r.reg.Clear(sess)
	cancel()
}

type registerReq struct {
	FCMToken string `json:"fcm_token"`
}

func (r *Relay) handleRegister(w http.ResponseWriter, req *http.Request) {
	_, ok := auth.DeviceFromContext(req.Context())
	if !ok {
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
