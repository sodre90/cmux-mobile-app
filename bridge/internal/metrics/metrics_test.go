package metrics

import (
	"expvar"
	"testing"
)

// The whole point of Snapshot is that it needs no maintenance when a var is
// added, because a hand-kept second list is what went stale before
// (cmux-app-9aa). Assert every var this package declares is in the result
// rather than a fixed set of names.
func TestSnapshotCarriesEveryCounterThisPackageDeclares(t *testing.T) {
	declared := []string{
		"tunnels_active",
		"pairing_codes_issued_total",
		"pairing_codes_redeemed_total",
		"pairing_codes_expired_total",
		"push_sent_total",
		"push_failed_total",
		"push_tokens_dropped_total",
	}
	snap := Snapshot()
	for _, name := range declared {
		if _, ok := snap[name]; !ok {
			t.Errorf("Snapshot() is missing %q", name)
		}
	}
}

// A counter at zero must still be reported: "this has never happened" is a
// different answer from "this counter does not exist".
func TestACounterThatHasNeverMovedIsStillReported(t *testing.T) {
	name := "test_never_incremented_total"
	expvar.NewInt(name)

	got, ok := Snapshot()[name]
	if !ok {
		t.Fatalf("Snapshot() dropped the untouched counter %q", name)
	}
	if got != 0 {
		t.Fatalf("Snapshot()[%q] = %d, want 0", name, got)
	}
}

func TestSnapshotReadsTheLiveValue(t *testing.T) {
	v := expvar.NewInt("test_live_value_total")
	v.Add(3)

	if got := Snapshot()["test_live_value_total"]; got != 3 {
		t.Fatalf("Snapshot() = %d, want 3", got)
	}

	v.Add(4)

	if got := Snapshot()["test_live_value_total"]; got != 7 {
		t.Fatalf("Snapshot() after a second increment = %d, want 7", got)
	}
}

// E2EDecryptFailuresTotal is a Map keyed by call site, and is the counter that
// was unreadable everywhere: it is only ever incremented on the agent, so the
// relay's /debug/vars shows it as {} forever.
func TestAMapVarContributesOneEntryPerKey(t *testing.T) {
	m := expvar.NewMap("test_by_site_total")
	m.Add("terminal_frame", 2)
	m.Add("body", 5)

	snap := Snapshot()

	if got := snap["test_by_site_total/terminal_frame"]; got != 2 {
		t.Errorf("terminal_frame = %d, want 2", got)
	}
	if got := snap["test_by_site_total/body"]; got != 5 {
		t.Errorf("body = %d, want 5", got)
	}
	if _, ok := snap["test_by_site_total"]; ok {
		t.Error("the map itself must not appear as a scalar entry")
	}
}

// cmdline and memstats are published by the runtime as Funcs. A full argv and
// a GC histogram have no business in an operator's health snapshot, and argv
// is the more pointed of the two: it is where a mis-launched agent's secrets
// would sit.
func TestSnapshotExcludesTheRuntimesOwnVars(t *testing.T) {
	snap := Snapshot()
	for _, name := range []string{"cmdline", "memstats"} {
		if _, ok := snap[name]; ok {
			t.Errorf("Snapshot() included the runtime var %q", name)
		}
	}
}
