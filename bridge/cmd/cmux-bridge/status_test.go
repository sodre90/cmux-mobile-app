package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/status"
)

func TestPrintStatusRelayUpDirectDisabled(t *testing.T) {
	var buf bytes.Buffer
	printStatus(&buf, status.Snapshot{
		WrittenAt:         time.Now(),
		RelayTunnelUp:     true,
		DirectModeEnabled: false,
		LastCmuxReachedAt: time.Now(),
		LastEventAt:       time.Time{},
	})
	out := buf.String()
	if !strings.Contains(out, "relay tunnel:    up") {
		t.Fatalf("output missing relay tunnel up: %s", out)
	}
	if !strings.Contains(out, "direct listener: disabled") {
		t.Fatalf("output missing disabled direct listener: %s", out)
	}
	if !strings.Contains(out, "last event:      never") {
		t.Fatalf("output missing never for last event: %s", out)
	}
}

func TestPrintStatusDirectEnabledDown(t *testing.T) {
	var buf bytes.Buffer
	printStatus(&buf, status.Snapshot{
		WrittenAt:         time.Now(),
		RelayTunnelUp:     false,
		DirectModeEnabled: true,
		DirectListenerUp:  false,
	})
	out := buf.String()
	if !strings.Contains(out, "relay tunnel:    down") {
		t.Fatalf("output missing relay tunnel down: %s", out)
	}
	if !strings.Contains(out, "direct listener: down") {
		t.Fatalf("output missing direct listener down: %s", out)
	}
}

// cmux-app-0no. Each of these three states used to print the same word,
// "up", including the two where the direct half of dual-pairing was in fact
// dead.
func TestPrintStatusDistinguishesBoundFromWorking(t *testing.T) {
	cases := []struct {
		name string
		snap status.Snapshot
		want string
	}{
		{
			name: "nothing ever reaches it",
			snap: status.Snapshot{DirectModeEnabled: true, DirectListenerUp: true},
			want: "nothing has ever connected",
		},
		{
			name: "connections arrive but never complete",
			snap: status.Snapshot{DirectModeEnabled: true, DirectListenerUp: true, DirectConnectionsAccepted: 7},
			want: "none of 7 connections",
		},
		{
			name: "actually serving",
			snap: status.Snapshot{
				DirectModeEnabled:         true,
				DirectListenerUp:          true,
				DirectConnectionsAccepted: 7,
				DirectLastServedAt:        time.Now(),
			},
			want: "up, last served",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var buf bytes.Buffer
			tc.snap.WrittenAt = time.Now()
			printStatus(&buf, tc.snap)
			if !strings.Contains(buf.String(), tc.want) {
				t.Fatalf("output missing %q: %s", tc.want, buf.String())
			}
		})
	}
}

func TestRunStatusReadsWrittenSnapshot(t *testing.T) {
	dir := t.TempDir()
	statusPath := filepath.Join(dir, "status.json")
	cfgPath := filepath.Join(dir, "agent.toml")
	if err := os.WriteFile(cfgPath, []byte(`status_file = "`+statusPath+`"`+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := status.Write(statusPath, status.Snapshot{
		WrittenAt:     time.Now(),
		RelayTunnelUp: true,
	}); err != nil {
		t.Fatal(err)
	}

	if got := runStatus([]string{"-config", cfgPath}); got != 0 {
		t.Fatalf("runStatus exit code = %d, want 0", got)
	}
}

func TestRunStatusMissingFileFails(t *testing.T) {
	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "agent.toml")
	if err := os.WriteFile(cfgPath, []byte(`status_file = "`+filepath.Join(dir, "never-written.json")+`"`+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := runStatus([]string{"-config", cfgPath}); got != 1 {
		t.Fatalf("runStatus exit code = %d, want 1 for a status file that was never written", got)
	}
}
