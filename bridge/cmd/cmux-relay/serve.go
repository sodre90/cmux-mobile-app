package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/hashicorp/yamux"

	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/relay"
)

func defaultConfigPath() string {
	return cli.ConfigPath("cmux-relay", "config.toml")
}

func runServe(args []string) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("serve: %v", err)
		return 1
	}

	rl := relay.New(store, cfg.AgentCN, cfg.RelayToken)

	var pusher relay.Pusher
	if cfg.FCMCredentials != "" && cfg.FCMProjectID != "" {
		if p, err := push.FromServiceAccount(context.Background(), cfg.FCMProjectID, cfg.FCMCredentials); err != nil {
			log.Printf("serve: push disabled: %v", err)
		} else {
			pusher = p
			log.Printf("serve: FCM push enabled for project %s", cfg.FCMProjectID)
		}
	}
	if pusher != nil {
		rl.SetSessionHook(func(ctx context.Context, sess *yamux.Session) {
			relay.MonitorAgent(ctx, sess, cfg.RelayToken, store, pusher)
		})
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	httpSrv := &http.Server{Addr: cfg.Listen, Handler: rl.Handler()}
	go func() {
		<-ctx.Done()
		shutCtx, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = httpSrv.Shutdown(shutCtx)
	}()

	log.Printf("cmux-relay listening on %s (agent CN %q)", cfg.Listen, cfg.AgentCN)
	if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Printf("serve: %v", err)
		return 1
	}
	return 0
}
