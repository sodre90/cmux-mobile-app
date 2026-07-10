package cmux

import (
	"context"
	"encoding/json"
)

// fastPathPoolSize bounds how many concurrent, independently-authenticated
// connections the fast path keeps open to cmux's control socket. Each
// connection serializes its own request/response pairs end-to-end (see
// socketConn.rpc) with no cross-connection state -- request IDs, the auth
// handshake, and the committed-on-write contract are all scoped to a single
// connection -- so holding several open concurrently is safe. That lets an
// unrelated fast call (e.g. an input ack) proceed on an idle connection
// while a slow one (e.g. a large replay poll) is still in flight on
// another, instead of queuing behind it.
//
// 4 is deliberately small: it comfortably covers the bridge's realistic
// concurrency (a handful of terminal panes plus occasional rename/yolo
// calls from one or a few paired devices) without holding open more
// authenticated sockets to cmux than are ever likely to be used at once.
const fastPathPoolSize = 4

// socketPool is a fixed-size, checkout-and-return free list of socketConns.
// Each connection is used by at most one caller at a time; when every
// connection is checked out, further callers block until one is returned
// (or ctx is done) rather than dialing unbounded extra connections.
type socketPool struct {
	conns chan *socketConn
}

func newSocketPool(size int) *socketPool {
	p := &socketPool{conns: make(chan *socketConn, size)}
	for i := 0; i < size; i++ {
		p.conns <- &socketConn{}
	}
	return p
}

// rpc checks out an idle connection, runs the call on it, and returns it to
// the pool. Reconnecting happens lazily inside socketConn.rpc exactly as it
// did for the single shared connection this pool replaces; the returned
// committed flag carries the same contract documented on socketConn.rpc.
func (p *socketPool) rpc(ctx context.Context, method string, params any) (result json.RawMessage, committed bool, err error) {
	select {
	case sc := <-p.conns:
		defer func() { p.conns <- sc }()
		return sc.rpc(ctx, method, params)
	case <-ctx.Done():
		return nil, false, ctx.Err()
	}
}

// closeAll drops every idle connection in the pool, forcing each to
// reconnect from scratch on its next checkout. Exported within the package
// for tests simulating cmux restarting, which breaks every open connection
// at once, not just one.
func (p *socketPool) closeAll() {
	size := cap(p.conns)
	conns := make([]*socketConn, 0, size)
	for i := 0; i < size; i++ {
		sc := <-p.conns
		sc.close()
		conns = append(conns, sc)
	}
	for _, sc := range conns {
		p.conns <- sc
	}
}
