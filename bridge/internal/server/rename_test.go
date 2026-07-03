package server

import (
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

const fakeRenameScript = `#!/bin/sh
printf '%s\n' "$*" >> "$CMUX_FAKE_LOG"
echo '{"ok":true}'
`

func postRename(t *testing.T, srvURL, tok, id, body string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("POST", srvURL+"/sessions/"+id+"/rename", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestRenameWorkspaceCallsCmuxRpc(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeRenameScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postRename(t, srv.URL, tok, "882CA6F0", `{"title":"Trading bot"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	data, _ := os.ReadFile(logPath)
	log := string(data)
	for _, want := range []string{"workspace.rename", "882CA6F0", "Trading bot"} {
		if !strings.Contains(log, want) {
			t.Fatalf("log missing %q; got:\n%s", want, log)
		}
	}
}

func TestRenameWorkspaceMissingTitle400(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	s, tok := newTestServer(t, fakeRenameScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postRename(t, srv.URL, tok, "882CA6F0", `{"title":""}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for missing title, got %d", resp.StatusCode)
	}
	if data, _ := os.ReadFile(logPath); len(data) != 0 {
		t.Fatalf("cmux must not be called on validation failure; log:\n%s", data)
	}
}

func TestRenameWorkspaceInvalidJson400(t *testing.T) {
	s, tok := newTestServer(t, fakeRenameScript)
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postRename(t, srv.URL, tok, "882CA6F0", `not json`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for invalid json, got %d", resp.StatusCode)
	}
}

func TestRenameWorkspaceCmuxFailure502(t *testing.T) {
	s, tok := newTestServer(t, "#!/bin/sh\nexit 1\n")
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp := postRename(t, srv.URL, tok, "882CA6F0", `{"title":"New name"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("want 502 when cmux rpc fails, got %d", resp.StatusCode)
	}
}
