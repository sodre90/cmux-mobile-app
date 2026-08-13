package relay

import (
	"net"
	"testing"
)

// fakeConn is a net.Conn that only records whether it was closed -- the
// tracker never reads or writes, it only holds and closes.
type fakeConn struct {
	net.Conn
	closed bool
}

func (c *fakeConn) Close() error {
	c.closed = true
	return nil
}

func alwaysActive(string) bool { return true }

func TestSweepClosesAConnectionWhoseDeviceWasRevoked(t *testing.T) {
	tracker := NewConnTracker()
	raw := &fakeConn{}
	tracker.Track("hash-gone", "tenant-a", raw)

	if closed := tracker.CloseRevoked(map[string]string{}, alwaysActive); closed != 1 {
		t.Fatalf("closed %d connections, want 1", closed)
	}
	if !raw.closed {
		t.Fatal("a revoked device's connection must be closed, not just forgotten")
	}
}

func TestSweepLeavesAStillListedDeviceAlone(t *testing.T) {
	tracker := NewConnTracker()
	kept := &fakeConn{}
	revoked := &fakeConn{}
	tracker.Track("hash-live", "tenant-a", kept)
	tracker.Track("hash-gone", "tenant-a", revoked)

	tracker.CloseRevoked(map[string]string{"hash-live": "tenant-a"}, alwaysActive)

	if kept.closed {
		t.Fatal("revoking one device must not close another device's connection")
	}
	if !revoked.closed {
		t.Fatal("the revoked device's connection should have been closed")
	}
}

// The tenant's own devices still have rows; it is the tenant that is gone.
func TestSweepClosesConnectionsOfARevokedTenant(t *testing.T) {
	tracker := NewConnTracker()
	doomed := &fakeConn{}
	other := &fakeConn{}
	tracker.Track("hash-a", "tenant-dead", doomed)
	tracker.Track("hash-b", "tenant-live", other)

	live := map[string]string{"hash-a": "tenant-dead", "hash-b": "tenant-live"}
	tracker.CloseRevoked(live, func(id string) bool { return id == "tenant-live" })

	if !doomed.closed {
		t.Fatal("a revoked tenant's device connection must close even though its row survives")
	}
	if other.closed {
		t.Fatal("a live tenant's connection must survive another tenant's revocation")
	}
}

// Without this the map would grow with request count: every proxied request
// dials a stream, and only WebSockets are long-lived.
func TestClosingAConnectionDeregistersIt(t *testing.T) {
	tracker := NewConnTracker()
	raw := &fakeConn{}
	tracked := tracker.Track("hash-a", "tenant-a", raw)

	if err := tracked.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	tracker.mu.Lock()
	remaining := len(tracker.conns)
	tracker.mu.Unlock()
	if remaining != 0 {
		t.Fatalf("tracker still holds %d device entries after Close", remaining)
	}
	if closed := tracker.CloseRevoked(map[string]string{}, alwaysActive); closed != 0 {
		t.Fatalf("a closed connection must not be swept again, got %d", closed)
	}
}

func TestSweepDoesNotDoubleCloseAConnectionItAlreadyClosed(t *testing.T) {
	tracker := NewConnTracker()
	raw := &fakeConn{}
	tracker.Track("hash-gone", "tenant-a", raw)

	tracker.CloseRevoked(map[string]string{}, alwaysActive)
	if closed := tracker.CloseRevoked(map[string]string{}, alwaysActive); closed != 0 {
		t.Fatalf("second sweep closed %d connections, want 0", closed)
	}
}
