package relay

import (
	"net"
	"testing"
	"time"

	"github.com/hashicorp/yamux"
)

func mkSession(t *testing.T) *yamux.Session {
	t.Helper()
	c1, c2 := net.Pipe()
	t.Cleanup(func() { c1.Close(); c2.Close() })
	go func() { _, _ = yamux.Client(c2, nil) }() // peer end keeps the pipe alive
	s, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestRegistryReplaceClosesOld(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	stopped := make(chan struct{})
	r.Set(s1, func() { close(stopped) })

	s2 := mkSession(t)
	r.Set(s2, nil)

	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("prior stop func not called on replace")
	}
	if !s1.IsClosed() {
		t.Fatal("prior session should be closed on replace")
	}
	if r.Current() != s2 {
		t.Fatal("Current should be s2")
	}
}

func TestRegistryClearOnlyIfCurrent(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	r.Set(s1, nil)
	other := mkSession(t)
	r.Clear(other) // not current → no-op
	if r.Current() != s1 {
		t.Fatal("Clear of a non-current session should be a no-op")
	}
	r.Clear(s1)
	if r.Current() != nil {
		t.Fatal("Current should be nil after clearing the active session")
	}
}
