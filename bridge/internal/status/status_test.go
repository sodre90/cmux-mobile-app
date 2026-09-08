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

func TestCountersRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status.json")
	want := map[string]int64{"push_sent_total": 12, "e2e_decrypt_failures_total/body": 3}
	if err := Write(path, Snapshot{WrittenAt: time.Now(), Counters: want}); err != nil {
		t.Fatal(err)
	}
	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Counters) != len(want) {
		t.Fatalf("counters round trip: got %v want %v", got.Counters, want)
	}
	for name, n := range want {
		if got.Counters[name] != n {
			t.Errorf("counters[%q] = %d, want %d", name, got.Counters[name], n)
		}
	}
}

// An agent from before this field writes a snapshot with no counters at all,
// which must read back as absent rather than fail the whole parse.
func TestASnapshotWithNoCountersReadsBackEmpty(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status.json")
	if err := Write(path, Snapshot{WrittenAt: time.Now()}); err != nil {
		t.Fatal(err)
	}
	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Counters) != 0 {
		t.Fatalf("want no counters, got %v", got.Counters)
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

// -- per-slot reachability (cmux-app-t5x)

// The bug this exists for: the direct standby was unreachable for 14 days and
// the only health line reset on every agent restart, so nothing could show it.
func TestAnOutageSurvivesARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status.json")
	lastGood := time.Now().Add(-14 * 24 * time.Hour).UTC().Round(time.Second)

	before := NewSlotReachability(nil)
	before.Record(map[string]bool{"relay": true, "direct": true}, lastGood)
	if err := Write(path, Snapshot{WrittenAt: lastGood, SlotLastReachedAt: before.Snapshot()}); err != nil {
		t.Fatal(err)
	}

	// A new process: seed from what the old one left on disk, then run rounds
	// where relay answers and direct does not.
	carried, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	after := NewSlotReachability(carried.SlotLastReachedAt)
	now := time.Now().UTC().Round(time.Second)
	after.Record(map[string]bool{"relay": true, "direct": false}, now)

	got := after.Snapshot()
	if !got["direct"].Equal(lastGood) {
		t.Fatalf("direct last-reached = %v, want the pre-restart %v", got["direct"], lastGood)
	}
	if !got["relay"].Equal(now) {
		t.Fatalf("relay last-reached = %v, want %v", got["relay"], now)
	}
}

// A slot that does not answer keeps its old timestamp: the gap between that and
// now IS the outage length, so clearing or advancing it destroys the answer.
func TestAFailedRoundDoesNotMoveTheTimestamp(t *testing.T) {
	good := time.Now().Add(-2 * time.Hour)
	r := NewSlotReachability(map[string]time.Time{"direct": good})

	for range 10 {
		r.Record(map[string]bool{"direct": false}, time.Now())
	}

	if got := r.Snapshot()["direct"]; !got.Equal(good) {
		t.Fatalf("last-reached = %v, want it pinned at %v", got, good)
	}
}

// A configured slot that has never once answered must be distinguishable from
// a slot that was never configured -- "never" and "absent" are different
// answers, and only one of them is alarming.
func TestASlotThatHasNeverAnsweredIsRecordedAsNever(t *testing.T) {
	r := NewSlotReachability(nil)

	r.Record(map[string]bool{"direct": false}, time.Now())

	got := r.Snapshot()
	at, present := got["direct"]
	if !present {
		t.Fatal("a configured slot that failed must still appear")
	}
	if !at.IsZero() {
		t.Fatalf("want the zero time for never-reached, got %v", at)
	}
	if _, ok := got["relay"]; ok {
		t.Fatal("a slot that was never probed must not appear at all")
	}
}

func TestSlotReachabilityRoundTripsThroughTheFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status.json")
	at := time.Now().UTC().Round(time.Second)
	if err := Write(path, Snapshot{
		WrittenAt:         at,
		SlotLastReachedAt: map[string]time.Time{"relay": at, "direct": {}},
	}); err != nil {
		t.Fatal(err)
	}

	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if !got.SlotLastReachedAt["relay"].Equal(at) {
		t.Fatalf("relay = %v, want %v", got.SlotLastReachedAt["relay"], at)
	}
	if !got.SlotLastReachedAt["direct"].IsZero() {
		t.Fatalf("direct = %v, want the zero time", got.SlotLastReachedAt["direct"])
	}
}

// The reaper goroutine records while the status writer reads.
func TestSlotReachabilityIsSafeUnderConcurrentUse(t *testing.T) {
	r := NewSlotReachability(nil)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := range 500 {
			r.Record(map[string]bool{"relay": i%2 == 0, "direct": true}, time.Now())
		}
	}()
	for range 500 {
		_ = r.Snapshot()
	}
	<-done
}

// Seeding must copy, not alias: the map handed in comes straight off a decoded
// Snapshot the caller may still be holding.
func TestSeedingCopiesTheMap(t *testing.T) {
	seed := map[string]time.Time{"direct": {}}
	r := NewSlotReachability(seed)

	r.Record(map[string]bool{"direct": true}, time.Now())

	if !seed["direct"].IsZero() {
		t.Fatal("Record wrote through to the caller's seed map")
	}
}
