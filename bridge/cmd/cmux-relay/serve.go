package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/ca"
	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/relay"
)

func defaultConfigPath() string {
	return cli.ConfigPath("cmux-relay", "config.toml")
}

// isLoopbackAddr reports whether addr (a "host:port" listen address, as
// cfg.Listen always is) resolves to a loopback-only interface. It fails
// closed: any host it can't positively confirm as loopback (including an
// empty host meaning "all interfaces", or a hostname that doesn't parse as
// an IP) is treated as NOT loopback.
func isLoopbackAddr(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		host = addr
	}
	if host == "" {
		return false // ":8765" binds all interfaces — not loopback.
	}
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false // unresolvable/non-IP host — fail closed.
	}
	return ip.IsLoopback()
}

func runServe(args []string) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		slog.Error("serve: load store", "err", err)
		return 1
	}
	if !isLoopbackAddr(cfg.Listen) && cfg.EdgeToken == "" {
		slog.Error("serve: refusing to start: listen address is not loopback-only and edge_token is unset — nginx is trusted to set X-Client-Cert-CN, so binding non-loopback without edge_token lets anyone who can reach this address forge that header and hijack any tenant's tunnel; set edge_token in config.toml or bind to loopback", "listen", cfg.Listen)
		return 1
	}
	signer, err := ca.LoadOrCreate(cfg.CACert, cfg.CAKey)
	if err != nil {
		slog.Error("serve: ca", "err", err)
		return 1
	}

	rl := relay.New(store, signer, cfg.RelayToken)
	rl.SetEdgeToken(cfg.EdgeToken)

	var pusher relay.Pusher
	if cfg.FCMCredentials != "" && cfg.FCMProjectID != "" {
		if p, err := push.FromServiceAccount(context.Background(), cfg.FCMProjectID, cfg.FCMCredentials); err != nil {
			slog.Warn("serve: push disabled", "err", err)
		} else {
			pusher = p
			slog.Info("serve: FCM push enabled", "fcm_project_id", cfg.FCMProjectID)
		}
	}
	if pusher != nil {
		rl.SetSessionHook(func(ctx context.Context, tenantID string, sess *yamux.Session) {
			relay.MonitorAgent(ctx, tenantID, sess, cfg.RelayToken, store, pusher)
		})
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// No SIGHUP/reload handling needed: the store reads live from SQLite on
	// every request, so a separate `cmux-relay devices`/`tenants` process's
	// writes are visible immediately without a restart or reload signal.

	httpSrv := &http.Server{Addr: cfg.Listen, Handler: rl.Handler()}
	go func() {
		<-ctx.Done()
		shutCtx, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = httpSrv.Shutdown(shutCtx)
	}()

	slog.Info("cmux-relay listening", "listen", cfg.Listen)
	if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("serve: listen and serve", "err", err)
		return 1
	}
	return 0
}
