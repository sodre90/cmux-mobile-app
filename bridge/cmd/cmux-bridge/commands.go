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

	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/server"
)

func defaultConfigPath() string {
	return cli.ConfigPath("cmux-bridge", "config.toml")
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
	srv := server.New(cfg, &cmux.Client{Bin: cfg.CmuxBin}, store)

	if cfg.FCMCredentials != "" && cfg.FCMProjectID != "" {
		p, err := push.FromServiceAccount(context.Background(), cfg.FCMProjectID, cfg.FCMCredentials)
		if err != nil {
			log.Printf("serve: push disabled: %v", err)
		} else {
			srv.SetPusher(p)
			log.Printf("serve: FCM push enabled for project %s", cfg.FCMProjectID)
		}
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go srv.RunEvents(ctx)

	httpSrv := &http.Server{Addr: cfg.Listen, Handler: srv.Handler()}
	go func() {
		<-ctx.Done()
		shutCtx, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = httpSrv.Shutdown(shutCtx)
	}()

	log.Printf("cmux-bridge listening on %s (cmux bin %q)", cfg.Listen, cfg.CmuxBin)
	if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Printf("serve: %v", err)
		return 1
	}
	return 0
}
