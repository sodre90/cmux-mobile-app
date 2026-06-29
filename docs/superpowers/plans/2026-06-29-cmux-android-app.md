# cmux Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app that connects to the `cmux-bridge` (behind the
user's mTLS nginx) to list cmux sessions, drive a live terminal, and answer
agent prompts — with FCM push when an agent needs attention.

**Architecture:** Single-module Kotlin/Jetpack Compose app. An OkHttp client
configured with a user-imported PKCS#12 **client certificate** (mTLS) and a
per-device **bearer token** talks to the bridge's documented HTTP/WS contract.
Terminal frames carry cmux's `render_grid` (`cmux.render-grid.v1`) which a Compose
renderer draws as a styled cell grid. FCM delivers high-priority data messages
that deep-link into the agent inbox.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), OkHttp (HTTP + WebSocket),
kotlinx.serialization, AndroidX (Lifecycle, Navigation-Compose, DataStore),
Firebase Messaging. minSdk 26, targetSdk 35, AGP 8.7, Kotlin 2.0, JDK 17 toolchain.

## Global Constraints

- **Bridge contract is the only API.** The app depends solely on the bridge's
  endpoints (`GET /sessions`, `WS /events`, `WS /terminal/{id}`,
  `POST /feed/{id}/reply`, `POST /devices/register`), never on cmux internals.
- **Auth = mTLS (client cert) + `Authorization: Bearer <device-token>`** on every
  request and WS handshake. No token → no network.
- **Secrets on device:** the PKCS#12 and the device token live in
  EncryptedSharedPreferences / DataStore; never logged.
- **Module path:** app dir `android/`, Gradle module `:app`, package
  `com.sodre90.cmuxremote`, applicationId `com.sodre90.cmuxremote`.
- **JDK 17 toolchain**, AGP 8.7, Kotlin 2.0.x, Compose BOM 2024.10.x.
- **Verification gate:** `cd android && ./gradlew :app:testDebugUnitTest` and
  `./gradlew :app:assembleDebug` must both pass. Pure-logic classes (API client,
  render-grid parser) have JVM unit tests; UI is built to compile and run.
- **Firebase optional at build time:** `google-services.json` is required only
  for push; the app must build and run without it (push simply inactive).
- Commits authored solely by the human (`sodre90`); no AI co-author trailers.

---

## Calibration note (planner == executor)

Executed inline with the Gradle compiler + JVM unit tests as the feedback loop.
Tasks give exact paths, the public Kotlin signatures that lock each contract, and
full code for the decision-bearing logic (mTLS OkHttp setup, render-grid parser,
API/WS client, FCM service). Compose screen bodies are specified by their state
inputs + the composable signatures and built to compile; pixel layout is not
transcribed line-for-line. If handed to fresh subagents, expand screen steps to
full composables from the stated signatures first.

## File structure

```
android/
  settings.gradle.kts                 # rootProject, :app
  build.gradle.kts                    # plugin versions (AGP, Kotlin, ksp, google-services)
  gradle.properties                   # AndroidX, JVM args
  gradle/libs.versions.toml           # version catalog
  gradlew, gradlew.bat, gradle/wrapper/*   # Gradle 8.9 wrapper
  app/build.gradle.kts                # android block, deps, JDK 17, optional google-services
  app/src/main/AndroidManifest.xml    # INTERNET, FCM service, single Activity
  app/src/main/java/com/sodre90/cmuxremote/
    CmuxApp.kt                         # Application; manual DI container
    MainActivity.kt                    # Compose host + NavGraph
    data/
      BridgeContract.kt                # @Serializable DTOs mirroring the bridge JSON
      RenderGrid.kt                    # @Serializable render-grid model
      RenderGridParser.kt              # render_grid -> list of styled cell rows (pure)
      Settings.kt                      # DataStore: baseUrl, deviceToken, p12 alias
      Mtls.kt                          # build OkHttpClient w/ client cert + bearer interceptor
      BridgeClient.kt                  # sessions(), registerDevice(), replyFeed()
      EventsSocket.kt                  # WS /events -> Flow<EventFrame>
      TerminalSocket.kt               # WS /terminal/{id} -> Flow<TerminalDown> + send()
    ui/
      sessions/SessionsScreen.kt + SessionsViewModel.kt
      terminal/TerminalScreen.kt + TerminalViewModel.kt + RenderGridView.kt
      inbox/InboxScreen.kt + InboxViewModel.kt
      settings/SettingsScreen.kt + SettingsViewModel.kt
      theme/Theme.kt
    push/CmuxMessagingService.kt       # FirebaseMessagingService -> notification + deep link
  app/src/test/java/com/sodre90/cmuxremote/
    RenderGridParserTest.kt
    BridgeContractTest.kt
    MtlsTest.kt
```

Bridge JSON ⇄ Kotlin DTOs (must match the bridge exactly):

- `Session(id, cwd, title, kind, needs_attention)`
- `EventFrame(type, name, needs_attention, feed_id, workspace_id, surface_id, title, kind)`
- `TerminalDown(type, grid, columns, rows, seq)` where `grid` is the render-grid object
- `TerminalUp(type, text, columns, rows)`
- `FeedReply(kind, request_id, params)`

---

### Task 1: Gradle project that builds (empty app)

**Files:** all Gradle config + wrapper, `MainActivity.kt` (empty Compose
`Scaffold`), `AndroidManifest.xml`, `CmuxApp.kt`, version catalog, theme.

**Interfaces:**
- Produces: a buildable `:app` with Compose enabled and an empty `MainActivity`.

- [ ] **Step 1:** Write `settings.gradle.kts`, root `build.gradle.kts`,
  `gradle/libs.versions.toml` (AGP 8.7.0, Kotlin 2.0.21, compose-bom 2024.10.01,
  okhttp 4.12.0, kotlinx-serialization 1.7.3, lifecycle 2.8.6,
  navigation-compose 2.8.3, datastore 1.1.1, security-crypto 1.1.0-alpha06,
  firebase-bom 33.5.1), `app/build.gradle.kts` (compileSdk 35, minSdk 26,
  JDK 17, `buildFeatures { compose = true }`, serialization plugin), a Material 3
  `Theme.kt`, a minimal `MainActivity` showing `Text("cmux remote")`, manifest
  with `INTERNET` + the single activity, `CmuxApp` Application.
- [ ] **Step 2:** Generate the Gradle 8.9 wrapper.
- [ ] **Step 3:** Run `cd android && ./gradlew :app:assembleDebug`.
  Expected: BUILD SUCCESSFUL, an APK in `app/build/outputs/apk/debug/`.
- [ ] **Step 4: Commit**

```bash
git add android
git commit -m "feat(app): buildable Compose scaffold"
```

---

### Task 2: Bridge DTOs + render-grid parser (pure, JVM-tested)

**Files:** `data/BridgeContract.kt`, `data/RenderGrid.kt`,
`data/RenderGridParser.kt`, tests `RenderGridParserTest.kt`,
`BridgeContractTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class Session(val id:String, val cwd:String, val title:String,
      val kind:String, @SerialName("needs_attention") val needsAttention:Boolean=false)
  @Serializable data class SessionsResponse(val sessions: List<Session>)
  @Serializable data class EventFrame(val type:String, val name:String="",
      @SerialName("needs_attention") val needsAttention:Boolean=false,
      @SerialName("feed_id") val feedId:String="", @SerialName("workspace_id") val workspaceId:String="",
      @SerialName("surface_id") val surfaceId:String="", val title:String="", val kind:String="")
  @Serializable data class TerminalUp(val type:String, val text:String="",
      val columns:Int=0, val rows:Int=0)
  @Serializable data class FeedReply(val kind:String, @SerialName("request_id") val requestId:String,
      val params: Map<String, String> = emptyMap())

  data class Cell(val text:String, val fg:Long?, val bg:Long?, val bold:Boolean, val inverse:Boolean)
  data class GridRow(val cells: List<Cell>)
  data class Grid(val columns:Int, val rows:Int, val cursorRow:Int, val cursorCol:Int, val lines: List<GridRow>)
  object RenderGridParser { fun parse(renderGrid: JsonObject): Grid }
  ```
  `parse` reads `columns`, `rows`, `cursor`, `row_spans` (each `{row, column,
  cell_width, style_id, text}`) and the `styles` table (`{id, foreground,
  background, bold, inverse, ...}`), placing spans into a `columns`×`rows` cell
  matrix.

- [ ] **Step 1: Failing test** — feed a canned `render_grid` JSON (one styled
  span "hi" at row 0 col 0 with a style referencing fg) and assert
  `parse(...).lines[0]` reconstructs "hi" with the style applied, dimensions
  correct, and gaps filled with blank cells.

```kotlin
// RenderGridParserTest.kt (essence)
private const val grid = """{"columns":4,"rows":1,"cursor":{"row":0,"column":2},
 "styles":[{"id":1,"foreground":"#ff0000","background":null,"bold":true,"inverse":false}],
 "row_spans":[{"row":0,"column":0,"cell_width":1,"style_id":1,"text":"hi"}]}"""

@Test fun parsesStyledSpan() {
    val g = RenderGridParser.parse(Json.parseToJsonElement(grid).jsonObject)
    assertEquals(4, g.columns); assertEquals(1, g.rows)
    assertEquals("hi", g.lines[0].cells.take(2).joinToString(""){it.text})
    assertTrue(g.lines[0].cells[0].bold)
    assertEquals(0xff0000L, g.lines[0].cells[0].fg)
    assertEquals(2, g.cursorCol)
}
```

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest` → FAIL (unresolved).
- [ ] **Step 3:** Implement DTOs + parser. Color parse: `#rrggbb` → Long, null → null.
- [ ] **Step 4:** Run tests → PASS.
- [ ] **Step 5: Commit** `feat(app): bridge DTOs and render-grid parser`.

---

### Task 3: mTLS OkHttp client + auth (JVM-tested with MockWebServer TLS)

**Files:** `data/Mtls.kt`, `data/Settings.kt`, test `MtlsTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  data class BridgeConfig(val baseUrl:String, val deviceToken:String,
      val clientP12: ByteArray?, val p12Password:String, val serverCaPem:String?)
  object Mtls { fun client(cfg: BridgeConfig): OkHttpClient }   // installs KeyManager from p12 + bearer interceptor
  class Settings(context: Context) { /* DataStore: baseUrl, deviceToken; EncryptedSharedPreferences: p12 bytes, password */ }
  ```
  `client` builds an `SSLSocketFactory` whose `KeyManager` presents the p12 cert,
  trusts `serverCaPem` (or system) and adds an interceptor injecting
  `Authorization: Bearer <deviceToken>`.

- [ ] **Step 1: Failing test** — MockWebServer with `requestClientAuth()`;
  build a self-signed client cert+CA in-test (okhttp `HeldCertificate`); assert
  a request through `Mtls.client(...)` succeeds and carries the bearer header,
  and that a client without the cert is rejected by the server.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(app): mTLS OkHttp client + secure settings store`.

---

### Task 4: BridgeClient (sessions, feed reply, device register) — MockWebServer-tested

**Files:** `data/BridgeClient.kt`, extend a test (`BridgeClientTest.kt`).

**Interfaces:**
- Produces:
  ```kotlin
  class BridgeClient(private val http: OkHttpClient, private val baseUrl: String) {
      suspend fun sessions(): List<Session>
      suspend fun registerDevice(fcmToken: String)
      suspend fun replyFeed(feedId:String, reply: FeedReply)
  }
  ```
- [ ] **Step 1: Failing tests** — MockWebServer returns a `/sessions` body;
  assert `sessions()` decodes it; assert `replyFeed` POSTs to
  `/feed/{id}/reply` with the serialized body; assert bearer header present.
- [ ] **Step 2:** FAIL. **Step 3:** Implement with OkHttp + kotlinx.serialization,
  IO dispatcher. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(app): BridgeClient REST calls`.

---

### Task 5: WebSocket flows (events + terminal)

**Files:** `data/EventsSocket.kt`, `data/TerminalSocket.kt`,
test `EventsSocketTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  class EventsSocket(private val http:OkHttpClient, private val baseUrl:String) {
      fun connect(): Flow<EventFrame>   // callbackFlow over WS /events; auto-close on cancel
  }
  class TerminalSocket(private val http:OkHttpClient, private val baseUrl:String, private val surfaceId:String) {
      fun connect(): Flow<JsonObject>           // raw TerminalDown frames
      fun send(up: TerminalUp)                  // input/paste/resize
      fun close()
  }
  ```
- [ ] **Step 1: Failing test** — MockWebServer web socket sends two JSON event
  frames; assert `connect()` emits two decoded `EventFrame`s. (`MockWebServer`
  supports `webSocket` upgrades.)
- [ ] **Step 2:** FAIL. **Step 3:** Implement with `callbackFlow` +
  `http.newWebSocket`. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(app): events + terminal WebSocket flows`.

---

### Task 6: Settings screen (onboarding: base URL, p12 import, token)

**Files:** `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, nav wiring.

**Interfaces:**
- Consumes: `Settings`. Produces a screen to set base URL, paste device token,
  and import a `.p12` (SAF file picker) with its password; persists via `Settings`.
- [ ] **Step 1:** Implement composable + VM (state: `baseUrl`, `token`, `p12Imported`).
- [ ] **Step 2:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** `feat(app): settings/onboarding screen`.

---

### Task 7: Sessions screen (live `GET /sessions`)

**Files:** `ui/sessions/SessionsScreen.kt`, `SessionsViewModel.kt`.

**Interfaces:**
- Consumes: `BridgeClient.sessions()`. Produces a list with title, cwd, kind
  badge, and an attention dot; tap → navigate to terminal or inbox.
  `SessionsViewModel.state: StateFlow<UiState<List<Session>>>` with `refresh()`.
- [ ] **Step 1:** Implement VM (loads sessions, error/loading states) + screen.
- [ ] **Step 2:** assembleDebug → SUCCESSFUL.
- [ ] **Step 3: Commit** `feat(app): sessions list screen`.

---

### Task 8: Terminal screen (render-grid view + input)

**Files:** `ui/terminal/TerminalScreen.kt`, `TerminalViewModel.kt`,
`RenderGridView.kt`.

**Interfaces:**
- Consumes: `TerminalSocket`, `RenderGridParser`. `RenderGridView(grid: Grid)`
  draws the cell grid with a monospace font and per-cell fg/bg/bold via
  `Canvas`/`drawText`. The VM maps incoming frames → `Grid` and exposes
  `send(text)`, `resize(cols,rows)`. A `TextField`/key bar sends input.
- [ ] **Step 1:** Implement VM + `RenderGridView` + screen (soft keyboard sends
  `TerminalUp(type="input")`; common keys row: Esc/Tab/Ctrl/arrows).
- [ ] **Step 2:** assembleDebug → SUCCESSFUL.
- [ ] **Step 3: Commit** `feat(app): terminal screen with render-grid renderer`.

---

### Task 9: Agent inbox (events + reply)

**Files:** `ui/inbox/InboxScreen.kt`, `InboxViewModel.kt`.

**Interfaces:**
- Consumes: `EventsSocket.connect()`, `BridgeClient.replyFeed()`. The VM keeps a
  list of attention frames (`needsAttention` feed items), shows
  Approve/Deny/Reply, and calls `replyFeed(feedId, FeedReply(kind, requestId, params))`.
- [ ] **Step 1:** Implement VM (collects events, dedups by `feedId`, removes on
  reply) + screen. Approve → `params={"decision":"approve"}` (see bridge note:
  confirm against a live prompt).
- [ ] **Step 2:** assembleDebug → SUCCESSFUL.
- [ ] **Step 3: Commit** `feat(app): agent inbox with reply actions`.

---

### Task 10: FCM push + device registration

**Files:** `push/CmuxMessagingService.kt`, manifest service entry,
`app/build.gradle.kts` (optional google-services plugin guarded by file
presence), register the FCM token via `BridgeClient.registerDevice` on app start.

**Interfaces:**
- Produces: a `FirebaseMessagingService` that, on a data message with
  `type=attention`, posts a notification deep-linking to the inbox; on new token
  calls `registerDevice`. The google-services plugin is applied only when
  `app/google-services.json` exists, so the app still builds without it.
- [ ] **Step 1:** Implement service + token registration + notification channel +
  deep link intent. Guard google-services plugin application.
- [ ] **Step 2:** assembleDebug → SUCCESSFUL (without `google-services.json`).
- [ ] **Step 3: Commit** `feat(app): FCM push + device registration`.

---

### Task 11: README + run instructions

**Files:** `android/README.md`.

- [ ] **Step 1:** Document: open in Android Studio, set the bridge base URL
  (your nginx DNS), generate + import the client `.p12`, paste the device token
  from `cmux-bridge pair`, optional Firebase setup (`google-services.json`),
  build/run. Note the app needs the bridge reachable (mTLS).
- [ ] **Step 2: Commit** `docs(app): android README`.

---

## Self-review (spec coverage)

- Session list (spec §6.2) → Task 7. ✅
- Terminal widget + render-grid (spec §6.2/§6.3, S2 resolved = cell grid) →
  Tasks 2, 5, 8. ✅
- Agent inbox + reply (spec §6.2, §8) → Tasks 5, 9. ✅
- mTLS client cert handling (spec §6.1, S4) → Task 3. ✅
- FCM (spec §7) → Task 10. ✅
- Onboarding/secrets (spec §4) → Tasks 3, 6. ✅

**Out of scope (later):** create/close sessions, file-diff viewing, multiple
Macs, biometric lock, tablet layouts.

## Execution

Executed inline with `./gradlew :app:testDebugUnitTest` + `:app:assembleDebug`
as the verification gate, a commit per task.
