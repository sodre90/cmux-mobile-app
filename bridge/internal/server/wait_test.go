package server

import (
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// The read deadlines in this package exist so a socket that never answers
// fails the test instead of hanging it; they are not latency assertions, and
// `go test -timeout` is the real backstop. They were hand-written as 2s at
// ~25 call sites, which is generous when a package runs alone and too tight
// when `go test ./...` puts 24 packages on the CPU at once and every terminal
// RPC forks a shell -- the gate then failed roughly one run in four with a
// bare i/o timeout (cmux-app-afu). A passing test never waits this long.
const wsReadWait = 15 * time.Second

// How long to keep polling for a condition another goroutine has to reach.
// Same reasoning as wsReadWait: generous, and only ever paid on failure.
const settleWait = 5 * time.Second

const settlePollInterval = 10 * time.Millisecond

func armReadDeadline(t *testing.T, c *websocket.Conn) {
	t.Helper()
	if err := c.SetReadDeadline(time.Now().Add(wsReadWait)); err != nil {
		t.Fatalf("set read deadline: %v", err)
	}
}

// eventually polls until cond holds, and reports whether it ever did. The
// point is to wait on the condition itself rather than on a duration guessed
// to be longer than a busy machine needs.
func eventually(cond func() bool) bool {
	deadline := time.Now().Add(settleWait)
	for {
		if cond() {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(settlePollInterval)
	}
}

// waitForRPCLog blocks until the fake cmux's argv log contains every needle,
// which is how the terminal tests observe an RPC actually reaching cmux.
func waitForRPCLog(t *testing.T, logPath string, needles ...string) {
	t.Helper()
	logged := func() bool {
		data, err := os.ReadFile(logPath)
		if err != nil {
			return false
		}
		for _, needle := range needles {
			if !strings.Contains(string(data), needle) {
				return false
			}
		}
		return true
	}
	if !eventually(logged) {
		data, _ := os.ReadFile(logPath)
		t.Fatalf("rpc %v not dispatched; log:\n%s", needles, data)
	}
}

func (h *hub) subscriberCount() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns)
}

// waitForSubscribers blocks until the /events handler has registered with the
// hub. Dialing only gets the upgrade done; registration happens afterwards on
// the handler's own goroutine, and a broadcast sent before it lands is
// dropped rather than queued.
func waitForSubscribers(t *testing.T, h *hub, want int) {
	t.Helper()
	if !eventually(func() bool { return h.subscriberCount() == want }) {
		t.Fatalf("hub has %d subscribers, want %d", h.subscriberCount(), want)
	}
}

func (t *socketTracker) count() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	n := 0
	for _, peers := range t.sockets {
		n += len(peers)
	}
	return n
}

// waitForTrackedSockets blocks until the handler has registered its socket for
// revocation, which likewise happens after the dial returns.
func waitForTrackedSockets(t *testing.T, tracker *socketTracker, want int) {
	t.Helper()
	if !eventually(func() bool { return tracker.count() == want }) {
		t.Fatalf("tracker holds %d sockets, want %d", tracker.count(), want)
	}
}
