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

// TerminalUp is a client->server terminal message. Seq is a client-assigned
// monotonic id echoed back in the matching "ack" TerminalDown.
type TerminalUp struct {
	Type    string `json:"type"` // "input" | "paste" | "resize"
	Text    string `json:"text,omitempty"`
	Columns int    `json:"columns,omitempty"`
	Rows    int    `json:"rows,omitempty"`
	Seq     int64  `json:"seq,omitempty"`
}
