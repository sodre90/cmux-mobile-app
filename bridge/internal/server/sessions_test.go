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

// realistic-shaped mobile.workspace.list payload: duplicates + nested groups +
// a shell-prompt terminal surface.
const fakeWorkspaceList = `{
  "groups": [
    {"workspaces": [
      {"id":"882CA6F0","current_directory":"/Users/u/prj/trading","preview":"Build options trading system"}
    ]}
  ],
  "workspaces": [
    {"id":"882CA6F0","current_directory":"/Users/u/prj/trading","preview":"Build options trading system"},
    {"id":"E43BBF04","current_directory":"/Users/u/prj/trading","preview":"u@host:~/prj/trading"}
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
		Sessions []Session `json:"sessions"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Sessions) != 2 {
		t.Fatalf("want 2 deduped sessions, got %d: %+v", len(body.Sessions), body.Sessions)
	}
	byID := map[string]Session{}
	for _, s := range body.Sessions {
		byID[s.ID] = s
	}
	agent, ok := byID["882CA6F0"]
	if !ok || agent.Kind != "agent" || agent.CWD != "/Users/u/prj/trading" {
		t.Fatalf("agent session wrong: %+v", agent)
	}
	if agent.Title != "Build options trading system" {
		t.Fatalf("agent title wrong: %q", agent.Title)
	}
	term, ok := byID["E43BBF04"]
	if !ok || term.Kind != "terminal" {
		t.Fatalf("terminal session wrong: %+v", term)
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

func TestCleanTitleStripsGlyph(t *testing.T) {
	if got := cleanTitle("⠂ Build price comparison"); got != "Build price comparison" {
		t.Fatalf("cleanTitle glyph strip failed: %q", got)
	}
	if got := cleanTitle("Plain title"); got != "Plain title" {
		t.Fatalf("cleanTitle altered plain title: %q", got)
	}
}
