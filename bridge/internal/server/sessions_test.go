package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/testutil"
)

// realistic-shaped mobile.workspace.list payload: a workspace duplicated across
// groups + top-level, a multi-pane workspace, and an empty-pane workspace.
// Each pane object also has id + current_directory but NO terminals array, so
// only the workspaces must be collected.
const fakeWorkspaceList = `{
  "groups": [
    {"workspaces": [
      {"id":"882CA6F0","current_directory":"/Users/u/prj/trading","preview":"Build options trading system","title":"✳ Build options","has_unread":true,
       "terminals":[{"id":"T1","current_directory":"/Users/u/prj/trading","title":"✳ Build options","is_focused":true,"is_ready":true}]}
    ]}
  ],
  "workspaces": [
    {"id":"882CA6F0","current_directory":"/Users/u/prj/trading","preview":"Build options trading system","title":"✳ Build options","has_unread":true,
     "terminals":[{"id":"T1","current_directory":"/Users/u/prj/trading","title":"✳ Build options","is_focused":true,"is_ready":true}]},
    {"id":"E43BBF04","current_directory":"/Users/u/prj/trading","preview":"shell","title":"~/prj/trading","has_unread":false,
     "terminals":[
       {"id":"T2","current_directory":"/Users/u/prj/trading","title":"~/prj/trading","is_focused":true,"is_ready":true},
       {"id":"T3","current_directory":"/Users/u/prj/trading","title":"✳ Review PR","is_focused":false,"is_ready":false}
     ]},
    {"id":"EMPTY01","current_directory":"/Users/u/prj/x","preview":"empty","title":"~/prj/x","terminals":[]}
  ]
}`

func newTestServer(t *testing.T, script string) (*Server, string) {
	t.Helper()
	bin := testutil.WriteFakeCmux(t, script)
	store, err := auth.Open(t.TempDir() + "/d.json")
	if err != nil {
		t.Fatal(err)
	}
	tok, _ := store.Issue("phone")
	s := New(config.Config{}, &cmux.Client{Bin: bin}, store)
	return s, tok
}

func TestSessionsRequiresToken(t *testing.T) {
	s, _ := newTestServer(t, "#!/bin/sh\necho '{}'\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/sessions")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 without token, got %d", resp.StatusCode)
	}
}

func TestSessionsDedupAndShape(t *testing.T) {
	script := "#!/bin/sh\ncat <<'JSON'\n" + fakeWorkspaceList + "\nJSON\n"
	s, tok := newTestServer(t, script)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/sessions", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var body struct {
		Workspaces []Workspace `json:"workspaces"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Workspaces) != 3 {
		t.Fatalf("want 3 deduped workspaces, got %d: %+v", len(body.Workspaces), body.Workspaces)
	}
	byID := map[string]Workspace{}
	for _, w := range body.Workspaces {
		byID[w.ID] = w
	}
	// Panes must NOT leak as top-level workspaces.
	for _, leaked := range []string{"T1", "T2", "T3"} {
		if _, bad := byID[leaked]; bad {
			t.Fatalf("pane %q leaked as a workspace", leaked)
		}
	}
	ws, ok := byID["882CA6F0"]
	if !ok {
		t.Fatal("missing workspace 882CA6F0")
	}
	if ws.CWD != "/Users/u/prj/trading" || ws.Title != "Build options" ||
		ws.Preview != "Build options trading system" || !ws.HasUnread {
		t.Fatalf("workspace 882CA6F0 fields wrong: %+v", ws)
	}
	if len(ws.Terminals) != 1 {
		t.Fatalf("882CA6F0 want 1 pane, got %d", len(ws.Terminals))
	}
	p := ws.Terminals[0]
	if p.ID != "T1" || !p.Focused || !p.Ready || p.Kind != "agent" || p.Title != "Build options" {
		t.Fatalf("882CA6F0 pane wrong: %+v", p)
	}
	multi := byID["E43BBF04"]
	if len(multi.Terminals) != 2 {
		t.Fatalf("E43BBF04 want 2 panes, got %d", len(multi.Terminals))
	}
	if multi.Terminals[0].Kind != "terminal" || multi.Terminals[1].Kind != "agent" {
		t.Fatalf("E43BBF04 pane kinds wrong: %+v", multi.Terminals)
	}
	if multi.Terminals[1].Focused || multi.Terminals[1].Ready {
		t.Fatalf("E43BBF04 second pane should be unfocused+not-ready: %+v", multi.Terminals[1])
	}
	if empty := byID["EMPTY01"]; len(empty.Terminals) != 0 {
		t.Fatalf("EMPTY01 want 0 panes, got %d", len(empty.Terminals))
	}
}

func TestClassifyKind(t *testing.T) {
	cases := map[string]string{
		"Build options trading system": "agent",
		"~/prj/log-search":             "terminal",
		"u@host:~/prj/trading":         "terminal",
		"/Users/u/prj/x":               "terminal",
	}
	for title, want := range cases {
		if got := classifyKind(title); got != want {
			t.Errorf("classifyKind(%q)=%q want %q", title, got, want)
		}
	}
}

func TestClassifyAttention(t *testing.T) {
	cases := map[string]string{
		"Claude needs your permission":     "permission",
		"Claude is waiting for your input": "input",
		"CODEX NEEDS YOUR PERMISSION":      "permission", // case-insensitive
		"All done. Summary of the work…":   "",
		"":                                 "",
	}
	for preview, want := range cases {
		if got := classifyAttention(preview); got != want {
			t.Errorf("classifyAttention(%q)=%q want %q", preview, got, want)
		}
	}
}

func TestCleanTitleStripsGlyph(t *testing.T) {
	if got := cleanTitle("⠂ Build price comparison"); got != "Build price comparison" {
		t.Fatalf("cleanTitle glyph strip failed: %q", got)
	}
	if got := cleanTitle("Plain title"); got != "Plain title" {
		t.Fatalf("cleanTitle altered plain title: %q", got)
	}
}
