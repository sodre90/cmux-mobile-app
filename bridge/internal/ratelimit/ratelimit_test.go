package ratelimit

import (
	"testing"
	"time"
)

func TestCooldownAllowsFirstCallAndBlocksWithinInterval(t *testing.T) {
	c := NewCooldown(time.Hour)
	if !c.Allow("dev1") {
		t.Fatal("first call for a key must be allowed")
	}
	if c.Allow("dev1") {
		t.Fatal("second call within the interval must be blocked")
	}
}

func TestCooldownIsScopedPerKey(t *testing.T) {
	c := NewCooldown(time.Hour)
	if !c.Allow("dev1") {
		t.Fatal("dev1 first call must be allowed")
	}
	if !c.Allow("dev2") {
		t.Fatal("dev2 is a different key and must be allowed independently of dev1")
	}
}

func TestCooldownAllowsAgainAfterIntervalElapses(t *testing.T) {
	c := NewCooldown(10 * time.Millisecond)
	if !c.Allow("dev1") {
		t.Fatal("first call must be allowed")
	}
	time.Sleep(15 * time.Millisecond)
	if !c.Allow("dev1") {
		t.Fatal("call after the interval elapsed must be allowed again")
	}
}
