package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
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

	srv := server.New(config.Config{}, &cmux.Client{Bin: cfg.CmuxBin}, nil)
	go srv.RunEvents(ctx)
	handler := srv.TrustedHandler(cfg.RelayToken)

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
