package status

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func TestWriteReadRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "status.json")
	want := Snapshot{
		WrittenAt:         time.Now().UTC().Round(time.Second),
		RelayTunnelUp:     true,
		DirectModeEnabled: true,
		DirectListenerUp:  false,
		LastCmuxReachedAt: time.Now().UTC().Round(time.Second),
		LastEventAt:       time.Now().UTC().Round(time.Second),
	}
	if err := Write(path, want); err != nil {
		t.Fatal(err)
	}
	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if !got.WrittenAt.Equal(want.WrittenAt) || got.RelayTunnelUp != want.RelayTunnelUp ||
		got.DirectModeEnabled != want.DirectModeEnabled || got.DirectListenerUp != want.DirectListenerUp ||
		!got.LastCmuxReachedAt.Equal(want.LastCmuxReachedAt) || !got.LastEventAt.Equal(want.LastEventAt) {
		t.Fatalf("round trip mismatch: got %+v want %+v", got, want)
	}
}

func TestReadMissingFileErrors(t *testing.T) {
	if _, err := Read(filepath.Join(t.TempDir(), "no-such-file.json")); err == nil {
		t.Fatal("want an error reading a status file that was never written")
	}
}

func TestZeroSnapshotRoundTripsAsZero(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status.json")
	if err := Write(path, Snapshot{}); err != nil {
		t.Fatal(err)
	}
	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if !got.LastCmuxReachedAt.IsZero() || !got.LastEventAt.IsZero() {
		t.Fatalf("want zero timestamps to round-trip as zero, got %+v", got)
	}
}

func TestRunWriterWritesImmediatelyAndOnTick(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status.json")
	ctx, cancel := context.WithCancel(context.Background())
	calls := 0
	snapshot := func() Snapshot {
		calls++
		return Snapshot{WrittenAt: time.Now(), RelayTunnelUp: calls > 1}
	}

	done := make(chan struct{})
	go func() {
		RunWriter(ctx, path, 20*time.Millisecond, snapshot)
		close(done)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if snap, err := Read(path); err == nil && snap.RelayTunnelUp {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if !got.RelayTunnelUp {
		t.Fatal("expected at least one ticked write to observe RelayTunnelUp=true")
	}

	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("RunWriter did not return after ctx cancellation")
	}
}
