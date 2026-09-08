package wire

import "encoding/json"

// TerminalDown is a server->client terminal message. Grid carries the cmux
// render-grid object (format "cmux.render-grid.v1") verbatim; the app renders it
// as a styled cell grid. "ack" echoes back an input/paste/resize message's Seq
// once its RPC has run, with Ok reflecting whether that RPC actually succeeded.
type TerminalDown struct {
	Type    string          `json:"type"` // "replay" | "output" | "ack"
	Grid    json.RawMessage `json:"grid,omitempty"`
	Columns int             `json:"columns,omitempty"`
	Rows    int             `json:"rows,omitempty"`
	Seq     int64           `json:"seq,omitempty"`
	// Ok is only meaningful for "ack" frames; not omitempty because a failed
	// RPC's ack (Ok: false) must be distinguishable on the wire from an "ok"
	// field that was never set.
	Ok bool `json:"ok"`
}

// CloseSurfaceGone is the WebSocket close code WS /terminal/{id} uses to say
// the surface no longer exists, so the client stops reconnecting to an id cmux
// will never have again (cmux-app-34c). Mirrored in the app as
// TerminalSocket's CLOSE_SURFACE_GONE.
//
// A close code rather than a TerminalDown field, deliberately: it rides the
// transport, so it needs no e2e frame (the socket may be closing before any
// session is usable) and a client that ignores it behaves exactly as before.
// It is sent with an empty reason string -- the number says everything the
// deliberately blind relay could not already infer from the connection ending.
//
// 4404 is in the 4000-4999 range RFC 6455 reserves for private application use.
const CloseSurfaceGone = 4404

// TerminalUp is a client->server terminal message. Seq is a client-assigned
// monotonic id echoed back in the matching "ack" TerminalDown.
type TerminalUp struct {
	Type    string `json:"type"` // "input" | "paste" | "resize"
	Text    string `json:"text,omitempty"`
	Columns int    `json:"columns,omitempty"`
	Rows    int    `json:"rows,omitempty"`
	Seq     int64  `json:"seq,omitempty"`
}
