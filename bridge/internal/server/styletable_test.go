package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

// gridWith builds a grid the way cmux does: the default style at id 0, then
// the given styles numbered from 1, and spans that point into the table. The
// style content is fixed per name so a test can renumber the table and keep
// the content the same, which is exactly what cmux does when rows scroll.
func gridWith(styles []string, spans ...string) json.RawMessage {
	table := []string{`{"id":0,"foreground":"#fff","background":"#000","bold":false}`}
	for i, name := range styles {
		table = append(table, fmt.Sprintf(`{"id":%d,"foreground":"#%s","bold":%v}`, i+1, name, name == "bold"))
	}
	return json.RawMessage(fmt.Sprintf(
		`{"render_epoch":"E1","scrollback_rows":240,"cleared_rows":[],"scrolled_rows":0,`+
			`"anchor":"viewport","active_screen":"primary",`+
			`"styles":[%s],"row_spans":[%s],"scrollback_spans":[]}`,
		strings.Join(table, ","), strings.Join(spans, ",")))
}

func span(row, styleID int, text string) string {
	return fmt.Sprintf(`{"row":%d,"column":0,"style_id":%d,"text":%q,"cell_width":1}`, row, styleID, text)
}

func styleIDs(t *testing.T, grid json.RawMessage, block string) []int {
	t.Helper()
	var g struct {
		RowSpans []struct {
			StyleID int `json:"style_id"`
		} `json:"row_spans"`
		Scrollback []struct {
			StyleID int `json:"style_id"`
		} `json:"scrollback_spans"`
	}
	if err := json.Unmarshal(grid, &g); err != nil {
		t.Fatal(err)
	}
	spans := g.RowSpans
	if block == "scrollback_spans" {
		spans = g.Scrollback
	}
	ids := make([]int, len(spans))
	for i, s := range spans {
		ids[i] = s.StyleID
	}
	return ids
}

// The regression the whole design exists for. When rows scroll off, cmux
// renumbers the styles it still uses in the order it now meets them; the
// content of the surviving styles is identical. The two frames must come out
// byte-identical, because that is what lets the sticky-block delta omit them.
func TestARenumberedStyleTableComesOutByteIdentical(t *testing.T) {
	table := newStyleTable()
	first := table.canonicalise(gridWith([]string{"aaa", "bbb", "ccc"}, span(0, 2, "x"), span(1, 3, "y")))
	// Style "aaa" scrolled off; cmux now meets "bbb" first, so it is 1 and "ccc" is 2.
	second := table.canonicalise(gridWith([]string{"bbb", "ccc"}, span(0, 1, "x"), span(1, 2, "y")))
	if !bytes.Equal(first, second) {
		t.Fatalf("renumbered frames differ:\n%s\n%s", first, second)
	}
}

func TestSpansPointAtTheStableIdNotCmuxs(t *testing.T) {
	table := newStyleTable()
	table.canonicalise(gridWith([]string{"aaa", "bbb"}, span(0, 2, "x")))
	out := table.canonicalise(gridWith([]string{"bbb"}, span(0, 1, "x")))
	if got := styleIDs(t, out, "row_spans"); got[0] != 2 {
		t.Fatalf("span should keep its stable id 2 after cmux renumbered bbb to 1, got %d", got[0])
	}
}

// A style that drops out of cmux's table and later reappears must get the id
// it had before, not a fresh one -- otherwise a pane that scrolls a colour off
// and back on would churn again.
func TestAReappearingStyleGetsItsOriginalIdBack(t *testing.T) {
	table := newStyleTable()
	table.canonicalise(gridWith([]string{"aaa", "bbb"}, span(0, 1, "x")))
	table.canonicalise(gridWith([]string{"bbb"}, span(0, 1, "x")))
	out := table.canonicalise(gridWith([]string{"bbb", "aaa"}, span(0, 2, "x")))
	if got := styleIDs(t, out, "row_spans"); got[0] != 1 {
		t.Fatalf("aaa was stable id 1 and must be again, got %d", got[0])
	}
}

func TestANewStyleGetsANewIdAndTheTableOnlyGrows(t *testing.T) {
	table := newStyleTable()
	table.canonicalise(gridWith([]string{"aaa"}, span(0, 1, "x")))
	out := table.canonicalise(gridWith([]string{"aaa", "zzz"}, span(0, 2, "x")))
	var g struct {
		Styles []struct {
			ID int `json:"id"`
		} `json:"styles"`
	}
	if err := json.Unmarshal(out, &g); err != nil {
		t.Fatal(err)
	}
	if len(g.Styles) != 3 || g.Styles[2].ID != 2 {
		t.Fatalf("want a three-entry table with zzz at 2, got %+v", g.Styles)
	}
	if got := styleIDs(t, out, "row_spans"); got[0] != 2 {
		t.Fatalf("span should point at the new id 2, got %d", got[0])
	}
}

// The emitted table is the bridge's, so it must be stable even when cmux's
// keeps reordering -- and every id a span uses must be in it.
func TestTheEmittedTableIsTheBridgesNotCmuxs(t *testing.T) {
	table := newStyleTable()
	table.canonicalise(gridWith([]string{"aaa", "bbb"}, span(0, 1, "x")))
	out := table.canonicalise(gridWith([]string{"bbb", "aaa"}, span(0, 1, "x")))
	var g struct {
		Styles []struct {
			ID         int    `json:"id"`
			Foreground string `json:"foreground"`
		} `json:"styles"`
	}
	if err := json.Unmarshal(out, &g); err != nil {
		t.Fatal(err)
	}
	if g.Styles[1].Foreground != "#aaa" || g.Styles[2].Foreground != "#bbb" {
		t.Fatalf("table must keep the bridge's order aaa,bbb; got %+v", g.Styles)
	}
	if got := styleIDs(t, out, "row_spans"); got[0] != 2 {
		t.Fatalf("the span pointed at bbb, which is stable id 2, got %d", got[0])
	}
}

// The app fills blank cells with style 0 and reads the default background from
// it, so 0 is the one id whose meaning must survive canonicalisation -- even
// on a table cmux happened not to list in id order.
func TestTheDefaultStyleStaysAtIdZero(t *testing.T) {
	table := newStyleTable()
	outOfOrder := json.RawMessage(strings.Replace(string(gridWith([]string{"aaa"}, span(0, 1, "x"))),
		`{"id":0,"foreground":"#fff","background":"#000","bold":false},{"id":1,"foreground":"#aaa","bold":false}`,
		`{"id":1,"foreground":"#aaa","bold":false},{"id":0,"foreground":"#fff","background":"#000","bold":false}`, 1))
	out := table.canonicalise(outOfOrder)
	var g struct {
		Styles []struct {
			ID         int    `json:"id"`
			Background string `json:"background"`
		} `json:"styles"`
	}
	if err := json.Unmarshal(out, &g); err != nil {
		t.Fatal(err)
	}
	if g.Styles[0].ID != 0 || g.Styles[0].Background != "#000" {
		t.Fatalf("the default style must be id 0 in the emitted table, got %+v", g.Styles)
	}
}

// Fields this does not know about must survive: a cmux that adds one to its
// spans keeps working, at the cost of the slower path.
func TestUnknownSpanFieldsAreCopiedThrough(t *testing.T) {
	table := newStyleTable()
	grid := gridWith([]string{"aaa"}, `{"row":0,"column":0,"style_id":1,"text":"x","cell_width":1,"wide":true}`)
	out := table.canonicalise(grid)
	if !strings.Contains(string(out), `"wide":true`) {
		t.Fatalf("unknown span field was dropped: %s", out)
	}
}

// The typed path names cmux's fields but decoding does not insist on them,
// so a span missing one must fall to the copying path rather than be written
// back with fields cmux never sent -- or, for text, with no value at all.
func TestASpanMissingAKnownFieldIsCopiedNotInvented(t *testing.T) {
	for name, span := range map[string]string{
		"no text":   `{"row":0,"column":0,"style_id":1}`,
		"no row":    `{"column":0,"style_id":1,"text":"x"}`,
		"no column": `{"row":0,"style_id":1,"text":"x"}`,
	} {
		t.Run(name, func(t *testing.T) {
			table := newStyleTable()
			out := table.canonicalise(gridWith([]string{"aaa"}, span))
			if !json.Valid(out) {
				t.Fatalf("output is not JSON: %s", out)
			}
			var fields map[string]json.RawMessage
			if err := json.Unmarshal(out, &fields); err != nil {
				t.Fatal(err)
			}
			var spans []map[string]json.RawMessage
			if err := json.Unmarshal(fields["row_spans"], &spans); err != nil {
				t.Fatal(err)
			}
			var want map[string]json.RawMessage
			if err := json.Unmarshal([]byte(span), &want); err != nil {
				t.Fatal(err)
			}
			if len(spans) != 1 || len(spans[0]) != len(want) {
				t.Fatalf("span was not copied as sent: %s", fields["row_spans"])
			}
		})
	}
}

func TestUnknownStyleFieldsAreCopiedThrough(t *testing.T) {
	table := newStyleTable()
	grid := json.RawMessage(strings.Replace(string(gridWith([]string{"aaa"}, span(0, 0, "x"))),
		`"bold":false`, `"bold":false,"glow":9`, 1))
	if out := table.canonicalise(grid); !strings.Contains(string(out), `"glow":9`) {
		t.Fatalf("unknown style field was dropped: %s", out)
	}
}

// Whatever the reason, a bail-out means the grid goes out exactly as cmux
// produced it and the table forgets everything, so the next frame that CAN be
// canonicalised starts a fresh numbering the app will receive in full.
func TestABailOutPassesTheGridThroughAndResetsTheTable(t *testing.T) {
	base := gridWith([]string{"aaa", "bbb"}, span(0, 2, "x"))
	cases := map[string]json.RawMessage{
		"default style changed": json.RawMessage(strings.Replace(string(base), `"foreground":"#fff"`, `"foreground":"#eee"`, 1)),
		"no default style":      json.RawMessage(strings.Replace(string(base), `{"id":0,"foreground":"#fff","background":"#000","bold":false},`, ``, 1)),
		"undecodable grid":      json.RawMessage(`{"styles": [`),
		"no styles block":       json.RawMessage(`{"render_epoch":"E1","scrollback_rows":240,"cleared_rows":[],"scrolled_rows":0,"anchor":"viewport","active_screen":"primary","row_spans":[]}`),
		"span without a style":  gridWith([]string{"aaa"}, `{"row":0,"column":0,"text":"x"}`),
		"span with unknown id":  gridWith([]string{"aaa"}, span(0, 7, "x")),
		"style without an id":   json.RawMessage(strings.Replace(string(base), `"id":1,`, ``, 1)),
		"pane reset":            json.RawMessage(strings.Replace(string(base), `"render_epoch":"E1"`, `"render_epoch":"E2"`, 1)),
		"resize":                json.RawMessage(strings.Replace(string(base), `"scrollback_rows":240`, `"scrollback_rows":300`, 1)),
		"no epoch to compare":   json.RawMessage(strings.Replace(string(base), `"render_epoch":"E1",`, ``, 1)),
		"rows cleared in place": json.RawMessage(strings.Replace(string(base), `"cleared_rows":[]`, `"cleared_rows":[3]`, 1)),
		"viewport scrolled":     json.RawMessage(strings.Replace(string(base), `"scrolled_rows":0`, `"scrolled_rows":5`, 1)),
		"user panned up":        json.RawMessage(strings.Replace(string(base), `"anchor":"viewport"`, `"anchor":"history"`, 1)),
		"alternate screen":      json.RawMessage(strings.Replace(string(base), `"active_screen":"primary"`, `"active_screen":"alternate"`, 1)),
		"bail field missing":    json.RawMessage(strings.Replace(string(base), `"anchor":"viewport",`, ``, 1)),
	}
	for name, grid := range cases {
		t.Run(name, func(t *testing.T) {
			table := newStyleTable()
			table.canonicalise(base) // prime: default plus two styles, epoch E1
			if len(table.entries) != 3 {
				t.Fatalf("priming failed: %d entries", len(table.entries))
			}
			out := table.canonicalise(grid)
			if !bytes.Equal(out, grid) {
				t.Fatalf("a bail-out must pass the grid through untouched\n got: %s\nwant: %s", out, grid)
			}
			if len(table.entries) != 0 || len(table.ids) != 0 || table.primed {
				t.Fatalf("a bail-out must reset the table, got %d entries primed=%v", len(table.entries), table.primed)
			}
		})
	}
}

// After a bail-out the next good frame is canonicalised from scratch, not
// left as passthrough forever.
func TestCanonicalisationResumesAfterABailOut(t *testing.T) {
	table := newStyleTable()
	table.canonicalise(gridWith([]string{"aaa"}, span(0, 1, "x")))
	table.canonicalise(json.RawMessage(`{"styles": [`))
	out := table.canonicalise(gridWith([]string{"bbb", "aaa"}, span(0, 2, "x")))
	// Fresh table: bbb is 1, aaa is 2 -- the span pointed at aaa.
	if got := styleIDs(t, out, "row_spans"); got[0] != 2 {
		t.Fatalf("want a fresh numbering after the bail-out, got %d", got[0])
	}
}

// The first frame on a socket has nothing to compare its epoch against, so it
// records it rather than bailing -- otherwise no socket would ever start.
func TestTheFirstFrameIsCanonicalisedNotBailed(t *testing.T) {
	table := newStyleTable()
	out := table.canonicalise(gridWith([]string{"aaa"}, span(0, 1, "x")))
	if len(table.entries) != 2 {
		t.Fatal("first frame did not populate the table")
	}
	if !json.Valid(out) || !strings.Contains(string(out), `"styles":[{`) {
		t.Fatalf("first frame not canonicalised: %s", out)
	}
}

// cmux pretty-prints; both bail-out comparisons and output stability must not
// depend on its whitespace.
func TestPrettyPrintedInputIsHandled(t *testing.T) {
	table := newStyleTable()
	compact := gridWith([]string{"aaa"}, span(0, 1, "x"))
	var pretty bytes.Buffer
	if err := json.Indent(&pretty, compact, "", "  "); err != nil {
		t.Fatal(err)
	}
	a := table.canonicalise(compact)
	b := table.canonicalise(pretty.Bytes())
	if !bytes.Equal(a, b) {
		t.Fatalf("whitespace changed the output:\n%s\n%s", a, b)
	}
}

// The fast path and the fallback must agree byte for byte, or a frame that
// happens to take one and the next the other would look changed.
func TestTheTypedAndMapSpanPathsAgree(t *testing.T) {
	remap := map[int]int{3: 7}
	spans := json.RawMessage(`[{"row":1,"column":2,"style_id":3,"text":"aé","cell_width":1}]`)
	fast, ok := rewriteKnownSpans(spans, remap)
	if !ok {
		t.Fatal("fast path refused a known span")
	}
	slow, ok := rewriteAnySpans(spans, remap)
	if !ok {
		t.Fatal("map path failed")
	}
	if !bytes.Equal(fast, slow) {
		t.Fatalf("paths disagree:\n%s\n%s", fast, slow)
	}
}

// A realistic grid: ~2000 spans across 48 styles, the shape measured live.
// The budget agreed at review is 5ms per frame on the user's Mac; this keeps
// a regression from landing unnoticed.
func BenchmarkCanonicaliseARealisticGrid(b *testing.B) {
	styles := make([]string, 48)
	for i := range styles {
		styles[i] = fmt.Sprintf("%06x", i*0x1234)
	}
	spans := make([]string, 0, 2000)
	for i := range 2000 {
		spans = append(spans, span(i/8, i%49, "the quick brown fox jumps over"))
	}
	grid := gridWith(styles, spans...)
	table := newStyleTable()
	b.SetBytes(int64(len(grid)))
	b.ResetTimer()
	for range b.N {
		table.canonicalise(grid)
	}
}

// The fixture the app's StyleTableFixtureTest renders. Two frames as cmux
// would send them -- the second renumbered, its content the same -- and what
// the bridge makes of the second. The app must render the canonicalised frame
// exactly as it renders the original: the ids differ by design, so rendered
// output is the only invariant left, and it spans both languages (invariant 3).
//
// If this fails after a change here, regenerate the Kotlin constants from what
// it prints.
func TestTheCrossLanguageStyleFixtureIsUnchanged(t *testing.T) {
	first := gridWith([]string{"aaa", "bbb", "bold"}, span(0, 1, "ab"), span(1, 3, "cd"))
	second := gridWith([]string{"bbb", "bold", "aaa"}, span(0, 3, "ab"), span(1, 2, "cd"))
	table := newStyleTable()
	table.canonicalise(first)
	canonical := table.canonicalise(second)

	const want = `{"active_screen":"primary","anchor":"viewport","cleared_rows":[],"render_epoch":"E1",` +
		`"row_spans":[{"cell_width":1,"column":0,"row":0,"style_id":1,"text":"ab"},` +
		`{"cell_width":1,"column":0,"row":1,"style_id":3,"text":"cd"}],` +
		`"scrollback_rows":240,"scrollback_spans":[],"scrolled_rows":0,` +
		`"styles":[{"background":"#000","bold":false,"foreground":"#fff","id":0},` +
		`{"bold":false,"foreground":"#aaa","id":1},{"bold":false,"foreground":"#bbb","id":2},` +
		`{"bold":true,"foreground":"#bold","id":3}]}`
	if string(canonical) != want {
		t.Fatalf("fixture changed; regenerate the Kotlin constants from:\noriginal:  %s\ncanonical: %s", second, canonical)
	}
}
