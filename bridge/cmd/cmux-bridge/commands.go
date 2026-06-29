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

	"golang.org/x/oauth2/google"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/push"
	"github.com/sodre90/cmux-bridge/internal/server"
)

// fcmScope is the OAuth scope required to send FCM HTTP v1 messages.
const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

func defaultConfigPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "config.toml"
	}
	return filepath.Join(home, ".config", "cmux-bridge", "config.toml")
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
	srv := server.New(cfg, &cmux.Client{Bin: cfg.CmuxBin}, store)

	if cfg.FCMCredentials != "" && cfg.FCMProjectID != "" {
		p, err := newPusher(cfg)
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
		Token: func(ctx context.Context) (string, error) {
			tok, err := creds.TokenSource.Token()
			if err != nil {
				return "", err
			}
			return tok.AccessToken, nil
		},
	}, nil
}

func runPair(args []string) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	name := fs.String("name", "phone", "a label for this device")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := loadStore(*cfgPath)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	tok, err := store.Issue(*name)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	fmt.Printf("\nDevice token for %q (paste into the app once):\n\n    %s\n\n", *name, tok)
	fmt.Println("Keep it secret. Revoke later with: cmux-bridge devices revoke <token>")
	return 0
}

func runDevices(args []string) int {
	fs := flag.NewFlagSet("devices", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := loadStore(*cfgPath)
	if err != nil {
		log.Printf("devices: %v", err)
		return 1
	}
	rest := fs.Args()
	switch {
	case len(rest) == 0 || rest[0] == "list":
		devs := store.List()
		if len(devs) == 0 {
			fmt.Println("no paired devices")
			return 0
		}
		for _, d := range devs {
			fmt.Printf("%-16s  token=%s  fcm=%v  created=%s\n",
				d.Name, d.Token, d.FCM != "", d.Created.Format(time.RFC3339))
		}
		return 0
	case rest[0] == "revoke":
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "usage: cmux-bridge devices revoke <token>")
			return 2
		}
		if store.Revoke(rest[1]) {
			fmt.Println("revoked")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such token")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-bridge devices [list|revoke <token>]")
		return 2
	}
}
