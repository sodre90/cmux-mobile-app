# Dual-pairing automatic fallback (relay + direct, together)

## Context

`2026-07-03-tailscale-direct-transport-design.md` added an optional direct
(Tailscale) transport as a manual **alternative** to the relay: the phone
stores exactly one `{baseUrl, deviceToken}` pair at a time, and switching
between relay and direct means re-pairing by hand, which overwrites
whichever pairing was active before. That project's own non-goals section
named this explicitly: "Automatic transport selection/fallback between
relay and direct mode — switching is a manual re-pair in v1."

Real end-to-end testing (2026-07-04) confirmed direct mode itself works —
including a real Let's Encrypt cert served over the tailnet — but re-pairing
against it silently discarded the relay pairing. The user then asked for
exactly the case the prior spec deferred: **pair against both at once**, and
have the phone automatically prefer the relay but fall back to the direct
listener when the relay (home server) is unreachable, with no manual step.

This also surfaces a real bug worth fixing regardless of scope: direct mode
has no `/devices/register` endpoint (push stays relay-only, by that
project's own design). A phone that is *solely* paired to direct today has
no way to receive push at all, and worse, `CmuxMessagingService.onNewToken`
calls whatever `bridgeClient()` is currently configured — so an FCM token
refresh while direct is the only paired profile would try to register
against a backend that doesn't support it. Today (before this project) that
failure is silent and swallowed (`onNewToken`'s `catch (_: Exception)`), so
nothing crashes, but push permanently stops working for that device. Dual
pairing is the fix: always register with the relay slot specifically, which
is only possible once the relay slot is a stable, independently-addressable
thing rather than "whatever's currently in Settings."

## Current architecture (confirmed from code)

- **`android/.../data/Settings.kt`**: one `EncryptedSharedPreferences` file,
  exactly two keys (`base_url`, `device_token`). `bridgeConfig()` returns a
  single `BridgeConfig?` or null. No slot concept at all.
- **`android/.../data/e2e/Session.kt`**: one `EncryptedSharedPreferences`
  file (`cmux_e2e_session`), five keys (peer pubkey, shared secret, send
  counter, recv-highest, recv-window-bits). Its own doc comment: "the phone
  pairs with exactly one agent at a time — re-pairing overwrites this
  record." `setPairing()` resets all counters unconditionally.
- **`android/.../data/AppContainer.kt`**: builds and caches exactly one
  `OkHttpClient`, keyed by `"${baseUrl}|${deviceToken}|${session.isPaired()}"`.
  `bridgeClient()`, `eventsSocket()`, `terminalSocket(id)` all derive from
  the single `settings.bridgeConfig()`. `pairingClient()` builds a bare
  `OkHttpClient()` (no bearer/e2e — pairing is unauthenticated) closing over
  the single shared `identity`/`session`/`settings`.
- **`android/.../data/pairing/PairingClient.kt`**: `pairInternal()` takes
  plain callbacks (`onSetPairing`, `onSetBaseUrl`, `onSetToken`) — already
  parametrized enough that "which slot to write into" is a thin wrapper
  away, not a rewrite. `PairingClient.pair(qr)` hardcodes those callbacks to
  the single `session`/`settings` instance.
- **`android/.../ui/pairing/PairingViewModel.kt`** /
  **`PairingScreen.kt`**: no slot concept; a single scan-or-manual-entry flow
  that always pairs "the" connection.
- **`android/.../ui/CmuxNavHost.kt`**: `Routes.SETTINGS` composable directly
  renders `PairingScreen`; the nav host's start destination is
  `Routes.SETTINGS` iff `container.settings.bridgeConfig() == null`, else
  `Routes.SESSIONS`. Reached both on first run and from the Sessions
  screen's Settings icon (`onSettings` in `SessionsScreen`).
- **`android/.../push/CmuxMessagingService.kt`**: `onNewToken` calls
  `container.bridgeClient()?.registerDevice(token)` — whatever `bridgeClient()`
  resolves to today, with no way to say "always the relay one."
- **`android/.../ui/sessions/SessionsViewModel.kt`**: `subscribeToEvents()`
  runs a single reconnect-with-backoff loop
  (`INITIAL_BACKOFF_MS`=1000, `MAX_BACKOFF_MS`=5000) against
  `container.eventsSocket()`. `InboxViewModel` mirrors the same pattern
  (per its own doc comments referenced from `SessionsViewModel`).
  `TerminalViewModel` similarly owns one `terminalSocket(surfaceId)`.
- **Bridge (Go) side needs no changes.** Both listeners (relay dial-loop,
  direct mode's TLS listener) already run concurrently and independently
  today (`runAgent`, `bridge/cmd/cmux-bridge/agent.go`) — each has its own
  `auth.Store`/tenant, and a device registers its FCM token against whichever
  backend it calls `/devices/register` on. Nothing about "the phone also
  happens to be paired elsewhere" needs the Go side to know or coordinate;
  this whole feature is a client-side connection-selection problem.

## Decisions made

- **Automatic fallback, not a manual switcher.** The user's own framing —
  "if my home server down, i can access it through tailscale as fallback" —
  describes seamless behavior, not a toggle they'd have to remember to
  flip. *(Proposed and not yet explicitly confirmed in chat — the prior
  AskUserQuestion on this timed out; please confirm or redirect when
  reviewing this spec.)*
- **Fixed roles, not configurable priority:** relay is always primary,
  direct is always fallback. This matches reality (relay works from any
  network; direct only works when Tailscale is up on the phone) and avoids
  building a preference UI nobody asked for.
- **Both must be independently paired.** There is no way to derive a
  direct-mode pairing from a relay one or vice versa (separate `auth.Store`
  instances, separate tokens, separate e2e shared secrets — see the prior
  spec's "device paired via the relay tries hitting the direct listener"
  edge case, which already established these are fully independent
  credentials). The Settings screen must let the user pair each slot
  separately and see both statuses at once.
- **Push registration is pinned to the relay slot, unconditionally** — not
  "whichever is currently preferred." This is a correctness fix bundled into
  this project (see Context), not a new feature in its own right.
- **No new bridge-side work.** Confirmed above.

## Design

### `ConnectionSlot` and per-slot storage

New enum, `android/.../data/ConnectionSlot.kt`:

```kotlin
package com.sodre90.cmuxremote.data

enum class ConnectionSlot { RELAY, DIRECT }
```

**`Settings.kt`** — replace the two bare keys with slot-prefixed pairs
(`relay_base_url`/`relay_device_token`, `direct_base_url`/`direct_device_token`):

```kotlin
fun bridgeConfig(slot: ConnectionSlot): BridgeConfig? {
    val url = prefs.getString(key(slot, KEY_BASE_URL), null)?.takeIf { it.isNotBlank() } ?: return null
    val token = prefs.getString(key(slot, KEY_TOKEN), null)?.takeIf { it.isNotBlank() } ?: return null
    return BridgeConfig(baseUrl = url, deviceToken = token)
}

fun setBridgeConfig(slot: ConnectionSlot, baseUrl: String, token: String) {
    prefs.edit()
        .putString(key(slot, KEY_BASE_URL), baseUrl)
        .putString(key(slot, KEY_TOKEN), token)
        .apply()
}

private fun key(slot: ConnectionSlot, base: String) = "${slot.name.lowercase()}_$base"
```

**One-time legacy migration** (init block, alongside the existing `KEY_P12`
wipe): if the old unprefixed `base_url`/`device_token` keys exist and
neither slot has been written yet, migrate them into a slot chosen by a
simple heuristic — a base URL ending in `.ts.net` (Tailscale's MagicDNS
suffix) is `DIRECT`, anything else is `RELAY` — then delete the legacy keys.
This exact user's current phone state (`https://macbook.sokoke-draco.ts.net:8443`)
migrates correctly to `DIRECT` under this rule, matching reality: their
relay pairing was overwritten and needs to be re-established from Settings
after this ships.

**`Session.kt`** — same prefixing approach, constructed per slot:
`class Session(context: Context, private val slot: ConnectionSlot) : PairedSession`,
with every existing key (`KEY_PEER_PUBLIC_KEY`, `KEY_SHARED_SECRET`,
`KEY_SEND_COUNTER`, `KEY_RECV_HIGHEST`, `KEY_RECV_WINDOW_BITS`) prefixed the
same way, in the same `cmux_e2e_session` file (two slots sharing one file,
distinguished only by key prefix — no need for two encrypted files). Same
legacy-migration idea: if unprefixed keys exist, migrate into the same slot
`Settings` inferred, then clear them. `isPaired()` becomes per-slot; nothing
else about the class's behavior (counters, replay window) changes — it's
still "one session per pairing," just two pairings live side by side now
instead of one.

### `AppContainer.kt`: two independent client stacks + a fallback wrapper

Replace the single cached `(clientKey, client)` pair with one per slot
(same caching-by-key idea, doubled):

```kotlin
private val sessions = ConnectionSlot.entries.associateWith { Session(appContext, it) }
private val clients = mutableMapOf<ConnectionSlot, Pair<String, OkHttpClient>>()

@Synchronized
private fun httpClient(slot: ConnectionSlot, cfg: BridgeConfig): OkHttpClient {
    val key = "${cfg.baseUrl}|${cfg.deviceToken}|${sessions.getValue(slot).isPaired()}"
    val cached = clients[slot]
    if (cached != null && cached.first == key) return cached.second
    var built = Mtls.client(cfg)
    if (sessions.getValue(slot).isPaired()) {
        built = built.newBuilder().addInterceptor(E2eInterceptor(sessions.getValue(slot), cipher)).build()
    }
    clients[slot] = key to built
    return built
}

fun bridgeClient(slot: ConnectionSlot): BridgeClient? =
    settings.bridgeConfig(slot)?.let { BridgeClient(httpClient(slot, it), it.baseUrl) }

fun eventsSocket(slot: ConnectionSlot): EventsSocket? =
    settings.bridgeConfig(slot)?.let { EventsSocket(httpClient(slot, it), it.baseUrl, sessions.getValue(slot), cipher) }

fun terminalSocket(slot: ConnectionSlot, surfaceId: String): TerminalSocket? =
    settings.bridgeConfig(slot)?.let { TerminalSocket(httpClient(slot, it), it.baseUrl, surfaceId, sessions.getValue(slot), cipher) }

fun pairingClient(slot: ConnectionSlot): PairingClient =
    PairingClient(OkHttpClient(), identity, sessions.getValue(slot), settings, slot)

/** The fallback-aware entry point most call sites should use instead of bridgeClient(slot) directly. */
fun activeBridge(): FallbackBridgeClient = FallbackBridgeClient(
    primary = { bridgeClient(ConnectionSlot.RELAY) },
    fallback = { bridgeClient(ConnectionSlot.DIRECT) },
)
```

`identity` (the phone's X25519 keypair, used to derive the shared secret
during pairing) stays a single shared instance — it identifies the *phone*,
not a connection, and both slots' pairing handshakes legitimately use the
same keypair to talk to two different agents' pairing endpoints.

### `FallbackBridgeClient`: try-primary-then-fallback for HTTP calls

New file, `android/.../data/FallbackBridgeClient.kt`. Wraps two
`() -> BridgeClient?` suppliers and an in-memory "primary is in the penalty
box until T" timestamp (process-lifetime only — a fresh app process always
retries the relay first, which is the safe default and needs no persisted
state):

```kotlin
class FallbackBridgeClient(
    private val primary: () -> BridgeClient?,
    private val fallback: () -> BridgeClient?,
) {
    @Volatile private var primaryDownUntil: Long = 0L

    private suspend fun <T> call(block: suspend (BridgeClient) -> T): T {
        val primaryClient = primary()
        val fallbackClient = fallback()

        // Skip a doomed primary attempt if it's not configured at all, or
        // we recently confirmed it's down (still inside the penalty window).
        val skipPrimary = primaryClient == null || System.currentTimeMillis() < primaryDownUntil
        if (skipPrimary) {
            return block(fallbackClient ?: primaryClient ?: throw BridgeException(0, "not configured"))
        }

        return try {
            block(primaryClient)
        } catch (e: IOException) {
            if (fallbackClient == null) throw e
            primaryDownUntil = System.currentTimeMillis() + PENALTY_MS
            block(fallbackClient)
        }
    }

    suspend fun sessions() = call { it.sessions() }
    suspend fun pendingFeed() = call { it.pendingFeed() }
    suspend fun replyFeed(feedId: String, reply: FeedReply) = call { it.replyFeed(feedId, reply) }
    suspend fun renameWorkspace(id: String, title: String) = call { it.renameWorkspace(id, title) }
    suspend fun setYoloMode(id: String, mode: String) = call { it.setYoloMode(id, mode) }
    // registerDevice() is deliberately NOT exposed here -- see push section below.

    private companion object {
        const val PENALTY_MS = 30_000L
    }
}
```

`call`'s per-attempt timeout comes from the underlying `BridgeClient`'s
`OkHttpClient`, which needs a **short connect timeout specifically for the
primary attempt** so a dead relay fails fast instead of hanging the UI —
`AppContainer.httpClient(RELAY, cfg)` builds its client with
`connectTimeout(3, TimeUnit.SECONDS)` (the direct slot's client keeps
OkHttp's normal default, since once we've already fallen back there's no
second fallback to race against). `SessionsViewModel`/`InboxViewModel`
switch from `container.bridgeClient()` to `container.activeBridge()`, and
their existing `_actionError`/`UiState.Error` paths need no changes — a
`FallbackBridgeClient` call either succeeds (transparently, from whichever
slot) or throws the same `BridgeException`/`IOException` shapes as today
after both slots have failed.

### WebSocket fallback (events + terminal)

`EventsSocket`/`TerminalSocket` connections don't go through
`FallbackBridgeClient` (they're long-lived, not one-shot calls). Instead,
extend the reconnect-with-backoff loop already in `SessionsViewModel`
(and mirrored in `InboxViewModel`/`TerminalViewModel`) to alternate slots:

```kotlin
private fun subscribeToEvents() {
    var preferred = ConnectionSlot.RELAY
    viewModelScope.launch {
        var backoff = INITIAL_BACKOFF_MS
        while (isActive) {
            val socket = container.eventsSocket(preferred) ?: container.eventsSocket(preferred.other())
            if (socket == null) { delay(backoff); continue }
            try {
                socket.connect().collect { frame ->
                    backoff = INITIAL_BACKOFF_MS
                    if (frame.type != "heartbeat") refreshRequests.tryEmit(Unit)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                preferred = preferred.other() // try the other slot next time
            }
            if (!isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            refreshRequests.tryEmit(Unit)
        }
    }
}
```

(`ConnectionSlot.other()` is a two-line extension: `RELAY -> DIRECT`,
`DIRECT -> RELAY`.) This is a small, additive change to an existing loop —
no new class needed for the WS side, unlike the HTTP side where
`FallbackBridgeClient` centralizes the "try both, race a timeout" logic that
five different call sites (`sessions`, `pendingFeed`, `replyFeed`,
`renameWorkspace`, `setYoloMode`) would otherwise each have to repeat.
`TerminalViewModel` applies the identical pattern to its single
`terminalSocket(surfaceId)`.

### Pairing UI: two independent slots, not one flow

- **`PairingClient.kt`**: `pair(qr)` and the free `pairInternal()` function
  gain a `slot: ConnectionSlot` parameter (or, simpler given `PairingClient`
  is already constructed per-slot per the `AppContainer.pairingClient(slot)`
  signature above, no parameter is needed on `pair()` itself — the instance
  already knows its slot via the `session`/`settings` callbacks it was
  built with; `onSetBaseUrl`/`onSetToken` close over
  `{ settings.setBridgeConfig(slot, it, ...) }` instead of the old
  `{ settings.baseUrl = it }`). No change to the wire protocol or
  `pairInternal`'s core logic — only which slot the result is written into.
- **`PairingViewModel.kt`**: gains a `slot: ConnectionSlot` constructor
  parameter, threaded straight into `container.pairingClient(slot)`. No
  other change — scanning/manual-entry/error states are identical
  regardless of slot.
- **`PairingScreen.kt`**: unchanged internally (still just scan-or-manual-entry
  for whichever slot its `PairingViewModel` was built for); its `TopAppBar`
  title becomes a parameter (`"Pair via relay"` / `"Pair via Tailscale
  (direct)"`) so the user always knows which slot they're about to
  (re)pair.
- **New `ConnectionSettingsScreen.kt`** replaces `Routes.SETTINGS`'s direct
  `PairingScreen` render. Shows both slots' status (paired / not paired,
  derived from `container.settings.bridgeConfig(slot) != null`) each with a
  "(Re)pair" button navigating to a per-slot pairing route. Two new routes,
  `Routes.pair(slot)` (e.g. `"pair/relay"`, `"pair/direct"`), each rendering
  `PairingScreen` with a `PairingViewModel` built for that slot; on success,
  both routes pop back to `ConnectionSettingsScreen` (not straight to
  Sessions, since the user may want to pair the second slot next), with a
  separate "Done" action on that screen navigating to `Routes.SESSIONS`.
- **`CmuxNavHost.kt`**: start destination becomes `Routes.SETTINGS` iff
  *neither* slot is paired (`RELAY` and `DIRECT` both return `null`);
  `Routes.SESSIONS` if *either* is paired (one paired, one not, is a normal,
  supported steady state — the app just won't have a fallback yet).

### Push: pin registration to the relay slot

**`CmuxMessagingService.kt`**: `onNewToken` changes from
`container.bridgeClient()?.registerDevice(token)` to
`container.bridgeClient(ConnectionSlot.RELAY)?.registerDevice(token)`. If
the relay slot isn't paired at all (only direct is), this is `null` and the
existing `catch`/no-op behavior applies unchanged — push simply isn't
available yet, which is accurate (direct mode has no push support, and this
project doesn't add any), rather than the current silent-and-wrong behavior
of trying to register against whatever happens to be active.

## Data flow

### Dual pairing

1. User pairs against the relay first (today's existing flow, now writing
   into the `RELAY` slot) — typically during first-run onboarding.
2. User later opens Settings → Tailscale (direct) → pairs a second time,
   running `cmux-bridge pair-device --direct` on the Mac as today. This
   writes into the `DIRECT` slot without touching the `RELAY` slot's stored
   config or session state at all.
3. Both slots now have independent `{baseUrl, token}` (Settings) and
   `{sharedSecret, counters}` (Session) — two fully separate credentials to
   two fully separate `auth.Store` tenants on the Mac, exactly as the prior
   spec's architecture already allows.

### Steady-state request (HTTP)

1. `SessionsViewModel`/`InboxViewModel` call `container.activeBridge().sessions()`
   (etc.) instead of `container.bridgeClient()!!.sessions()`.
2. `FallbackBridgeClient` tries the relay slot's `BridgeClient` first
   (3s connect timeout). On success, done — this is the common case,
   indistinguishable from today's behavior.
3. On an `IOException` (relay unreachable/timeout), it retries the exact
   same logical call against the direct slot's `BridgeClient` (normal
   timeout) and remembers relay-is-down for 30s so the *next* several calls
   go straight to direct without re-paying the 3s connect-timeout tax on
   every one.
4. After the 30s penalty window, the next call tries relay again — cheap
   automatic recovery with no persisted state, no background polling, and
   no user-visible indicator needed beyond "it just works."

### Steady-state connection (WebSocket: events, terminal)

1. The reconnect loop tries whichever slot it currently prefers (starts at
   `RELAY`); on any failure it flips its preference and retries the other
   slot on the next iteration, subject to the same backoff as today.
2. A slot that has no stored pairing is simply skipped (`eventsSocket(slot)`
   returns null), so a phone with only one slot paired behaves exactly as
   it does today — this feature is additive for the "both paired" case and
   a no-op otherwise.

## Error handling / edge cases

- **Neither slot paired (fresh install):** `CmuxNavHost` routes to
  `ConnectionSettingsScreen`, exactly like today's single-`PairingScreen`
  redirect, just via the new screen.
- **Only one slot paired (today's actual state, and a fully normal steady
  state):** `FallbackBridgeClient`/the WS loop degrade to "always use the
  one that's configured" — no behavior change from today.
- **Both slots paired, both unreachable:** the relay attempt fails fast
  (3s), the direct attempt fails on its own normal timeout, and the caller
  sees the same `BridgeException`/`IOException` it would see today from a
  single failed backend — `UiState.Error` surfaces it unchanged.
- **Legacy single-slot data on upgrade:** migrated once via the
  `.ts.net`-suffix heuristic described above, then the legacy keys are
  deleted (mirrors the existing `KEY_P12` migration idiom already in
  `Settings.kt`). This exact user's current phone (paired only to
  `*.ts.net`) migrates to `DIRECT`, meaning after upgrading they still need
  to (re)pair the relay slot once from the new Settings screen to get the
  fallback benefit and restore push.
- **Relay flaps rapidly (up/down/up within seconds):** bounded by the 30s
  penalty window — worst case, a few calls in a row go to direct
  needlessly, but nothing loops or thrashes per-request.
- **Push with only direct paired:** unchanged limitation carried over from
  the prior spec — push stays unavailable, now failing silently and
  *correctly* (relay slot is `null`) instead of silently and *incorrectly*
  (calling an endpoint that doesn't exist on whatever was last active).

## Testing

- **Android unit tests:**
  - `Settings`/`Session` per-slot storage: writing one slot doesn't affect
    the other; the legacy-migration heuristic (`.ts.net` → `DIRECT`, else
    `RELAY`) on both fresh legacy data and no-legacy-data cases.
  - `FallbackBridgeClient`: primary success (fallback never called);
    primary `IOException` → fallback succeeds; both fail → the fallback's
    exception propagates; penalty window suppresses a second primary
    attempt within 30s of a recorded failure (inject a fake clock or expose
    the penalty check as an overridable seam, mirroring how other
    time-dependent tests in this codebase isolate `System.currentTimeMillis()`).
  - `PairingClient`/`pairInternal`: unchanged core-logic tests, parametrized
    over both slots to confirm the right `Settings`/`Session` instance is
    written.
  - `CmuxMessagingService`-adjacent: a test confirming `onNewToken` calls
    `bridgeClient(RELAY)` specifically, not whatever `activeBridge()`/a
    generic accessor would resolve to (regression test for the bug this
    project fixes).
- **Manual end-to-end:** pair both slots on a real phone; stop reachability
  to the relay (e.g. toggle the Mac off its home network, or block the
  relay's port) while keeping Tailscale up, and confirm the sessions list /
  terminal / feed replies keep working via automatic fallback with no user
  action; restore relay reachability and confirm it recovers within the
  30s penalty window; confirm push still arrives (relay slot registered);
  confirm a fresh install with only direct paired behaves exactly as today
  (no crash, no attempted relay calls beyond the initial null-check).

## Explicit non-goals (this project)

- Configurable priority (letting the user choose which slot is primary) —
  relay is always primary, per the Decisions section.
- Any bridge (Go) changes — confirmed unnecessary above.
- Push over direct mode — still out of scope, per the prior spec.
- Persisting "which slot last worked" across app restarts — in-memory only,
  a fresh process always retries relay first.
- A user-visible indicator of which slot is currently active — this is
  deliberately invisible/seamless per the "automatic" decision; can be
  revisited later if the user wants visibility into it.
