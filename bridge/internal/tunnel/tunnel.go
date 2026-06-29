package tunnel

import (
	"context"
	"crypto/tls"
	"io"
	"net/http"
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
	d := websocket.Dialer{
		TLSClientConfig:  tlsCfg,
		HandshakeTimeout: 15 * time.Second,
	}
	ws, _, err := d.DialContext(ctx, relayURL, header)
	if err != nil {
		return nil, err
	}
	return yamux.Server(newWSConn(ws), yamuxCfg())
}
