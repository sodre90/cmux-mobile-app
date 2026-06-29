package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/sodre90/cmux-bridge/internal/cli"
)

func runPair(args []string) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	name := fs.String("name", "phone", "a label for this device")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
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
	fmt.Println("Keep it secret. Revoke later with: cmux-relay devices revoke <token>")
	return 0
}

func runDevices(args []string) int {
	fs := flag.NewFlagSet("devices", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
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
			fmt.Fprintln(os.Stderr, "usage: cmux-relay devices revoke <token>")
			return 2
		}
		if store.Revoke(rest[1]) {
			fmt.Println("revoked")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such token")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-relay devices [list|revoke <token>]")
		return 2
	}
}
