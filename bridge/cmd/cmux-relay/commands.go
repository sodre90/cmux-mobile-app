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
	tenant := fs.String("tenant", "", "tenant id this device belongs to (see: cmux-relay tenants list)")
	name := fs.String("name", "phone", "a label for this device")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *tenant == "" {
		fmt.Fprintln(os.Stderr, "pair: -tenant is required (see: cmux-relay tenants list)")
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	if !store.TenantActive(*tenant) {
		fmt.Fprintf(os.Stderr, "pair: no active tenant %q\n", *tenant)
		return 1
	}
	tok, err := store.Issue(*tenant, *name)
	if err != nil {
		log.Printf("pair: %v", err)
		return 1
	}
	fmt.Printf("\nDevice token for %q (tenant %s, paste into the app once):\n\n    %s\n\n", *name, *tenant, tok)
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
			fmt.Printf("%-16s  tenant=%s  token=...%s  fcm=%v  created=%s\n",
				d.Name, d.TenantID, d.HashSuffix, d.FCM != "", d.Created.Format(time.RFC3339))
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

func runTenants(args []string) int {
	fs := flag.NewFlagSet("tenants", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("tenants: %v", err)
		return 1
	}
	rest := fs.Args()
	switch {
	case len(rest) == 0 || rest[0] == "list":
		tenants, err := store.ListTenants()
		if err != nil {
			log.Printf("tenants: %v", err)
			return 1
		}
		if len(tenants) == 0 {
			fmt.Println("no tenants")
			return 0
		}
		for _, t := range tenants {
			fmt.Printf("%s  created=%s  revoked=%v\n", t.ID, t.CreatedAt.Format(time.RFC3339), t.Revoked)
		}
		return 0
	case rest[0] == "revoke":
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "usage: cmux-relay tenants revoke <id>")
			return 2
		}
		if store.RevokeTenant(rest[1]) {
			fmt.Println("revoked (this also stops all of that tenant's devices from verifying)")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such tenant, or already revoked")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-relay tenants [list|revoke <id>]")
		return 2
	}
}
