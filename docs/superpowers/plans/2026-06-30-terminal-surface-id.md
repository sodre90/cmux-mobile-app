# Terminal Surface ID Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the agent-session "Terminal disconnected" by giving the app real terminal-surface ids: `/sessions` returns workspaces with inline terminal panes, and the app shows a workspace list that expands (accordion) into its panes.

**Architecture:** The bridge's `mobile.workspace.list` already nests a `terminals[]` array (each a streamable surface) inside each workspace. The bridge stops flattening workspaces and panes into one ambiguous list and instead returns workspaces each carrying their panes. The app renders workspace cards; a single-pane workspace opens its pane directly, a multi-pane workspace expands inline; tapping a pane opens `/terminal/{pane.id}`.

**Tech Stack:** Go 1.26 (bridge, `github.com/sodre90/cmux-bridge`), Kotlin + Jetpack Compose (Android app, `com.sodre90.cmuxremote`). App unit tests are pure JVM (JUnit4 + okhttp MockWebServer + kotlinx.serialization), run via Gradle; no Compose UI test harness.

## Global Constraints

- All commits authored solely by the human developer (`sodre90 <erdos.peter.bme@gmail.com>`). NEVER add `Co-Authored-By: Claude` or any AI attribution trailer.
- Bridge tests: `cd bridge && go test ./internal/server/` ; build: `go build ./...`.
- App tests/compile: `cd android && JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest`.
- Envelope key for `GET /sessions` is `workspaces` (was `sessions`). The route path stays `/sessions`.
- `classifyKind` is cosmetic only (a pane badge); it must not gate streamability.
- Keep `cleanTitle`, `classifyKind`, `stringField`, `firstString`, `isTitleRune`, `writeJSON` unchanged.
- YAGNI: do not add sorting, pinning, group rendering, or a separate panes endpoint.

---

### Task 1: Bridge — workspaces with inline terminal panes

**Files:**
- Modify: `bridge/internal/server/sessions.go` (replace `Session` type + `parseSessions`; update `handleSessions`)
- Test: `bridge/internal/server/sessions_test.go` (rewrite fixture + shape assertions)

**Interfaces:**
- Consumes: existing helpers `cleanTitle(string) string`, `classifyKind(string) string`, `stringField(map[string]any,string) (string,bool)`, `firstString(map[string]any,...string) string`, `writeJSON(http.ResponseWriter,int,any)`.
- Produces (relied on by Task 2 via the HTTP contract):
  - `GET /sessions` → `200 {"workspaces":[Workspace,...]}`.
  - `type Workspace struct { ID, CWD, Title, Preview string; HasUnread bool; Terminals []TerminalPane }` with JSON tags `id, cwd, title, preview, has_unread, terminals`.
  - `type TerminalPane struct { ID, CWD, Title string; Focused, Ready bool; Kind string }` with JSON tags `id, cwd, title, focused, ready, kind`.

- [ ] **Step 1: Rewrite the bridge test fixture and shape assertions (failing)**

Replace the `fakeWorkspaceList` const and the `TestSessionsDedupAndShape` test in `bridge/internal/server/sessions_test.go` with the versions below. Leave `TestSessionsRequiresToken`, `TestClassifyKind`, and `TestCleanTitleStripsGlyph` untouched.

```go
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd bridge && go test ./internal/server/ -run TestSessionsDedupAndShape`
Expected: FAIL — compile error (`undefined: Workspace`) because the type doesn't exist yet.

- [ ] **Step 3: Replace the `Session` type and `parseSessions` in `sessions.go`**

In `bridge/internal/server/sessions.go`, replace the `Session` struct (lines 10-17) with the two structs below:

```go
// Workspace is the app-facing representation of a cmux workspace and its
// terminal surfaces (panes). Each pane's ID is a streamable terminal-surface id
// the app opens via /terminal/{id}.
type Workspace struct {
	ID        string         `json:"id"`
	CWD       string         `json:"cwd"`
	Title     string         `json:"title"`
	Preview   string         `json:"preview"`
	HasUnread bool           `json:"has_unread"`
	Terminals []TerminalPane `json:"terminals"`
}

// TerminalPane is one terminal surface within a workspace. ID is the cmux
// terminal-surface id; Kind is a cosmetic badge derived from the title.
type TerminalPane struct {
	ID      string `json:"id"`
	CWD     string `json:"cwd"`
	Title   string `json:"title"`
	Focused bool   `json:"focused"`
	Ready   bool   `json:"ready"`
	Kind    string `json:"kind"`
}
```

Replace `handleSessions` (the body that calls `parseSessions` and writes `"sessions"`) so it reads:

```go
func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	raw, err := s.cmux.Rpc(r.Context(), "mobile.workspace.list", nil)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "cmux unavailable"})
		return
	}
	workspaces, err := parseWorkspaces(raw)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "cmux parse error"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"workspaces": workspaces})
}
```

Replace the entire `parseSessions` function with `parseWorkspaces` + `parsePanes`:

```go
// parseWorkspaces normalizes a mobile.workspace.list payload into Workspaces.
// cmux nests workspace objects under several keys (top-level "workspaces" and
// inside "groups"), so we walk the whole tree and collect any object that
// carries a "terminals" array — that array is what distinguishes a workspace
// from its nested terminal surfaces, which fixes the old flattening that swept
// panes in as their own (unstreamable) entries. Deduped by id (first wins).
func parseWorkspaces(raw []byte) ([]Workspace, error) {
	var root any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	out := []Workspace{}
	var walk func(any)
	walk = func(node any) {
		switch v := node.(type) {
		case map[string]any:
			id, hasID := stringField(v, "id")
			terms, hasTerms := v["terminals"].([]any)
			if hasID && hasTerms && !seen[id] {
				seen[id] = true
				cwd, _ := stringField(v, "current_directory")
				hasUnread, _ := v["has_unread"].(bool)
				out = append(out, Workspace{
					ID:        id,
					CWD:       cwd,
					Title:     cleanTitle(firstString(v, "title")),
					Preview:   firstString(v, "preview"),
					HasUnread: hasUnread,
					Terminals: parsePanes(terms),
				})
			}
			for _, child := range v {
				walk(child)
			}
		case []any:
			for _, child := range v {
				walk(child)
			}
		}
	}
	walk(root)
	return out, nil
}

// parsePanes maps a workspace's "terminals" array into TerminalPanes. Panes
// without an id are skipped; an empty array yields an empty (non-nil) slice.
func parsePanes(terms []any) []TerminalPane {
	panes := []TerminalPane{}
	for _, t := range terms {
		m, ok := t.(map[string]any)
		if !ok {
			continue
		}
		id, hasID := stringField(m, "id")
		if !hasID {
			continue
		}
		cwd, _ := stringField(m, "current_directory")
		title := cleanTitle(firstString(m, "title"))
		focused, _ := m["is_focused"].(bool)
		ready, _ := m["is_ready"].(bool)
		panes = append(panes, TerminalPane{
			ID:      id,
			CWD:     cwd,
			Title:   title,
			Focused: focused,
			Ready:   ready,
			Kind:    classifyKind(title),
		})
	}
	return panes
}
```

- [ ] **Step 4: Run the bridge tests to verify they pass**

Run: `cd bridge && go test ./internal/server/`
Expected: PASS (all tests, including `TestSessionsDedupAndShape`, `TestClassifyKind`, `TestCleanTitleStripsGlyph`, `TestSessionsRequiresToken`, and the terminal handler tests).

- [ ] **Step 5: Verify the whole module builds**

Run: `cd bridge && go build ./... && go vet ./internal/server/`
Expected: no output (success).

- [ ] **Step 6: Commit**

```bash
cd /Users/perdos/prj/cmux-app
git add bridge/internal/server/sessions.go bridge/internal/server/sessions_test.go
git commit -m "Return workspaces with inline terminal panes from /sessions"
```

---

### Task 2: App — workspace list with accordion drill-in to panes

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/model/Dtos.kt` (replace `Session`/`SessionsResponse` with `Workspace`/`TerminalPane`/`WorkspacesResponse`)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/BridgeClient.kt` (`sessions()` → `List<Workspace>`)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsViewModel.kt` (`List<Workspace>`)
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsLogic.kt` (pure helpers)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsScreen.kt` (workspace cards + accordion + pane rows; `onOpenTerminal: (String) -> Unit`)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt` (`onOpenTerminal` passes a surface id)
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/model/DtosTest.kt` (replace the Session parse test)
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/BridgeClientTest.kt` (new envelope shape)
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/ui/sessions/SessionsLogicTest.kt` (new — tap-decision + label)

**Interfaces:**
- Consumes (from Task 1, via the HTTP contract): `GET /sessions` → `{"workspaces":[{id,cwd,title,preview,has_unread,terminals:[{id,cwd,title,focused,ready,kind}]}]}`.
- Produces:
  - `data class Workspace(id, cwd, title, preview, hasUnread, terminals: List<TerminalPane>)`
  - `data class TerminalPane(id, cwd, title, focused, ready, kind)`
  - `fun singlePaneTarget(ws: Workspace): String?` — pane id to open directly, or null to expand.
  - `fun paneCountLabel(count: Int): String`
  - `SessionsScreen(..., onOpenTerminal: (String) -> Unit, ...)`

- [ ] **Step 1: Write the failing DTO + logic tests**

Replace the `parsesSessionWithSnakeCaseAndUnknownKeys` test in `DtosTest.kt` with this workspace-shape test (keep the other four tests):

```kotlin
@Test
fun parsesWorkspaceWithInlinePanesAndUnknownKeys() {
    val js = """
        {"id":"ws-1","cwd":"/Users/p/proj","title":"build","preview":"Claude is waiting",
         "has_unread":true,"future_field":42,
         "terminals":[
           {"id":"t-1","cwd":"/Users/p/proj","title":"build","focused":true,"ready":true,"kind":"agent"},
           {"id":"t-2","cwd":"/Users/p/proj","title":"~/proj","focused":false,"ready":false,"kind":"terminal"}
         ]}
    """.trimIndent()
    val w = BridgeJson.decodeFromString(Workspace.serializer(), js)
    assertEquals("ws-1", w.id)
    assertEquals("/Users/p/proj", w.cwd)
    assertEquals("Claude is waiting", w.preview)
    assertTrue(w.hasUnread)
    assertEquals(2, w.terminals.size)
    assertEquals("t-1", w.terminals[0].id)
    assertTrue(w.terminals[0].focused)
    assertEquals("terminal", w.terminals[1].kind)
}

@Test
fun parsesWorkspaceWithMissingOptionalFields() {
    val w = BridgeJson.decodeFromString(Workspace.serializer(), """{"id":"ws-2"}""")
    assertEquals("ws-2", w.id)
    assertEquals("", w.preview)
    assertFalse(w.hasUnread)
    assertTrue(w.terminals.isEmpty())
}
```

Create `android/app/src/test/java/com/sodre90/cmuxremote/ui/sessions/SessionsLogicTest.kt`:

```kotlin
package com.sodre90.cmuxremote.ui.sessions

import com.sodre90.cmuxremote.model.TerminalPane
import com.sodre90.cmuxremote.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionsLogicTest {

    private fun ws(vararg paneIds: String) =
        Workspace(id = "w", terminals = paneIds.map { TerminalPane(id = it) })

    @Test
    fun singlePaneWorkspaceTargetsThatPane() {
        assertEquals("p1", singlePaneTarget(ws("p1")))
    }

    @Test
    fun multiPaneWorkspaceTargetsNull() {
        assertNull(singlePaneTarget(ws("p1", "p2")))
    }

    @Test
    fun zeroPaneWorkspaceTargetsNull() {
        assertNull(singlePaneTarget(ws()))
    }

    @Test
    fun paneCountLabelIsSingularOrPlural() {
        assertEquals("0 panes", paneCountLabel(0))
        assertEquals("1 pane", paneCountLabel(1))
        assertEquals("3 panes", paneCountLabel(3))
    }
}
```

Also replace `sessionsDecodesEnvelopeAndSendsBearer` in `BridgeClientTest.kt` with the new-envelope version (the other three tests — `nonSuccessThrowsBridgeException`, `replyFeedPostsToFeedPathWithBody`, `registerDevicePostsFcmToken` — are unchanged):

```kotlin
@Test
fun sessionsDecodesEnvelopeAndSendsBearer() {
    server.enqueue(
        MockResponse().setBody(
            """{"workspaces":[{"id":"a","cwd":"/x","title":"build","preview":"Claude is waiting","has_unread":true,
                "terminals":[{"id":"t-a","cwd":"/x","title":"build","focused":true,"ready":true,"kind":"agent"}]}]}""",
        ),
    )

    val list = runBlocking { client.sessions() }

    assertEquals(1, list.size)
    assertEquals("a", list[0].id)
    assertTrue(list[0].hasUnread)
    assertEquals(1, list[0].terminals.size)
    assertEquals("t-a", list[0].terminals[0].id)

    val req = server.takeRequest()
    assertEquals("GET", req.method)
    assertEquals("/sessions", req.path)
    assertEquals("Bearer tok-7", req.getHeader("Authorization"))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest`
Expected: FAIL — compile errors (`Workspace`, `TerminalPane`, `singlePaneTarget`, `paneCountLabel` unresolved).

- [ ] **Step 3: Replace the DTOs in `Dtos.kt`**

In `model/Dtos.kt`, replace the `Session` and `SessionsResponse` declarations (lines 21-33) with:

```kotlin
/** A cmux workspace as surfaced by the bridge `GET /sessions` endpoint. */
@Serializable
data class Workspace(
    val id: String = "",
    val cwd: String = "",
    val title: String = "",
    val preview: String = "",
    @SerialName("has_unread") val hasUnread: Boolean = false,
    val terminals: List<TerminalPane> = emptyList(),
)

/** One terminal surface (pane) within a [Workspace]; [id] opens via /terminal/{id}. */
@Serializable
data class TerminalPane(
    val id: String = "",
    val cwd: String = "",
    val title: String = "",
    val focused: Boolean = false,
    val ready: Boolean = false,
    val kind: String = "",
)

/** Envelope returned by `GET /sessions`. */
@Serializable
data class WorkspacesResponse(val workspaces: List<Workspace> = emptyList())
```

- [ ] **Step 4: Update `BridgeClient.sessions()`**

In `data/BridgeClient.kt`, change the imports `com.sodre90.cmuxremote.model.Session` and `com.sodre90.cmuxremote.model.SessionsResponse` to `com.sodre90.cmuxremote.model.Workspace` and `com.sodre90.cmuxremote.model.WorkspacesResponse`, and replace `sessions()`:

```kotlin
suspend fun sessions(): List<Workspace> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("$root/sessions").get().build()
    http.newCall(request).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw BridgeException(resp.code, body)
        BridgeJson.decodeFromString(WorkspacesResponse.serializer(), body).workspaces
    }
}
```

- [ ] **Step 5: Update `SessionsViewModel`**

In `ui/sessions/SessionsViewModel.kt`, change the import `com.sodre90.cmuxremote.model.Session` to `com.sodre90.cmuxremote.model.Workspace` and both `UiState<List<Session>>` occurrences to `UiState<List<Workspace>>`. No other changes (the body already calls `client.sessions()`).

- [ ] **Step 6: Create the pure helpers `SessionsLogic.kt`**

Create `ui/sessions/SessionsLogic.kt`:

```kotlin
package com.sodre90.cmuxremote.ui.sessions

import com.sodre90.cmuxremote.model.Workspace

/**
 * The surface id to open directly when a workspace card is tapped, or null when
 * the card should expand to show its panes instead. Exactly one pane → open it
 * directly; zero or many panes → null (zero has nothing to open, many expands).
 */
fun singlePaneTarget(ws: Workspace): String? =
    if (ws.terminals.size == 1) ws.terminals[0].id else null

/** Trailing pane-count label, e.g. "0 panes" / "1 pane" / "3 panes". */
fun paneCountLabel(count: Int): String =
    if (count == 1) "1 pane" else "$count panes"
```

- [ ] **Step 7: Rewrite `SessionsScreen.kt` (workspace cards + accordion + panes)**

Replace the whole file `ui/sessions/SessionsScreen.kt` with:

```kotlin
package com.sodre90.cmuxremote.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.model.TerminalPane
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    vm: SessionsViewModel,
    onOpenTerminal: (String) -> Unit,
    onOpenInbox: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("cmux sessions") },
                actions = {
                    TextButton(onClick = onOpenInbox) { Text("Inbox") }
                    TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Error -> Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is UiState.Ready -> WorkspaceList(s.data, onOpenTerminal)
            }
        }
    }
}

@Composable
private fun WorkspaceList(workspaces: List<Workspace>, onOpen: (String) -> Unit) {
    if (workspaces.isEmpty()) {
        Box(Modifier.fillMaxSize()) { Text("No sessions", Modifier.align(Alignment.Center)) }
        return
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(workspaces, key = { it.id }) { ws ->
            WorkspaceCard(
                ws = ws,
                expanded = expanded[ws.id] == true,
                onToggle = { expanded[ws.id] = !(expanded[ws.id] ?: false) },
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun WorkspaceCard(
    ws: Workspace,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = ws.terminals.isNotEmpty()) {
                        val direct = singlePaneTarget(ws)
                        if (direct != null) onOpen(direct) else onToggle()
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ws.preview.ifBlank { ws.title.ifBlank { ws.cwd } },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ws.cwd,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (ws.hasUnread) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                        modifier = Modifier.size(10.dp),
                    ) {}
                }
                Text(
                    text = paneCountLabel(ws.terminals.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                ws.terminals.forEach { pane -> PaneRow(pane, onOpen) }
            }
        }
    }
}

@Composable
private fun PaneRow(pane: TerminalPane, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onOpen(pane.id) }
            .padding(start = 28.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = pane.title.ifBlank { pane.cwd },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!pane.ready) {
            Text(
                text = "starting…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KindBadge(pane.kind)
        if (pane.focused) {
            Text(
                text = "focus",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun KindBadge(kind: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = kind.ifBlank { "?" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
```

- [ ] **Step 8: Update `CmuxNavHost.kt` terminal wiring**

In `ui/CmuxNavHost.kt`, replace the `onOpenTerminal` argument in the `Routes.SESSIONS` composable (lines 58-60) with:

```kotlin
                // The pane's surface id is passed through to /terminal/{id} as
                // the cmux terminal-surface id (see bridge handleTerminal).
                onOpenTerminal = { surfaceId -> navController.navigate(Routes.terminal(surfaceId)) },
```

- [ ] **Step 9: Run the app unit tests to verify they pass**

Run: `cd android && JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest`
Expected: PASS (compiles the full app + runs `DtosTest`, `BridgeClientTest`, `SessionsLogicTest`, and the existing suites).

- [ ] **Step 10: Commit**

```bash
cd /Users/perdos/prj/cmux-app
git add android/app/src/main/java/com/sodre90/cmuxremote/model/Dtos.kt \
        android/app/src/main/java/com/sodre90/cmuxremote/data/BridgeClient.kt \
        android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsViewModel.kt \
        android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsLogic.kt \
        android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsScreen.kt \
        android/app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt \
        android/app/src/test/java/com/sodre90/cmuxremote/model/DtosTest.kt \
        android/app/src/test/java/com/sodre90/cmuxremote/data/BridgeClientTest.kt \
        android/app/src/test/java/com/sodre90/cmuxremote/ui/sessions/SessionsLogicTest.kt
git commit -m "Show workspace list with accordion drill-in to terminal panes"
```

---

## Self-Review

**1. Spec coverage:**
- New `/sessions` `{"workspaces":[...]}` shape with inline panes → Task 1 Steps 3-4. ✓
- `parseSessions` rewrite to "objects with a `terminals` array" + groups nesting + dedup → Task 1 Step 3, asserted in Step 1's test. ✓
- `classifyKind` per-pane cosmetic → Task 1 `parsePanes` sets `Kind`; not used for streamability. ✓
- App DTOs `Workspace`/`TerminalPane`/`WorkspacesResponse` → Task 2 Step 3. ✓
- `BridgeClient`/`SessionsViewModel` to `List<Workspace>` → Task 2 Steps 4-5. ✓
- Workspace card (preview→title→cwd, unread dot, pane count) → Task 2 Step 7. ✓
- Single-pane opens directly; multi-pane accordion; pane rows; `onOpenTerminal(String)` → Task 2 Steps 6-8. ✓
- Edge cases: zero-pane non-tappable (`clickable(enabled = …)`), not-ready "starting…" hint, empty pane slice → Task 2 Step 7 + Task 1 `parsePanes`. ✓
- Testing: bridge shape/dedup/groups/empty; app DTO parse, client envelope, tap-decision → both tasks' tests. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to". Every code step shows full code. ✓

**3. Type consistency:** `Workspace`/`TerminalPane` JSON tags (`has_unread`, `is_focused`→`focused`, `is_ready`→`ready`) match between bridge structs and app DTOs; `singlePaneTarget`/`paneCountLabel` signatures identical across `SessionsLogic.kt`, its test, and `SessionsScreen.kt`; `onOpenTerminal: (String) -> Unit` consistent in `SessionsScreen.kt` and `CmuxNavHost.kt`. ✓
```
