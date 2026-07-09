// Package backoff provides a small exponential-backoff-with-full-jitter
// helper shared by the bridge's reconnect/restart loops (relay dial, cmux
// events stream, agent push subscription), so a shared upstream outage
// (relay restart, cmux crash) doesn't produce synchronized, lockstep
// retries across every loop hammering it back to life at once.
package backoff

import (
	"math/rand"
	"time"
)

// Backoff tracks one loop's escalating retry delay.
type Backoff struct {
	base, max, cur time.Duration
}

// New returns a Backoff whose first Next() call is a jittered delay up to
// base, doubling on each subsequent call up to max.
func New(base, max time.Duration) *Backoff {
	return &Backoff{base: base, max: max, cur: base}
}

// Next returns a full-jitter delay -- a uniformly random duration in
// [0, cur) -- and advances cur toward max for the following call.
func (b *Backoff) Next() time.Duration {
	delay := time.Duration(rand.Int63n(int64(b.cur)))
	if b.cur < b.max {
		b.cur *= 2
		if b.cur > b.max {
			b.cur = b.max
		}
	}
	return delay
}
