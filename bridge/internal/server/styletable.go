package server

import (
	"bytes"
	"encoding/json"
	"slices"
	"strconv"
)

// styleTable gives one socket's style ids a stable identity.
//
// cmux numbers the styles in a render grid in the order it first meets them
// scanning the grid, and rebuilds that table on every replay. Scrolling one
// line changes what comes first, so ids move even though the styles do not --
// measured on a live pane, 47 of 48 entries had identical content between two
// frames and only 23 kept their id. Every span in both the scrollback and the
// visible rows carries an id, so one renumbering changes the bytes of ~2000
// spans that are otherwise the same.
// Nothing byte-level downstream can see through that: not the sticky-block
// delta, and not a compression window (see cmux-app-bly).
//
// This assigns ids by content instead. The table only ever grows, so an id
// means the same style for the life of the socket, and a scrollback that did
// not scroll is byte-identical again. Per socket and never shared: a reconnect
// starts empty, which is what keeps a reconnect a clean resync.
//
// The app needs nothing from this. It looks style_id up in whatever styles
// table the frame carries, and does not care who numbered it.
type styleTable struct {
	ids     map[string]int
	entries []json.RawMessage

	epoch          json.RawMessage
	scrollbackRows json.RawMessage
	primed         bool
}

func newStyleTable() *styleTable { return &styleTable{ids: map[string]int{}} }

// canonicalise returns grid with every span's style_id rewritten to this
// socket's stable ids and styles replaced by the socket's table.
//
// Anything this cannot do safely returns grid untouched and resets the table,
// so the frame goes out exactly as cmux produced it. That is the same
// direction every failure takes in deltaEncoder.strip and gridFingerprint: a
// redundant frame costs bytes, a mangled one costs correctness. The cases that
// bail are a grid that will not decode, a span or style missing the field this
// needs, a span pointing at a style the table does not have, and the grid
// states in [styleTableBailsOn] that this has never observed and will not
// guess about.
func (t *styleTable) canonicalise(grid json.RawMessage) json.RawMessage {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(grid, &fields); err != nil {
		return t.bail(grid)
	}
	if !t.sameEpoch(fields) || styleTableBailsOn(fields) {
		return t.bail(grid)
	}
	remap, ok := t.absorb(fields["styles"])
	if !ok {
		return t.bail(grid)
	}
	for _, k := range spanBlocks {
		spans, present := fields[k]
		if !present {
			continue
		}
		rewritten, ok := rewriteStyleIDs(spans, remap)
		if !ok {
			return t.bail(grid)
		}
		fields[k] = rewritten
	}
	fields["styles"] = appendJSONArray(nil, t.entries)
	return appendJSONObject(make([]byte, 0, len(grid)), fields)
}

// appendJSONObject writes fields as one JSON object, values verbatim. Every
// value here either came out of json.Unmarshal or was produced by this file,
// so re-validating them -- which is what json.Marshal does to a RawMessage,
// and what made the map path twice as slow -- buys nothing. Keys are written
// sorted, matching json.Marshal's order, so the output is byte-stable.
func appendJSONObject(buf []byte, fields map[string]json.RawMessage) []byte {
	keys := make([]string, 0, len(fields))
	for k := range fields {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	buf = append(buf, '{')
	for i, k := range keys {
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = strconv.AppendQuote(buf, k)
		buf = append(buf, ':')
		buf = append(buf, fields[k]...)
	}
	return append(buf, '}')
}

func appendJSONArray(buf []byte, items []json.RawMessage) []byte {
	buf = append(buf, '[')
	for i, item := range items {
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = append(buf, item...)
	}
	return append(buf, ']')
}

// spanBlocks are the only places a style_id appears. The cursor carries
// style:"block", a shape, not an id.
var spanBlocks = []string{"row_spans", "scrollback_spans"}

func (t *styleTable) bail(grid json.RawMessage) json.RawMessage {
	t.ids = map[string]int{}
	t.entries = nil
	t.primed = false
	return grid
}

// sameEpoch records the identity of the grid this table was built against and
// reports whether the given grid is still that one. A changed render_epoch is
// a pane reset and a changed scrollback_rows is a resize; neither has been
// observed, and in both nothing about the previous rows can be assumed to
// carry over. A grid without either field has no identity to compare, so it
// is never the same one.
func (t *styleTable) sameEpoch(fields map[string]json.RawMessage) bool {
	epoch, rows := fields["render_epoch"], fields["scrollback_rows"]
	if epoch == nil || rows == nil {
		return false
	}
	if !t.primed {
		t.epoch, t.scrollbackRows, t.primed = bytes.Clone(epoch), bytes.Clone(rows), true
		return true
	}
	return bytes.Equal(t.epoch, epoch) && bytes.Equal(t.scrollbackRows, rows)
}

// styleTableBailsOn lists the grid states the design has not observed. Each
// was constant across every frame captured, which is exactly why they are
// treated as unknown rather than as handled: rows cleared in place rather
// than scrolled, a viewport moved independently of the history, a user panned
// up so the scrollback is not tracking the tail, and the alternate screen,
// where the scrollback is frozen.
func styleTableBailsOn(fields map[string]json.RawMessage) bool {
	return !rawEquals(fields["cleared_rows"], `[]`) ||
		!rawEquals(fields["scrolled_rows"], `0`) ||
		!rawEquals(fields["anchor"], `"viewport"`) ||
		!rawEquals(fields["active_screen"], `"primary"`)
}

// rawEquals compares a raw JSON value to its expected compact form,
// tolerating the whitespace cmux's pretty-printer puts around it. A missing
// field compares unequal, so an older cmux that does not send one of these is
// treated as unknown rather than as safe.
func rawEquals(raw json.RawMessage, want string) bool {
	if raw == nil {
		return false
	}
	var buf bytes.Buffer
	if err := json.Compact(&buf, raw); err != nil {
		return false
	}
	return buf.String() == want
}

// absorb folds a frame's styles into the table and returns how cmux's ids for
// this frame map onto the table's. A style seen before gets the id it already
// has; a new one is appended. The canonical form is the entry without its id,
// re-marshalled, which sorts keys so equal content always yields equal bytes.
//
// Id 0 is the one id that is not arbitrary. The app fills blank cells with it
// and reads the grid's default background from styles[0], and cmux keeps its
// default style there on every frame observed. So the table is primed with
// cmux's 0 as its own 0, and a frame whose id 0 is no longer that style -- a
// changed default, or none at all -- fails, so the app gets the new default
// from cmux directly and the next frame primes again.
func (t *styleTable) absorb(styles json.RawMessage) (map[int]int, bool) {
	if styles == nil {
		return nil, false
	}
	var entries []map[string]json.RawMessage
	if err := json.Unmarshal(styles, &entries); err != nil {
		return nil, false
	}
	remap := make(map[int]int, len(entries))
	for _, entry := range orderDefaultFirst(entries) {
		cmuxID, ok := intField(entry, "id")
		if !ok {
			return nil, false
		}
		delete(entry, "id")
		content, err := json.Marshal(entry)
		if err != nil {
			return nil, false
		}
		id, seen := t.ids[string(content)]
		if !seen {
			id = len(t.entries)
			entry["id"] = json.RawMessage(strconv.Itoa(id))
			stable, err := json.Marshal(entry)
			if err != nil {
				return nil, false
			}
			t.ids[string(content)] = id
			t.entries = append(t.entries, stable)
		}
		remap[cmuxID] = id
	}
	if id, ok := remap[0]; !ok || id != 0 {
		return nil, false
	}
	return remap, true
}

// orderDefaultFirst puts cmux's id 0 at the front so that, on an empty table,
// it is the entry that becomes the bridge's 0. cmux lists it first anyway;
// this just stops that being something the correctness depends on.
func orderDefaultFirst(entries []map[string]json.RawMessage) []map[string]json.RawMessage {
	for i, entry := range entries {
		if id, ok := intField(entry, "id"); ok && id == 0 {
			if i == 0 {
				return entries
			}
			ordered := make([]map[string]json.RawMessage, 0, len(entries))
			ordered = append(ordered, entry)
			ordered = append(ordered, entries[:i]...)
			return append(ordered, entries[i+1:]...)
		}
	}
	return entries
}

// rewriteStyleIDs returns spans with each style_id passed through remap. A
// span without a style_id, or with one the table has not seen, is a shape this
// does not understand and fails the frame.
//
// The fast path decodes into [knownSpan], which names every field cmux sends
// today and refuses any other. That refusal is the fallback trigger, not a
// failure: a span with a field this does not know is re-read through a map,
// which is several times slower but copies every field as it came, so a cmux
// that adds one keeps working. Measured on a real 327KB grid, the typed path is
// what keeps a frame inside the 5ms budget.
func rewriteStyleIDs(spans json.RawMessage, remap map[int]int) (json.RawMessage, bool) {
	if out, ok := rewriteKnownSpans(spans, remap); ok {
		return out, true
	}
	return rewriteAnySpans(spans, remap)
}

// knownSpan is the span shape cmux sends today. text and cell_width stay raw
// so they are copied byte for byte rather than unescaped and re-escaped.
// The ints are pointers so that a span missing one is seen as missing --
// DisallowUnknownFields only rejects extra keys, not absent ones -- rather
// than read as 0 and written back as if cmux had sent it.
type knownSpan struct {
	Row       *int            `json:"row"`
	Column    *int            `json:"column"`
	StyleID   *int            `json:"style_id"`
	Text      json.RawMessage `json:"text"`
	CellWidth json.RawMessage `json:"cell_width,omitempty"`
}

func (sp *knownSpan) complete() bool {
	return sp.Row != nil && sp.Column != nil && sp.StyleID != nil && sp.Text != nil
}

func rewriteKnownSpans(spans json.RawMessage, remap map[int]int) (json.RawMessage, bool) {
	dec := json.NewDecoder(bytes.NewReader(spans))
	dec.DisallowUnknownFields()
	var list []knownSpan
	if err := dec.Decode(&list); err != nil {
		return nil, false
	}
	buf := make([]byte, 0, len(spans))
	buf = append(buf, '[')
	for i := range list {
		if !list[i].complete() {
			return nil, false
		}
		id, known := remap[*list[i].StyleID]
		if !known {
			return nil, false
		}
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = list[i].appendWithStyle(buf, id)
	}
	return append(buf, ']'), true
}

// appendWithStyle writes the span with its style_id replaced, keys in the
// sorted order json.Marshal would use, so a frame that takes this path and
// one that fell back to the map path produce the same bytes.
func (sp *knownSpan) appendWithStyle(buf []byte, styleID int) []byte {
	buf = append(buf, '{')
	if sp.CellWidth != nil {
		buf = append(buf, `"cell_width":`...)
		buf = append(buf, sp.CellWidth...)
		buf = append(buf, ',')
	}
	buf = append(buf, `"column":`...)
	buf = strconv.AppendInt(buf, int64(*sp.Column), 10)
	buf = append(buf, `,"row":`...)
	buf = strconv.AppendInt(buf, int64(*sp.Row), 10)
	buf = append(buf, `,"style_id":`...)
	buf = strconv.AppendInt(buf, int64(styleID), 10)
	buf = append(buf, `,"text":`...)
	buf = append(buf, sp.Text...)
	return append(buf, '}')
}

func rewriteAnySpans(spans json.RawMessage, remap map[int]int) (json.RawMessage, bool) {
	var list []map[string]json.RawMessage
	if err := json.Unmarshal(spans, &list); err != nil {
		return nil, false
	}
	for _, span := range list {
		cmuxID, ok := intField(span, "style_id")
		if !ok {
			return nil, false
		}
		id, known := remap[cmuxID]
		if !known {
			return nil, false
		}
		span["style_id"] = json.RawMessage(strconv.Itoa(id))
	}
	out, err := json.Marshal(list)
	if err != nil {
		return nil, false
	}
	return out, true
}

func intField(obj map[string]json.RawMessage, key string) (int, bool) {
	raw, ok := obj[key]
	if !ok {
		return 0, false
	}
	var n int
	if err := json.Unmarshal(raw, &n); err != nil {
		return 0, false
	}
	return n, true
}
