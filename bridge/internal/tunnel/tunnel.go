package tunnel

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"
)

func yamuxCfg() *yamux.Config {
	c := yamux.DefaultConfig()
	c.EnableKeepAlive = true
	c.KeepAliveInterval = 15 * time.Second
	c.ConnectionWriteTimeout = 10 * time.Second
	c.LogOutput = io.Discard
	return c
}

// acceptUpgrader upgrades the agent's inbound request. Auth is mTLS + client-CN
// (enforced upstream), not Origin, so any Origin is accepted.
var acceptUpgrader = websocket.Upgrader{
	CheckOrigin: func(*http.Request) bool { return true },
}

// Accept upgrades an inbound agent request to a WebSocket and returns a yamux
// session on which the relay opens one stream per proxied request.
func Accept(w http.ResponseWriter, r *http.Request) (*yamux.Session, error) {
	ws, err := acceptUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return nil, err
	}
	return yamux.Client(newWSConn(ws), yamuxCfg())
}

// Dial opens the agent's outbound WebSocket to relayURL (wss://…/agent/tunnel),
// presenting the client cert in tlsCfg, and returns a yamux session the agent
// serves with http.Serve.
func Dial(ctx context.Context, relayURL string, tlsCfg *tls.Config, header http.Header) (*yamux.Session, error) {
	var attempted dialAttempts
	d := websocket.Dialer{
		TLSClientConfig:  tlsCfg,
		HandshakeTimeout: 15 * time.Second,
		NetDialContext:   attempted.dialer().DialContext,
	}
	ws, _, err := d.DialContext(ctx, relayURL, header)
	if err != nil {
		return nil, attempted.annotate(err)
	}
	return yamux.Server(newWSConn(ws), yamuxCfg())
}

// dialAttempts records every address net.Dialer actually tried.
//
// Diagnostics only -- it deliberately does not change how dialing works.
// net.Dialer already races IPv6 against IPv4 (RFC 6555) and falls back the
// moment the primary errors, but when every address fails it returns the
// *primary* family's error. A dual-stack failure therefore surfaces as a bare
// "dial tcp [<v6>]:443: connect: no route to host", which reads as an
// IPv6-specific fault when the real condition is usually "this host has no
// usable route yet at all" (seen on agent startup, before the network is up).
// Listing the addresses actually attempted distinguishes the two.
type dialAttempts struct {
	mu    sync.Mutex
	addrs []string
}

// dialer returns a net.Dialer whose ControlContext runs once per connection
// attempt -- after the socket exists, before connect() -- so it observes every
// address the happy-eyeballs race reaches, including those that go on to fail.
func (a *dialAttempts) dialer() *net.Dialer {
	return &net.Dialer{
		ControlContext: func(_ context.Context, _, address string, _ syscall.RawConn) error {
			a.mu.Lock()
			defer a.mu.Unlock()
			a.addrs = append(a.addrs, address)
			return nil
		},
	}
}

func (a *dialAttempts) annotate(err error) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.addrs) == 0 {
		return err
	}
	return fmt.Errorf("%w (tried %d address(es): %s)", err, len(a.addrs), strings.Join(a.addrs, ", "))
}
