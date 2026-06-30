# Terminal Surface ID Fix — Design

**Date:** 2026-06-30
**Status:** Approved (ready for implementation plan)

## Problem

Opening an agent-type session from the Android app shows "Terminal disconnected"
with no frame ever arriving. The `/terminal/{id}` WebSocket upgrades successfully,
then the Mac bridge agent closes the stream ~150ms later.

Root cause (confirmed via agent-side instrumentation, commit `5ac509c`):

```
terminal 26420687-…: initial replay failed after 150.65ms:
  cmux rpc mobile.terminal.replay: exit status 1:
  Error: not_found: Terminal surface not found
```

The app opens `/terminal/{id}` using the **workspace id**, but
`mobile.terminal.replay` only accepts a **terminal-surface id**. cmux's
`mobile.workspace.list` returns workspaces that each have their own `id` *and* a
nested `terminals[]` array; every terminal is a separate surface with its own
streamable `id`.

The bridge's `parseSessions` (`bridge/internal/server/sessions.go`) walks the
whole payload and collects **every** object carrying `id` + `current_directory`
— both workspaces *and* their nested terminals — into one flat list, then labels
each by a fragile title heuristic (`classifyKind`). Consequences:

1. Cards backed by a **workspace id** cannot stream (`not_found`); cards backed
   by a **terminal-surface id** can. The agent/terminal badge does not track
   which ids are actually streamable.
2. The list shows duplicates — a workspace card *and* each of its terminal cards.

## Goal

Make every streamable id the app uses a real terminal-surface id, and present a
faithful workspace → panes model. A `log-search` workspace, for example, has
three panes (two agent panes + one shell); the user wants per-pane access.

## Decisions

- **Card model:** hybrid — a workspace list that expands into its terminal panes.
- **Data flow:** terminals are returned **inline** in `/sessions` (Approach A) —
  no separate panes endpoint. One request, one loading state.
- **Drill-in UX:** in-place **accordion**. A multi-pane workspace expands its
  panes inline; a single-pane workspace opens directly on tap.
- **`classifyKind`:** survives but moves **per-pane and becomes purely cosmetic**
  (a badge). It no longer decides streamability, because every pane id is a real
  terminal surface.

## Architecture

### Bridge: `/sessions` response (envelope renamed `sessions` → `workspaces`)

```json
{
  "workspaces": [
    {
      "id": "26420687-…",
      "cwd": "/Users/perdos/prj/log-search",
      "title": "Add .gitignore and push to branch",
      "preview": "Claude is waiting for your input",
      "has_unread": true,
      "terminals": [
        { "id": "5E7DAC84-…", "cwd": "…", "title": "Add .gitignore and push to branch", "focused": true,  "ready": true, "kind": "agent" },
        { "id": "E74AC2C1-…", "cwd": "…", "title": "Review log-search PR comments",     "focused": false, "ready": true, "kind": "agent" },
        { "id": "3A2E0077-…", "cwd": "…", "title": "~/prj/log-search",                   "focused": false, "ready": true, "kind": "terminal" }
      ]
    }
  ]
}
```

**Workspace fields kept:** `id`, `cwd` (from `current_directory`), `title`
(cleaned), `preview`, `has_unread`, `terminals`.
**Dropped as YAGNI:** `group_id`, `is_pinned`, `is_selected`, `last_activity_at`,
`preview_at`, `window_id`.
**Pane fields:** `id`, `cwd` (from `current_directory`), `title` (cleaned),
`focused` (from `is_focused`), `ready` (from `is_ready`), `kind` (from
`classifyKind(title)`).

### Bridge: `parseSessions` rewrite (the core fix)

Replace "collect every object with `id` + `current_directory`" with **collect
only objects that have a `terminals` array** — i.e. genuine workspaces. This:

- drops terminal sub-objects from the top level (kills the duplicates),
- still handles workspaces nested under `groups[]` (the defensive tree-walk
  stays; `groups` is empty now but the shape allows nesting),
- maps each workspace's `terminals[]` to pane objects, each carrying its own
  streamable surface id.

Dedup workspaces by id (first occurrence wins). A workspace with a missing or
empty `terminals` key yields an empty pane list. `cleanTitle` (strip leading
status glyph) applies to both workspace title and each pane title.

### App: DTOs (`model/Dtos.kt`)

Replace `Session`/`SessionsResponse` with:

```kotlin
@Serializable
data class Workspace(
    val id: String = "",
    val cwd: String = "",
    val title: String = "",
    val preview: String = "",
    @SerialName("has_unread") val hasUnread: Boolean = false,
    val terminals: List<TerminalPane> = emptyList(),
)

@Serializable
data class TerminalPane(
    val id: String = "",
    val cwd: String = "",
    val title: String = "",
    val focused: Boolean = false,
    val ready: Boolean = false,
    val kind: String = "",
)

@Serializable
data class WorkspacesResponse(val workspaces: List<Workspace> = emptyList())
```

### App: data + UI

- `data/BridgeClient.kt` — `sessions()` decodes `WorkspacesResponse` and returns
  `List<Workspace>`.
- `ui/sessions/SessionsViewModel.kt` — state holds `List<Workspace>` (file/class
  names unchanged).
- `ui/sessions/SessionsScreen.kt`:
  - Workspace card: primary line = `preview` (fallback `title`, then `cwd`),
    secondary = `cwd`, trailing = unread dot + pane count.
  - Tap behavior: exactly one pane → open `terminal/{pane.id}` directly; more
    than one → toggle an inline accordion listing the panes.
  - Pane row: cleaned title, cosmetic kind badge, focused marker; tap opens
    `terminal/{pane.id}`.
  - Accordion expansion state: a set keyed by workspace id, held in the screen;
    resets on refresh.
  - `onOpenTerminal` changes from `(Session) -> Unit` to `(String) -> Unit`
    (a surface id), since the screen now selects a specific pane.
- `ui/CmuxNavHost.kt` — `onOpenTerminal = { surfaceId -> navController.navigate(Routes.terminal(surfaceId)) }`.

The Inbox path (`AttentionItem`, events-driven) is independent and unaffected.

## Error handling & edge cases

- **Zero-pane workspace:** render the card but non-expandable/non-tappable
  (shows "0 panes"); never navigate to a dead id.
- **Pane `ready: false`:** still tappable; show a subtle "starting…" hint. The
  terminal screen already handles a not-yet-ready surface via its connect/replay
  path (which now logs the reason).
- **Surface dies between list-fetch and tap:** the terminal screen already shows
  "Terminal disconnected" + Reconnect — unchanged, and now rare since ids are
  valid.
- **Empty workspace list / loading / error:** existing `SessionsScreen` states
  remain.

## Testing

- **Bridge `parseSessions`:** rewrite fixtures to the real nested shape; assert
  one `Workspace` per workspace, panes mapped with correct id/focused/ready/kind,
  terminals **not** emitted as top-level cards, dedup by workspace id,
  workspaces-nested-under-`groups` handled, missing `terminals` → empty pane
  list. `classifyKind` unit tests stay (now applied per pane).
- **App:** `WorkspacesResponse` deserialization against the new JSON;
  `SessionsViewModel` maps response → workspaces; `SessionsScreen` behavior —
  single-pane tap → `onOpenTerminal(paneId)`, multi-pane tap → expands, pane tap
  → `onOpenTerminal(paneId)`.
- **`terminal.go`:** unchanged behavior; existing `terminal_test.go` stays green.

## Out of scope

- The agent-side terminal handler (`terminal.go`) needs no change — the fix is
  purely about passing valid surface ids. The connect/close debug logging added
  in `5ac509c` stays.
- Sorting workspaces/panes, pinning, group rendering, and wiring `has_unread`
  into a richer attention badge are deferred.
```
