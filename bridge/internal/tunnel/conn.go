// Package tunnel carries the cmux relay's Mac↔relay link: a single WebSocket
// (so it traverses nginx mTLS on :443) wrapped as a net.Conn and multiplexed
// with yamux. The agent runs http.Serve over the session; the relay opens one
// stream per proxied request.
package tunnel

import (
	"io"
	"net"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// wsConn adapts a gorilla *websocket.Conn (message framed) to a net.Conn (byte
// stream) by carrying bytes in binary WebSocket messages. yamux runs on top.
// yamux drives all reads from one goroutine and all writes from one goroutine;
// the mutexes are belt-and-suspenders.
type wsConn struct {
	ws  *websocket.Conn
	rmu sync.Mutex
	wmu sync.Mutex
	r   io.Reader // current message reader, or nil between messages
}

func newWSConn(ws *websocket.Conn) *wsConn { return &wsConn{ws: ws} }

func (c *wsConn) Read(p []byte) (int, error) {
	c.rmu.Lock()
	defer c.rmu.Unlock()
	for {
		if c.r != nil {
			n, err := c.r.Read(p)
			if err == io.EOF {
				c.r = nil
				if n > 0 {
					return n, nil
				}
				continue
			}
			return n, err
		}
		mt, r, err := c.ws.NextReader()
		if err != nil {
			return 0, err
		}
		if mt != websocket.BinaryMessage {
			continue
		}
		c.r = r
	}
}

func (c *wsConn) Write(p []byte) (int, error) {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	if err := c.ws.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wsConn) Close() error         { return c.ws.Close() }
func (c *wsConn) LocalAddr() net.Addr  { return c.ws.LocalAddr() }
func (c *wsConn) RemoteAddr() net.Addr { return c.ws.RemoteAddr() }

func (c *wsConn) SetReadDeadline(t time.Time) error  { return c.ws.SetReadDeadline(t) }
func (c *wsConn) SetWriteDeadline(t time.Time) error { return c.ws.SetWriteDeadline(t) }

func (c *wsConn) SetDeadline(t time.Time) error {
	if err := c.ws.SetReadDeadline(t); err != nil {
		return err
	}
	return c.ws.SetWriteDeadline(t)
}
