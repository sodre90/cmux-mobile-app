package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/hashicorp/yamux"
	"golang.org/x/oauth2/google"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/relay"
)

// fcmScope is the OAuth scope required to send FCM HTTP v1 messages.
const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

func defaultConfigPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "config.toml"
	}
	return filepath.Join(home, ".config", "cmux-relay", "config.toml")
}

func loadStore(cfgPath string) (config.Config, *auth.Store, error) {
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return cfg, nil, err
	}
	store, err := auth.Open(cfg.TokenStore)
	if err != nil {
		return cfg, nil, err
	}
	return cfg, store, nil
}

func runServe(args []string) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, store, err := loadStore(*cfgPath)
	if err != nil {
		log.Printf("serve: %v", err)
		return 1
	}

	rl := relay.New(store, cfg.AgentCN, cfg.RelayToken)

	var pusher relay.Pusher
	if cfg.FCMCredentials != "" && cfg.FCMProjectID != "" {
		if p, err := newPusher(cfg); err != nil {
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

func newPusher(cfg config.Config) (*push.Sender, error) {
	key, err := os.ReadFile(cfg.FCMCredentials)
	if err != nil {
		return nil, fmt.Errorf("read fcm credentials: %w", err)
	}
	creds, err := google.CredentialsFromJSON(context.Background(), key, fcmScope)
	if err != nil {
		return nil, fmt.Errorf("parse fcm credentials: %w", err)
	}
	return &push.Sender{
		ProjectID: cfg.FCMProjectID,
		Token: func(context.Context) (string, error) {
			tok, err := creds.TokenSource.Token()
			if err != nil {
				return "", err
			}
			return tok.AccessToken, nil
		},
	}, nil
}
