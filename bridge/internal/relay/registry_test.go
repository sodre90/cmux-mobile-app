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

func TestRegistrySetReplaceClosesOldForSameTenant(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	stopped := make(chan struct{})
	r.Set("tenant-a", s1, func() { close(stopped) })

	s2 := mkSession(t)
	r.Set("tenant-a", s2, nil)

	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("prior stop func not called on replace")
	}
	if !s1.IsClosed() {
		t.Fatal("prior session should be closed on replace")
	}
	if r.Get("tenant-a") != s2 {
		t.Fatal("Get(tenant-a) should return s2")
	}
}

func TestRegistryTenantsDoNotInterfere(t *testing.T) {
	r := NewRegistry()
	sa := mkSession(t)
	sb := mkSession(t)
	r.Set("tenant-a", sa, nil)
	r.Set("tenant-b", sb, nil)

	if r.Get("tenant-a") != sa {
		t.Fatal("tenant-a's session should be unaffected by tenant-b's Set")
	}
	if r.Get("tenant-b") != sb {
		t.Fatal("tenant-b's session should be present")
	}
	r.Clear("tenant-a", sa)
	if r.Get("tenant-a") != nil {
		t.Fatal("tenant-a should be cleared")
	}
	if r.Get("tenant-b") != sb {
		t.Fatal("clearing tenant-a must not affect tenant-b")
	}
}

func TestRegistryClearOnlyIfCurrent(t *testing.T) {
	r := NewRegistry()
	s1 := mkSession(t)
	r.Set("tenant-a", s1, nil)
	other := mkSession(t)
	r.Clear("tenant-a", other) // not current for tenant-a → no-op
	if r.Get("tenant-a") != s1 {
		t.Fatal("Clear of a non-current session should be a no-op")
	}
	r.Clear("tenant-a", s1)
	if r.Get("tenant-a") != nil {
		t.Fatal("Get should be nil after clearing the active session")
	}
}

func TestRegistryGetUnknownTenant(t *testing.T) {
	r := NewRegistry()
	if r.Get("never-registered") != nil {
		t.Fatal("Get on an unknown tenant should return nil, not panic")
	}
}
