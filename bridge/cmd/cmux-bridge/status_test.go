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
