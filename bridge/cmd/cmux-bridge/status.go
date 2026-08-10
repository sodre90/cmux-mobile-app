package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/status"
)

// runStatus implements `cmux-bridge status`: read the snapshot the running
// `cmux-bridge agent` process last wrote (internal/status) and print a
// human-readable summary. It never talks to the agent process directly --
// see internal/status's package doc for why a status file was chosen over a
// live query.
func runStatus(args []string) int {
	fs := flag.NewFlagSet("status", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultAgentConfigPath(), "path to agent.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, err := config.LoadAgent(*cfgPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "load agent config:", err)
		return 1
	}
	snap, err := status.Read(cfg.StatusFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "no status available at %s (%v) -- is `cmux-bridge agent` running?\n", cfg.StatusFile, err)
		return 1
	}
	printStatus(os.Stdout, snap)
	return 0
}

func printStatus(w io.Writer, snap status.Snapshot) {
	_, _ = fmt.Fprintf(w, "as of:           %s\n", formatAgo(snap.WrittenAt))
	_, _ = fmt.Fprintf(w, "relay tunnel:    %s\n", upDown(snap.RelayTunnelUp))
	if snap.DirectModeEnabled {
		_, _ = fmt.Fprintf(w, "direct listener: %s\n", describeDirectListener(snap))
	} else {
		_, _ = fmt.Fprintln(w, "direct listener: disabled")
	}
	_, _ = fmt.Fprintf(w, "cmux reached:    %s\n", formatTimeOrNever(snap.LastCmuxReachedAt))
	_, _ = fmt.Fprintf(w, "last event:      %s\n", formatTimeOrNever(snap.LastEventAt))
}

// describeDirectListener says what the listener is doing, not merely that it
// is bound. Both ways a bound listener can be useless -- nothing reaching it
// (a firewall, an ACL) and connections reaching it but never completing
// (TLS) -- used to print as a plain "up", which is how the direct half of
// dual-pairing stayed dead for days with the only operator-visible signal
// calling it healthy (cmux-app-0no).
func describeDirectListener(snap status.Snapshot) string {
	switch {
	case !snap.DirectListenerUp:
		return "down"
	case !snap.DirectLastServedAt.IsZero():
		return fmt.Sprintf("up, last served %s", formatAgo(snap.DirectLastServedAt))
	case snap.DirectConnectionsAccepted == 0:
		return "bound, but nothing has ever connected -- check the macOS firewall and Tailscale ACLs"
	default:
		return fmt.Sprintf("bound, but none of %d connections got as far as a request -- check TLS",
			snap.DirectConnectionsAccepted)
	}
}

func upDown(up bool) string {
	if up {
		return "up"
	}
	return "down"
}

func formatTimeOrNever(t time.Time) string {
	if t.IsZero() {
		return "never"
	}
	return formatAgo(t)
}

func formatAgo(t time.Time) string {
	return fmt.Sprintf("%s (%s ago)", t.Local().Format(time.RFC3339), time.Since(t).Round(time.Second))
}
