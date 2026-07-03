package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
	"github.com/sodre90/cmux-bridge/internal/yolo"
	"tailscale.com/client/tailscale"
	"tailscale.com/ipn/ipnstate"
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

func nextBackoff(d time.Duration) time.Duration {
	d *= 2
	if d > 30*time.Second {
		return 30 * time.Second
	}
	return d
}

// dialAndServe runs one tunnel lifecycle: dial the relay, then serve the handler
// over the yamux session until it dies. Returns when the session ends.
func dialAndServe(ctx context.Context, relayURL string, tlsCfg *tls.Config, handler http.Handler) error {
	sess, err := tunnel.Dial(ctx, relayURL, tlsCfg, nil)
	if err != nil {
		return err
	}
	defer sess.Close()
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
func selfTailscaleIPv4(st *ipnstate.Status) (netip.Addr, error) {
	if st == nil || st.Self == nil {
		return netip.Addr{}, errors.New("no Self status -- is Tailscale up?")
	}
	for _, ip := range st.Self.TailscaleIPs {
		if ip.Is4() {
			return ip, nil
		}
	}
	return netip.Addr{}, errors.New("no Tailscale IPv4 address assigned yet -- is Tailscale up?")
}

// serveDirect runs the direct (Tailscale) listener until ctx is canceled or
// the listener fails. It never affects the relay dial loop running
// alongside it in runAgent. store/tenantID back both the pairing routes and
// (via handler, already bound to the same store through Server.store)
// authenticated requests -- one auth.Store, opened once, for the whole
// listener.
//
// It binds ONLY to this Mac's own Tailscale IPv4 address, never to all
// interfaces: the design's whole premise is that Tailscale's own network
// ACLs are the access-control boundary for these routes (some of which,
// like the pairing endpoints, are otherwise unauthenticated), so listening
// on 0.0.0.0/[::] here would let any LAN-adjacent device reach them too.
func serveDirect(ctx context.Context, listenAddr string, store *auth.Store, tenantID string, handler http.Handler) error {
	mux := http.NewServeMux()
	server.MountDirectPairing(mux, store, tenantID)
	mux.Handle("/", handler)

	port, err := directListenPort(listenAddr)
	if err != nil {
		return fmt.Errorf("direct mode: %w", err)
	}
	lc := &tailscale.LocalClient{}
	st, err := lc.Status(ctx)
	if err != nil {
		return fmt.Errorf("direct mode: tailscale status: %w", err)
	}
	ip, err := selfTailscaleIPv4(st)
	if err != nil {
		return fmt.Errorf("direct mode: %w", err)
	}
	bindAddr := net.JoinHostPort(ip.String(), port)

	tcpLn, err := net.Listen("tcp", bindAddr)
	if err != nil {
		return fmt.Errorf("direct mode: listen %s: %w", bindAddr, err)
	}
	tlsLn := tls.NewListener(tcpLn, &tls.Config{GetCertificate: lc.GetCertificate})

	log.Printf("agent: direct listener up on %s (tailscale-only)", bindAddr)
	errCh := make(chan error, 1)
	go func() { errCh <- http.Serve(tlsLn, mux) }()
	select {
	case <-ctx.Done():
		tlsLn.Close()
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
		log.Printf("agent: %v", err)
		return 1
	}
	if err := ensureRegistered(cfg); err != nil {
		log.Printf("agent: %v", err)
		return 1
	}
	if cfg.RelayURL == "" {
		log.Printf("agent: relay_url is required")
		return 1
	}
	tlsCfg, err := loadTLS(cfg.ClientCert, cfg.ClientKey, cfg.CACert)
	if err != nil {
		log.Printf("agent: tls: %v", err)
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
			log.Printf("agent: direct mode: open auth store: %v", err)
			return 1
		}
		directTenantID, err = ensureDirectTenant(directStore)
		if err != nil {
			log.Printf("agent: direct mode: ensure tenant: %v", err)
			return 1
		}
	}
	srv := server.New(config.Config{}, &cmux.Client{Bin: cfg.CmuxBin}, directStore)
	srv.SetSessions(e2e.OpenStore(cfg.SessionStore))
	srv.SetYoloStore(yolo.OpenStore(cfg.YoloStore))
	go srv.RunEvents(ctx)
	handler := srv.TrustedHandler(cfg.RelayToken)

	if cfg.DirectListen != "" {
		go func() {
			if err := serveDirect(ctx, cfg.DirectListen, directStore, directTenantID, srv.DirectHandler()); err != nil && ctx.Err() == nil {
				log.Printf("agent: direct listener ended: %v", err)
			}
		}()
	}

	backoff := time.Second
	for ctx.Err() == nil {
		log.Printf("agent: dialing relay %s", cfg.RelayURL)
		if err := dialAndServe(ctx, cfg.RelayURL, tlsCfg, handler); err != nil {
			log.Printf("agent: tunnel ended: %v", err)
		}
		if ctx.Err() != nil {
			break
		}
		time.Sleep(backoff)
		backoff = nextBackoff(backoff)
	}
	return 0
}
