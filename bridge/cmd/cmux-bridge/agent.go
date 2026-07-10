package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/backoff"
	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
	"github.com/sodre90/cmux-bridge/internal/yolo"
)

// certRefreshInterval is how often serveDirect re-checks the direct-mode
// TLS certificate's remaining validity via `tailscale cert`.
// certMinValidity is the minimum remaining validity requested each time --
// well short of Let's Encrypt's 90-day lifetime, so repeated calls within
// that window are cheap no-ops rather than forcing a fresh issuance.
const (
	certRefreshInterval = 24 * time.Hour
	certMinValidity     = 30 * 24 * time.Hour
)

func defaultAgentConfigPath() string {
	return cli.ConfigPath("cmux-bridge", "agent.toml")
}

func loadTLS(certPath, keyPath, caPath string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, err
	}
	cfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}
	// An empty ca_cert means the relay's nginx presents a publicly-trusted
	// server cert (e.g. Let's Encrypt): leave RootCAs nil so Go uses the system
	// roots. A non-empty ca_cert pins a private CA instead.
	if caPath != "" {
		caPEM, err := os.ReadFile(caPath)
		if err != nil {
			return nil, err
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(caPEM) {
			return nil, errors.New("ca_cert: no certificates found")
		}
		cfg.RootCAs = pool
	}
	return cfg, nil
}

// dialAndServe runs one tunnel lifecycle: dial the relay, then serve the handler
// over the yamux session until it dies. Returns when the session ends.
func dialAndServe(ctx context.Context, relayURL string, tlsCfg *tls.Config, handler http.Handler) error {
	sess, err := tunnel.Dial(ctx, relayURL, tlsCfg, nil)
	if err != nil {
		return err
	}
	defer func() { _ = sess.Close() }()
	return http.Serve(sess, handler)
}

// ensureDirectTenant returns direct mode's single implicit tenant id,
// creating it once on first use. Idempotent across restarts: an existing
// tenant is always reused, so toggling direct_listen off and back on never
// orphans devices paired while it was on.
func ensureDirectTenant(store *auth.Store) (string, error) {
	tenants, err := store.ListTenants()
	if err != nil {
		return "", err
	}
	for _, t := range tenants {
		if !t.Revoked {
			return t.ID, nil
		}
	}
	return store.CreateTenant()
}

// directListenPort extracts the port from cfg.DirectListen, which is always
// documented and configured in ":PORT" form (e.g. ":8443" -- see
// bridge/README.md and bridge/deploy/agent.example.toml, and
// internal/config/agent_test.go's TestLoadAgentParsesDirectFields). A bare
// leading-colon address has no host part, so net.SplitHostPort happily
// returns an empty host and just the port.
func directListenPort(listenAddr string) (string, error) {
	_, port, err := net.SplitHostPort(listenAddr)
	if err != nil {
		return "", fmt.Errorf("direct_listen %q: %w", listenAddr, err)
	}
	return port, nil
}

// selfTailscaleIPv4 returns this node's own Tailscale IPv4 address from a
// tailscale status snapshot. A node can have both an IPv4 and IPv6 tailnet
// address; we bind to the IPv4 one, matching `tailscale ip -4` (the
// convention bridge/README.md's setup steps already use). Returns an error
// if Tailscale isn't up yet or hasn't assigned this node an IPv4 address --
// callers must fail closed on that, not fall back to binding all
// interfaces, since Tailscale's own network ACLs are the actual
// access-control boundary for the direct-mode listener.
func selfTailscaleIPv4(st *tailscaleSelf) (netip.Addr, error) {
	if st == nil {
		return netip.Addr{}, errors.New("no Self status -- is Tailscale up?")
	}
	for _, ip := range st.TailscaleIPs {
		if ip.Is4() {
			return ip, nil
		}
	}
	return netip.Addr{}, errors.New("no Tailscale IPv4 address assigned yet -- is Tailscale up?")
}

// refreshDirectCert re-runs tailscaleCert on a ticker for as long as ctx is
// live, atomically swapping certVal to the reloaded certificate on success.
// A failed refresh (tailscaled down, transient ACME error) logs and keeps
// serving the previous certificate rather than tearing down the listener.
func refreshDirectCert(ctx context.Context, domain, certFile, keyFile string, certVal *atomic.Pointer[tls.Certificate]) {
	ticker := time.NewTicker(certRefreshInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		if err := tailscaleCert(ctx, domain, certFile, keyFile, certMinValidity); err != nil {
			slog.Warn("agent: direct mode: cert refresh failed, keeping current cert", "err", err)
			continue
		}
		cert, err := tls.LoadX509KeyPair(certFile, keyFile)
		if err != nil {
			slog.Warn("agent: direct mode: cert refresh: reload failed, keeping current cert", "err", err)
			continue
		}
		certVal.Store(&cert)
	}
}

// serveDirect runs the direct (Tailscale) listener until ctx is canceled or
// the listener fails. It never affects the relay dial loop running
// alongside it in runAgent. store/tenantID back both the pairing routes and
// (via handler, already bound to the same store through Server.store)
// authenticated requests -- one auth.Store, opened once, for the whole
// listener. certDir holds the direct-mode TLS certificate/key files
// (refreshed periodically via tailscaleCert); it's created if missing.
//
// It binds ONLY to this Mac's own Tailscale IPv4 address, never to all
// interfaces: the design's whole premise is that Tailscale's own network
// ACLs are the access-control boundary for these routes (some of which,
// like the pairing endpoints, are otherwise unauthenticated), so listening
// on 0.0.0.0/[::] here would let any LAN-adjacent device reach them too.
func serveDirect(ctx context.Context, listenAddr, certDir string, store *auth.Store, tenantID string, handler http.Handler) error {
	mux := http.NewServeMux()
	server.MountDirectPairing(mux, store, tenantID)
	mux.Handle("/", handler)

	port, err := directListenPort(listenAddr)
	if err != nil {
		return fmt.Errorf("direct mode: %w", err)
	}
	st, err := tailscaleSelfStatus(ctx)
	if err != nil {
		return fmt.Errorf("direct mode: %w", err)
	}
	ip, err := selfTailscaleIPv4(st)
	if err != nil {
		return fmt.Errorf("direct mode: %w", err)
	}
	if st.DNSName == "" {
		return errors.New("direct mode: this Mac has no Tailscale DNS name yet -- is Tailscale up?")
	}
	domain := strings.TrimSuffix(st.DNSName, ".")
	bindAddr := net.JoinHostPort(ip.String(), port)

	if err := os.MkdirAll(certDir, 0o700); err != nil {
		return fmt.Errorf("direct mode: cert dir: %w", err)
	}
	certFile := filepath.Join(certDir, "direct-cert.pem")
	keyFile := filepath.Join(certDir, "direct-key.pem")
	if err := tailscaleCert(ctx, domain, certFile, keyFile, certMinValidity); err != nil {
		return fmt.Errorf("direct mode: %w", err)
	}
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return fmt.Errorf("direct mode: load cert: %w", err)
	}
	var certVal atomic.Pointer[tls.Certificate]
	certVal.Store(&cert)
	refreshCtx, cancelRefresh := context.WithCancel(ctx)
	defer cancelRefresh()
	go refreshDirectCert(refreshCtx, domain, certFile, keyFile, &certVal)

	tcpLn, err := net.Listen("tcp", bindAddr)
	if err != nil {
		return fmt.Errorf("direct mode: listen %s: %w", bindAddr, err)
	}
	tlsLn := tls.NewListener(tcpLn, &tls.Config{
		GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
			return certVal.Load(), nil
		},
		MinVersion: tls.VersionTLS12,
		NextProtos: []string{"h2", "http/1.1"},
	})

	slog.Info("agent: direct listener up (tailscale-only)", "addr", bindAddr)
	errCh := make(chan error, 1)
	go func() { errCh <- http.Serve(tlsLn, mux) }()
	select {
	case <-ctx.Done():
		_ = tlsLn.Close()
		return ctx.Err()
	case err := <-errCh:
		return err
	}
}

func runAgent(args []string) int {
	fs := flag.NewFlagSet("agent", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultAgentConfigPath(), "path to agent.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, err := config.LoadAgent(*cfgPath)
	if err != nil {
		slog.Error("agent: load config", "err", err)
		return 1
	}
	if err := ensureRegistered(cfg); err != nil {
		slog.Error("agent: register", "err", err)
		return 1
	}
	if cfg.RelayURL == "" {
		slog.Error("agent: relay_url is required")
		return 1
	}
	tlsCfg, err := loadTLS(cfg.ClientCert, cfg.ClientKey, cfg.CACert)
	if err != nil {
		slog.Error("agent: tls", "err", err)
		return 1
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	var directStore *auth.Store // nil unless direct mode is on; Handler()/authWrap stay unused in production either way
	var directTenantID string
	if cfg.DirectListen != "" {
		var err error
		directStore, err = auth.Open(cfg.DirectAuthStore)
		if err != nil {
			slog.Error("agent: direct mode: open auth store", "err", err)
			return 1
		}
		directTenantID, err = ensureDirectTenant(directStore)
		if err != nil {
			slog.Error("agent: direct mode: ensure tenant", "err", err)
			return 1
		}
	}
	srv := server.New(&cmux.Client{Bin: cfg.CmuxBin, FastPath: true}, directStore)
	sessions, err := e2e.OpenStore(cfg.SessionStore)
	if err != nil {
		slog.Error("agent: open session store", "err", err)
		return 1
	}
	srv.SetSessions(sessions)

	yoloStore, err := yolo.Open(cfg.YoloStore)
	if err != nil {
		slog.Error("agent: open yolo store", "err", err)
		return 1
	}
	srv.SetYoloStore(yoloStore)
	if cfg.DirectListen != "" && cfg.FCMProjectID != "" && cfg.FCMCredentials != "" {
		if p, err := push.FromServiceAccount(context.Background(), cfg.FCMProjectID, cfg.FCMCredentials); err != nil {
			slog.Warn("agent: direct-mode push disabled", "err", err)
		} else {
			srv.SetPusher(p, directTenantID)
			slog.Info("agent: direct-mode FCM push enabled", "fcm_project_id", cfg.FCMProjectID)
		}
	}
	go srv.RunEvents(ctx)
	handler := srv.TrustedHandler(cfg.RelayToken)

	if cfg.DirectListen != "" {
		certDir := filepath.Join(filepath.Dir(cfg.DirectAuthStore), "direct-certs")
		go func() {
			if err := serveDirect(ctx, cfg.DirectListen, certDir, directStore, directTenantID, srv.DirectHandler()); err != nil && ctx.Err() == nil {
				slog.Error("agent: direct listener ended", "err", err)
			}
		}()
	}

	retry := backoff.New(time.Second, 30*time.Second)
	for ctx.Err() == nil {
		slog.Info("agent: dialing relay", "relay_url", cfg.RelayURL)
		if err := dialAndServe(ctx, cfg.RelayURL, tlsCfg, handler); err != nil {
			slog.Warn("agent: tunnel ended", "err", err)
		}
		if ctx.Err() != nil {
			break
		}
		backoff.Sleep(ctx, retry.Next())
	}
	return 0
}
