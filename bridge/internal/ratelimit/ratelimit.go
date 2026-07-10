// Package ratelimit provides a minimal per-key cooldown window: at most one
// Allow(key) call succeeds per key per interval. It exists for low-frequency,
// potentially-abusable, device-triggered actions (currently only the
// test-push endpoints in internal/server and internal/relay) where a shared,
// tiny, dependency-free primitive is enough -- it does not replace or
// refactor internal/relay.ipRateLimiter, which predates this package and
// guards a different, IP-keyed set of routes.
package ratelimit

import (
	"sync"
	"time"
)

// Cooldown allows one Allow(key) call to succeed per key per interval,
// sweeping stale entries on access so the map doesn't grow unboundedly under
// a distributed-key flood.
type Cooldown struct {
	mu       sync.Mutex
	interval time.Duration
	last     map[string]time.Time
}

// NewCooldown returns a Cooldown allowing one call per key every interval.
func NewCooldown(interval time.Duration) *Cooldown {
	return &Cooldown{interval: interval, last: map[string]time.Time{}}
}

// Allow reports whether key may proceed now. A true result records this call
// as key's most recent, starting a fresh cooldown window.
func (c *Cooldown) Allow(key string) bool {
	now := time.Now()
	c.mu.Lock()
	defer c.mu.Unlock()
	if t, ok := c.last[key]; ok && now.Sub(t) < c.interval {
		return false
	}
	c.last[key] = now
	for k, t := range c.last {
		if now.Sub(t) > 10*c.interval {
			delete(c.last, k)
		}
	}
	return true
}
