package backoff

import (
	"context"
	"testing"
	"time"
)

func TestNextStaysWithinCapAndEscalatesToMax(t *testing.T) {
	b := New(time.Second, 30*time.Second)
	var lastCap time.Duration
	for i := 0; i < 10; i++ {
		d := b.Next()
		if d < 0 || d > 30*time.Second {
			t.Fatalf("Next()[%d] = %v, want within [0, 30s]", i, d)
		}
		lastCap = b.cur
	}
	if lastCap != 30*time.Second {
		t.Fatalf("cur should have escalated to the 30s cap, got %v", lastCap)
	}
}

func TestNextNeverExceedsMaxOnceCapped(t *testing.T) {
	b := New(time.Second, 4*time.Second)
	for i := 0; i < 20; i++ {
		if d := b.Next(); d > 4*time.Second {
			t.Fatalf("Next()[%d] = %v, want <= max (4s)", i, d)
		}
	}
}

func TestNextIsJittered(t *testing.T) {
	// Not deterministic proof of randomness, but catches the "always returns
	// cur-1" or "always returns 0" regressions cheaply: across enough draws
	// from a wide-enough range we should see more than one distinct value.
	b := New(time.Second, time.Second)
	seen := map[time.Duration]bool{}
	for i := 0; i < 50; i++ {
		seen[b.Next()] = true
	}
	if len(seen) < 2 {
		t.Fatalf("expected multiple distinct delays from repeated Next() calls, got %v", seen)
	}
}

func TestSleepReturnsTrueWhenDurationElapses(t *testing.T) {
	if !Sleep(context.Background(), time.Millisecond) {
		t.Fatal("expected Sleep to report the full duration elapsed")
	}
}

func TestSleepReturnsPromptlyOnContextCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	start := time.Now()
	if Sleep(ctx, 30*time.Second) {
		t.Fatal("expected Sleep to report early return on cancelled ctx")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("Sleep took %v on an already-cancelled ctx, want near-instant", elapsed)
	}
}
