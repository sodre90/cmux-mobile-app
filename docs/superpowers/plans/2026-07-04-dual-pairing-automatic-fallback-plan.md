# Dual-Pairing Automatic Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Android app pair against both the relay and the direct
(Tailscale) listener at once, and automatically use whichever is reachable —
relay first, direct as a transparent fallback — with no manual switching.

**Architecture:** Android-only. `Settings`/`Session` become slot-parametrized
(`ConnectionSlot.RELAY`/`DIRECT`) instead of single-value; a new
`FallbackBridgeClient` wraps two `BridgeClient`s and tries relay first with a
short connect timeout, falling back to direct on failure and remembering
the failure for 30s; the events/terminal WebSocket reconnect loops alternate
slots the same way. No bridge (Go) changes.

**Tech Stack:** Kotlin, OkHttp (`MockWebServer` for tests), Jetpack Compose,
`EncryptedSharedPreferences`.

## Global Constraints

- Additive to existing behavior: a phone with only one slot paired (today's
  actual state for every existing install) must behave exactly as it does
  today — this is the primary regression risk across every task.
- No bridge (Go) changes. This is a client-side connection-selection
  feature only.
- Relay is always primary, direct is always fallback — not configurable.
- No new bridge-side or bridge-facing wire format changes: `PairingClient`'s
  wire protocol (`pairInternal`, `resolvePairingCode`) stays byte-for-byte
  identical; only which `Settings`/`Session` slot the result is written into
  changes.
- `Settings`/`Session` (backed by `EncryptedSharedPreferences`) cannot be
  constructed in a local JVM unit test in this codebase (no Robolectric is
  configured) — this is a pre-existing constraint, not one this project
  introduces. Push all genuinely testable logic into pure functions or
  classes that don't need a real `Context`/Keystore (`inferLegacySlot`,
  `FallbackBridgeClient`), and say so explicitly in a task's Verification
  step rather than silently skipping tests for files that could otherwise
  have them.
- Commits authored solely by `sodre90 <erdos.peter.bme@gmail.com>`. **Never**
  add a `Co-Authored-By` or any AI-attribution trailer to any commit message.
- Every task ends with `cd android && ./gradlew :app:testDebugUnitTest` and
  `./gradlew :app:compileDebugKotlin` (or a full `:app:assembleDebug` where a
  task's Verification step says so) passing clean.

---

### Task 1: `ConnectionSlot` enum + legacy-slot inference

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/ConnectionSlot.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/ConnectionSlotTest.kt`

**Interfaces:**
- Produces: `enum class ConnectionSlot { RELAY, DIRECT }` with `fun other(): ConnectionSlot`, and `fun inferLegacySlot(baseUrl: String): ConnectionSlot` — both consumed by every later task in this plan.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.sodre90.cmuxremote.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSlotTest {

    @Test
    fun otherFlipsBetweenRelayAndDirect() {
        assertEquals(ConnectionSlot.DIRECT, ConnectionSlot.RELAY.other())
        assertEquals(ConnectionSlot.RELAY, ConnectionSlot.DIRECT.other())
    }

    @Test
    fun inferLegacySlotClassifiesTsNetHostAsDirect() {
        assertEquals(ConnectionSlot.DIRECT, inferLegacySlot("https://macbook.sokoke-draco.ts.net:8443"))
    }

    @Test
    fun inferLegacySlotClassifiesOtherHostsAsRelay() {
        assertEquals(ConnectionSlot.RELAY, inferLegacySlot("https://sodre-cmux.mywire.org"))
    }

    @Test
    fun inferLegacySlotClassifiesUnparseableUrlAsRelay() {
        assertEquals(ConnectionSlot.RELAY, inferLegacySlot("not a url"))
    }

    @Test
    fun inferLegacySlotRejectsHostsThatMerelyContainTsNetSubstring() {
        // Only an exact ".ts.net" host suffix counts as a real Tailscale
        // MagicDNS name -- a host that just happens to contain that text
        // elsewhere must not be misclassified.
        assertEquals(ConnectionSlot.RELAY, inferLegacySlot("https://ts.net.evil.example.com"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.ConnectionSlotTest"`
Expected: FAIL — `ConnectionSlot`/`inferLegacySlot` are unresolved references.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.sodre90.cmuxremote.data

import java.net.URI

/** Which backend a request or pairing targets: the relay (always reachable
 *  from anywhere, requires the home server) or the direct Tailscale
 *  listener (requires Tailscale to be up on the phone, no home server
 *  involved). Relay is always primary, direct is always the fallback --
 *  see the 2026-07-04 dual-pairing design's Decisions section. */
enum class ConnectionSlot {
    RELAY,
    DIRECT,
    ;

    fun other(): ConnectionSlot = if (this == RELAY) DIRECT else RELAY
}

/** Classifies a pre-dual-pairing single stored base URL into the slot it
 *  most likely belongs to, for one-time migration off the old unprefixed
 *  Settings/Session keys (see Settings.migrateLegacyIfNeeded). Tailscale's
 *  MagicDNS names always end in ".ts.net"; anything else is assumed to be a
 *  relay URL -- the only other shape this app has ever stored. */
fun inferLegacySlot(baseUrl: String): ConnectionSlot {
    val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return ConnectionSlot.RELAY
    return if (host.endsWith(".ts.net")) ConnectionSlot.DIRECT else ConnectionSlot.RELAY
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.ConnectionSlotTest"`
Expected: PASS, 5/5.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/data/ConnectionSlot.kt android/app/src/test/java/com/sodre90/cmuxremote/data/ConnectionSlotTest.kt
git commit -m "android: add ConnectionSlot and legacy-slot inference"
```

---

### Task 2: `Settings.kt` per-slot storage + legacy migration

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt`

**Interfaces:**
- Consumes: `ConnectionSlot`, `inferLegacySlot` (Task 1).
- Produces: `Settings.bridgeConfig(slot): BridgeConfig?`, `Settings.setBaseUrl(slot, url)`, `Settings.setDeviceToken(slot, token)`, `Settings.migrateLegacyIfNeeded(): ConnectionSlot?` (called explicitly by `AppContainer` in Task 5 — NOT from `init` — because its result must be threaded into the matching `Session`'s own migration; see Task 3). The old unparametrized `baseUrl`/`deviceToken` properties and `bridgeConfig()` are removed — every call site is updated in later tasks.

There is no automated test for this file: `Settings` constructs a real
`EncryptedSharedPreferences` from a `Context`, which this codebase's JVM unit
tests cannot construct (no Robolectric configured — see Global Constraints,
and `PairingClientTest`'s own comment on why `Settings`/`Session` can't be
built in a local test). `inferLegacySlot`, the only genuinely new *logic*
here, is already covered by Task 1. This task is verified by full-suite
build/test passing (no regressions) plus the manual check in Step 3 below.

- [ ] **Step 1: Replace `Settings.kt`'s content**

```kotlin
package com.sodre90.cmuxremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists connection settings and secrets for both [ConnectionSlot]s. Everything
 * (including base URLs and tokens) lives in [EncryptedSharedPreferences] so
 * device certificates and bearer tokens are encrypted at rest; nothing here is
 * ever logged.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        // An upgrading install may still have the pre-pairing manual-setup
        // format's client-cert key on disk. Wipe the whole prefs file once
        // and force re-pairing. Self-terminating: nothing writes this key
        // again once cleared, so this branch never fires on later launches.
        if (prefs.contains(KEY_P12)) {
            prefs.edit().clear().apply()
        }
    }

    fun baseUrl(slot: ConnectionSlot): String? = prefs.getString(key(slot, KEY_BASE_URL), null)
    fun setBaseUrl(slot: ConnectionSlot, value: String) {
        prefs.edit().putString(key(slot, KEY_BASE_URL), value).apply()
    }

    fun deviceToken(slot: ConnectionSlot): String? = prefs.getString(key(slot, KEY_TOKEN), null)
    fun setDeviceToken(slot: ConnectionSlot, value: String) {
        prefs.edit().putString(key(slot, KEY_TOKEN), value).apply()
    }

    /** Assembles a [BridgeConfig] for [slot], or null if that slot has never
     *  been paired. */
    fun bridgeConfig(slot: ConnectionSlot): BridgeConfig? {
        val url = baseUrl(slot)?.takeIf { it.isNotBlank() } ?: return null
        val token = deviceToken(slot)?.takeIf { it.isNotBlank() } ?: return null
        return BridgeConfig(baseUrl = url, deviceToken = token)
    }

    /**
     * One-time migration from the pre-dual-pairing single {base_url,
     * device_token} pair into whichever slot it most likely belongs to (see
     * [inferLegacySlot]). Must be called explicitly by AppContainer (not
     * from init): its result tells AppContainer which Session instance
     * should absorb the matching legacy e2e session data, since Session has
     * no way to see the base URL and infer this on its own.
     *
     * Returns the slot migrated into, or null if there was nothing to
     * migrate (already migrated on a prior run, or a genuinely fresh
     * install). Self-terminating: always clears the legacy keys the first
     * time it finds data, so this never fires twice.
     */
    fun migrateLegacyIfNeeded(): ConnectionSlot? {
        val legacyUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val legacyToken = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val slot = inferLegacySlot(legacyUrl)
        prefs.edit()
            .putString(key(slot, KEY_BASE_URL), legacyUrl)
            .putString(key(slot, KEY_TOKEN), legacyToken)
            .remove(KEY_BASE_URL)
            .remove(KEY_TOKEN)
            .apply()
        return slot
    }

    private fun key(slot: ConnectionSlot, base: String) = "${slot.name.lowercase()}_$base"

    private companion object {
        const val PREFS_NAME = "cmux_secure_prefs"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_P12 = "client_p12_b64"
    }
}
```

- [ ] **Step 2: Confirm the module doesn't yet build**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: FAIL — every caller of the old `settings.baseUrl`/`settings.deviceToken`/`settings.bridgeConfig()` (in `AppContainer.kt`, `PairingClient.kt`, `CmuxNavHost.kt`) no longer compiles. This is expected and fixed by Tasks 5, 8, 9 — do not patch those files in this task.

- [ ] **Step 3: Manual verification note (no automated test possible here)**

Record in this task's report: confirm by inspection that `bridgeConfig(RELAY)`
and `bridgeConfig(DIRECT)` read/write fully independent keys (`relay_base_url`/
`relay_device_token` vs `direct_base_url`/`direct_device_token`), and that
`migrateLegacyIfNeeded()` reads the bare, unprefixed `base_url`/`device_token`
keys exactly once and always removes them — re-inspect the diff rather than
running a test, since none can exist for this file per Global Constraints.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt
git commit -m "android: make Settings per-ConnectionSlot with legacy migration"
```

(The module will not compile again until Task 5 updates `AppContainer`,
Task 8 updates `PairingClient`, and Task 9 updates `CmuxNavHost` — this is
expected for this intermediate commit; each of those tasks restores a
compiling state as it lands. If your environment requires a compiling state
at every commit, squash Tasks 2, 3, 5, 8, 9 into a single commit at the end
instead of committing here — note which approach you took in this task's
report.)

---

### Task 3: `Session.kt` per-slot storage + legacy migration

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Session.kt`

**Interfaces:**
- Consumes: `ConnectionSlot` (Task 1).
- Produces: `Session(context, slot)` constructor; `Session.absorbLegacyIfTarget(isMigrationTarget: Boolean)`, called explicitly by `AppContainer` (Task 5) with `isMigrationTarget = (slot == settings.migrateLegacyIfNeeded())`. `PairedSession` interface is unchanged.

No automated test for this file either, for the same reason as Task 2
(needs a real `Context`/Keystore). Verified by inspection + the full suite
passing.

- [ ] **Step 1: Replace `Session.kt`'s content**

```kotlin
package com.sodre90.cmuxremote.data.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sodre90.cmuxremote.data.ConnectionSlot

/** The subset of [Session] that [encryptBody]/[decryptBody]/[encryptFrame]/
 *  [decryptFrame] need -- lets tests substitute an in-memory fake. */
interface PairedSession {
    fun sharedSecret(): ByteArray?
    fun nextSendCounter(): Long
    fun canAcceptRecvCounter(n: Long): Boolean
    fun commitRecvCounter(n: Long)
}

/**
 * One paired-agent session for [slot]: the derived shared secret, a durable
 * monotonic send counter, and the sliding-window receive gate. The phone
 * pairs with exactly one agent per slot at a time -- re-pairing that slot
 * overwrites its own record, but the other slot's session is untouched
 * (both slots' keys share one prefs file, distinguished only by prefix).
 */
class Session(context: Context, private val slot: ConnectionSlot) : PairedSession {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Migrates the pre-dual-pairing single e2e session record into this
     * instance's slot, if [isMigrationTarget] is true. AppContainer decides
     * this once (from [com.sodre90.cmuxremote.data.Settings.migrateLegacyIfNeeded]'s
     * result), since a Session has no way to see the base URL its legacy
     * pairing belonged to and infer the right slot on its own. No-op if
     * there's no legacy record. Self-terminating: always clears the legacy
     * keys the first time it finds data.
     */
    fun absorbLegacyIfTarget(isMigrationTarget: Boolean) {
        if (!isMigrationTarget) return
        if (!prefs.contains(KEY_SHARED_SECRET)) return
        prefs.edit()
            .putString(key(KEY_PEER_PUBLIC_KEY), prefs.getString(KEY_PEER_PUBLIC_KEY, null))
            .putString(key(KEY_SHARED_SECRET), prefs.getString(KEY_SHARED_SECRET, null))
            .putLong(key(KEY_SEND_COUNTER), prefs.getLong(KEY_SEND_COUNTER, 0L))
            .putLong(key(KEY_RECV_HIGHEST), prefs.getLong(KEY_RECV_HIGHEST, -1L))
            .putLong(key(KEY_RECV_WINDOW_BITS), prefs.getLong(KEY_RECV_WINDOW_BITS, 0L))
            .remove(KEY_PEER_PUBLIC_KEY)
            .remove(KEY_SHARED_SECRET)
            .remove(KEY_SEND_COUNTER)
            .remove(KEY_RECV_HIGHEST)
            .remove(KEY_RECV_WINDOW_BITS)
            .apply()
    }

    fun isPaired(): Boolean = prefs.contains(key(KEY_SHARED_SECRET))

    override fun sharedSecret(): ByteArray? =
        prefs.getString(key(KEY_SHARED_SECRET), null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Called once, by [com.sodre90.cmuxremote.data.pairing.PairingClient] after a
     *  successful pairing handshake. Resets counters and the replay window --
     *  a fresh pairing means a fresh shared secret, so old counter state is
     *  meaningless (and reusing it would incorrectly reject the first messages). */
    fun setPairing(peerPublicKey: ByteArray, sharedSecret: ByteArray) {
        prefs.edit()
            .putString(key(KEY_PEER_PUBLIC_KEY), Base64.encodeToString(peerPublicKey, Base64.NO_WRAP))
            .putString(key(KEY_SHARED_SECRET), Base64.encodeToString(sharedSecret, Base64.NO_WRAP))
            .putLong(key(KEY_SEND_COUNTER), 0L)
            .putLong(key(KEY_RECV_HIGHEST), -1L)
            .putLong(key(KEY_RECV_WINDOW_BITS), 0L)
            .apply()
    }

    /** Durable, never reset across reconnects. */
    override fun nextSendCounter(): Long {
        val n = prefs.getLong(key(KEY_SEND_COUNTER), 0L)
        prefs.edit().putLong(key(KEY_SEND_COUNTER), n + 1).apply()
        return n
    }

    private fun replayWindow(): ReplayWindow =
        ReplayWindow(prefs.getLong(key(KEY_RECV_HIGHEST), -1L), prefs.getLong(key(KEY_RECV_WINDOW_BITS), 0L))

    /** Read-only check -- call before attempting to decrypt. */
    override fun canAcceptRecvCounter(n: Long): Boolean = replayWindow().canAccept(n)

    /** Mutating -- call only after the corresponding ciphertext has verified. */
    override fun commitRecvCounter(n: Long) {
        val updated = replayWindow().commit(n)
        prefs.edit()
            .putLong(key(KEY_RECV_HIGHEST), updated.highestSeen)
            .putLong(key(KEY_RECV_WINDOW_BITS), updated.windowBits)
            .apply()
    }

    /** Wipes this slot's session only -- used when re-pairing this slot. The
     *  other slot's session (sharing the same prefs file) is untouched. */
    fun clear() {
        prefs.edit()
            .remove(key(KEY_PEER_PUBLIC_KEY))
            .remove(key(KEY_SHARED_SECRET))
            .remove(key(KEY_SEND_COUNTER))
            .remove(key(KEY_RECV_HIGHEST))
            .remove(key(KEY_RECV_WINDOW_BITS))
            .apply()
    }

    private fun key(base: String) = "${slot.name.lowercase()}_$base"

    private companion object {
        const val PREFS_NAME = "cmux_e2e_session"
        const val KEY_PEER_PUBLIC_KEY = "device_public_key_b64"
        const val KEY_SHARED_SECRET = "shared_secret_b64"
        const val KEY_SEND_COUNTER = "send_counter"
        const val KEY_RECV_HIGHEST = "recv_highest"
        const val KEY_RECV_WINDOW_BITS = "recv_window_bits"
    }
}
```

- [ ] **Step 2: Confirm the module doesn't yet build**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: FAIL — `AppContainer.kt` still constructs `Session(appContext)` with
the old one-arg constructor. Expected until Task 5.

- [ ] **Step 3: Manual verification note**

Confirm by inspection: `clear()` now only removes this slot's five keys
(not the whole prefs file), and `absorbLegacyIfTarget` only fires when
`isMigrationTarget` is true, never guessing the slot on its own.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Session.kt
git commit -m "android: make Session per-ConnectionSlot with legacy migration"
```

---

### Task 4: `FallbackBridgeClient`

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/FallbackBridgeClient.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/FallbackBridgeClientTest.kt`

**Interfaces:**
- Consumes: `BridgeClient`, `BridgeException` (existing, unchanged).
- Produces: `FallbackBridgeClient(primary: () -> BridgeClient?, fallback: () -> BridgeClient?, now: () -> Long = System::currentTimeMillis)` with `sessions()`, `pendingFeed()`, `replyFeed()`, `renameWorkspace()`, `setYoloMode()` — same signatures as the matching `BridgeClient` methods. Consumed by `AppContainer.activeBridge()` in Task 5, and by `SessionsViewModel`/`InboxViewModel`/`TerminalViewModel` in Task 7. Deliberately does **not** expose `registerDevice` — push registration always targets the relay slot's `BridgeClient` directly (Task 6), never this wrapper.

This class takes no `Context`/persistence dependency, so it's fully unit
testable with `MockWebServer`, unlike Tasks 2/3.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.sodre90.cmuxremote.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class FallbackBridgeClientTest {

    private lateinit var primaryServer: MockWebServer
    private lateinit var fallbackServer: MockWebServer

    @Before
    fun setUp() {
        primaryServer = MockWebServer().apply { start() }
        fallbackServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        primaryServer.shutdown()
        fallbackServer.shutdown()
    }

    /** connectTimeoutMs is also used as the read timeout, so a NO_RESPONSE
     *  MockResponse (server accepts the connection but never replies) fails
     *  fast and deterministically instead of hanging for OkHttp's real
     *  10s default. */
    private fun clientFor(server: MockWebServer, connectTimeoutMs: Long = 2_000): BridgeClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return BridgeClient(http, server.url("/").toString())
    }

    @Test
    fun primarySuccessNeverCallsFallback() {
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(primary = { clientFor(primaryServer) }, fallback = { clientFor(fallbackServer) })

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, primaryServer.requestCount)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun primaryFailureFallsBackAndSucceeds() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer) },
        )

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, primaryServer.requestCount)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun bothFailPropagatesException() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fallbackServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer, connectTimeoutMs = 300) },
        )

        try {
            runBlocking { fb.sessions() }
            fail("expected an exception when both primary and fallback fail")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun onlyPrimaryConfiguredUsesPrimaryDirectly() {
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(primary = { clientFor(primaryServer) }, fallback = { null })

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, primaryServer.requestCount)
    }

    @Test
    fun onlyPrimaryConfiguredPropagatesFailureWhenNoFallback() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fb = FallbackBridgeClient(primary = { clientFor(primaryServer, connectTimeoutMs = 300) }, fallback = { null })

        try {
            runBlocking { fb.sessions() }
            fail("expected the primary's failure to propagate when there's no fallback")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun onlyFallbackConfiguredUsesFallbackDirectly() {
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        val fb = FallbackBridgeClient(primary = { null }, fallback = { clientFor(fallbackServer) })

        val result = runBlocking { fb.sessions() }

        assertEquals(0, result.size)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun penaltyWindowSkipsPrimaryUntilItExpires() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}""")) // must NOT be consumed by the 2nd call
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        var clock = 1_000_000L
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer) },
            now = { clock },
        )

        runBlocking { fb.sessions() } // primary times out, falls back, sets 30s penalty
        clock += 10_000L // still inside the window
        runBlocking { fb.sessions() } // must skip primary entirely

        assertEquals(1, primaryServer.requestCount)
        assertEquals(2, fallbackServer.requestCount)
    }

    @Test
    fun penaltyWindowExpiresAndRetriesPrimary() {
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        primaryServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        fallbackServer.enqueue(MockResponse().setBody("""{"workspaces":[]}"""))
        var clock = 1_000_000L
        val fb = FallbackBridgeClient(
            primary = { clientFor(primaryServer, connectTimeoutMs = 300) },
            fallback = { clientFor(fallbackServer) },
            now = { clock },
        )

        runBlocking { fb.sessions() } // primary times out, falls back, sets 30s penalty
        clock += 31_000L // past the window

        runBlocking { fb.sessions() } // must retry primary, which now succeeds

        assertEquals(2, primaryServer.requestCount)
        assertEquals(1, fallbackServer.requestCount)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.FallbackBridgeClientTest"`
Expected: FAIL — `FallbackBridgeClient` is an unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.FeedReply
import java.io.IOException

/**
 * Wraps a primary (relay) and fallback (direct) [BridgeClient] supplier.
 * Every call tries primary first and transparently retries against
 * fallback on an [IOException] (primary unreachable/timeout), remembering
 * the failure for [PENALTY_MS] so a dead relay isn't re-tried on every
 * single call. The penalty is in-memory only -- a fresh process always
 * tries primary first again.
 */
class FallbackBridgeClient(
    private val primary: () -> BridgeClient?,
    private val fallback: () -> BridgeClient?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var primaryDownUntil: Long = 0L

    private suspend fun <T> call(block: suspend (BridgeClient) -> T): T {
        val primaryClient = primary()
        val fallbackClient = fallback()

        // Skip a doomed primary attempt if it's not configured at all, or
        // we recently confirmed it's down (still inside the penalty window).
        val skipPrimary = primaryClient == null || now() < primaryDownUntil
        if (skipPrimary) {
            return block(fallbackClient ?: primaryClient ?: throw BridgeException(0, "not configured"))
        }

        return try {
            block(primaryClient)
        } catch (e: IOException) {
            if (fallbackClient == null) throw e
            primaryDownUntil = now() + PENALTY_MS
            block(fallbackClient)
        }
    }

    suspend fun sessions() = call { it.sessions() }
    suspend fun pendingFeed() = call { it.pendingFeed() }
    suspend fun replyFeed(feedId: String, reply: FeedReply) = call { it.replyFeed(feedId, reply) }
    suspend fun renameWorkspace(id: String, title: String) = call { it.renameWorkspace(id, title) }
    suspend fun setYoloMode(id: String, mode: String) = call { it.setYoloMode(id, mode) }

    private companion object {
        const val PENALTY_MS = 30_000L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.FallbackBridgeClientTest"`
Expected: PASS, 8/8.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/data/FallbackBridgeClient.kt android/app/src/test/java/com/sodre90/cmuxremote/data/FallbackBridgeClientTest.kt
git commit -m "android: add FallbackBridgeClient (try relay, fall back to direct)"
```

---

### Task 5: `AppContainer.kt` wiring

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/AppContainer.kt`

**Interfaces:**
- Consumes: `ConnectionSlot`, `Settings` (Task 2), `Session` (Task 3), `FallbackBridgeClient` (Task 4).
- Produces: `bridgeClient(slot)`, `eventsSocket(slot)`, `terminalSocket(slot, surfaceId)`, `pairingClient(slot)`, `activeBridge(): FallbackBridgeClient?`, `anyBridgeConfigured(): Boolean`. The old unparametrized `bridgeClient()`/`eventsSocket()`/`terminalSocket(id)`/`pairingClient()` are removed. This restores a compiling module (Tasks 2–4 leave it broken by design).

No automated test for this file (same `Context` constraint as Tasks 2/3;
`AppContainer` has no existing test file either). Verified by the full
suite + a full `:app:assembleDebug` build passing.

- [ ] **Step 1: Replace `AppContainer.kt`'s content**

```kotlin
package com.sodre90.cmuxremote.data

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.E2eInterceptor
import com.sodre90.cmuxremote.data.e2e.Identity
import com.sodre90.cmuxremote.data.e2e.Session
import com.sodre90.cmuxremote.data.pairing.PairingClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container held by [com.sodre90.cmuxremote.CmuxApp]. Builds one
 * [OkHttpClient] per [ConnectionSlot] (bearer token + opt-in e2e encryption) and
 * hands out slot-scoped bridge clients/sockets, plus [activeBridge] for
 * callers that just want "whichever works right now."
 */
class AppContainer(appContext: Context) {

    val settings = Settings(appContext)
    val identity = Identity(appContext)
    val cipher = Cipher(LazySodiumAndroid(SodiumAndroid()))
    val workspaceOrderStore = WorkspaceOrderStore(appContext)

    private val sessions: Map<ConnectionSlot, Session> =
        ConnectionSlot.entries.associateWith { Session(appContext, it) }

    init {
        // One-time migration off the pre-dual-pairing single-slot format:
        // Settings decides which slot the legacy {base_url, device_token}
        // belongs to (it can see the URL); that same slot's Session then
        // absorbs the matching legacy e2e record (it can't see the URL, so
        // it can't decide this on its own).
        val migratedSlot = settings.migrateLegacyIfNeeded()
        sessions.forEach { (slot, session) -> session.absorbLegacyIfTarget(slot == migratedSlot) }
    }

    private val clients = mutableMapOf<ConnectionSlot, Pair<String, OkHttpClient>>()

    @Synchronized
    private fun httpClient(slot: ConnectionSlot, cfg: BridgeConfig): OkHttpClient {
        val session = sessions.getValue(slot)
        val key = "${cfg.baseUrl}|${cfg.deviceToken}|${session.isPaired()}"
        clients[slot]?.let { (cachedKey, cachedClient) -> if (cachedKey == key) return cachedClient }

        var builder = Mtls.client(cfg).newBuilder()
        if (slot == ConnectionSlot.RELAY) {
            // Only the connect phase needs a short leash: an unreachable
            // relay fails the TCP handshake almost immediately, so 3s is
            // generous for a real failure while still keeping the UI from
            // stalling on a dead home server. A slow-but-reachable response
            // (cmux itself being slow) is a different problem and must not
            // trip a spurious failover, so read/write timeouts stay at
            // OkHttp's defaults. Direct has no second fallback to race
            // against, so it keeps the normal (longer) connect timeout too.
            builder = builder.connectTimeout(3, TimeUnit.SECONDS)
        }
        var built = builder.build()
        if (session.isPaired()) {
            built = built.newBuilder().addInterceptor(E2eInterceptor(session, cipher)).build()
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

    /** Unauthenticated -- POST /devices/pair takes no bearer token (see
     *  bridge/internal/relay/relay.go's handleDevicePair). */
    fun pairingClient(slot: ConnectionSlot): PairingClient =
        PairingClient(OkHttpClient(), identity, sessions.getValue(slot), settings, slot)

    /** The fallback-aware entry point most read/write call sites should use
     *  instead of bridgeClient(slot) directly. Null only when neither slot
     *  is paired yet (matches the old single-slot bridgeClient()'s null
     *  contract, so existing "Bridge not configured" call sites need no
     *  shape change). */
    fun activeBridge(): FallbackBridgeClient? {
        if (!anyBridgeConfigured()) return null
        return FallbackBridgeClient(
            primary = { bridgeClient(ConnectionSlot.RELAY) },
            fallback = { bridgeClient(ConnectionSlot.DIRECT) },
        )
    }

    fun anyBridgeConfigured(): Boolean = ConnectionSlot.entries.any { settings.bridgeConfig(it) != null }
}
```

- [ ] **Step 2: Build**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: still FAIL — `PairingClient`'s constructor doesn't yet accept a
`slot` parameter (Task 8), and `CmuxNavHost.kt` still calls the old
zero-arg accessors (Task 9). This is expected; both are fixed by their own
tasks.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/data/AppContainer.kt
git commit -m "android: wire AppContainer for per-slot clients and activeBridge()"
```

---

### Task 6: Pin push registration to the relay slot

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/push/CmuxMessagingService.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/MainActivity.kt`

**Interfaces:**
- Consumes: `AppContainer.bridgeClient(ConnectionSlot.RELAY)` (Task 5).

This is the correctness fix motivating this whole project (see the plan's
parent spec's Context section): today, both of the app's two independent
push-registration call sites register against whatever `bridgeClient()`
happens to be configured; direct mode has no `/devices/register` endpoint
at all, so a token (re-)registration while direct is the only paired slot
would silently and permanently break push. There are two call sites, not
one — `CmuxMessagingService.onNewToken()` (fires only when FCM actually
issues a new token) and `MainActivity.registerFcmToken()` (fires on every
app launch, re-registering the *current* token as a belt-and-suspenders
measure independent of whether it changed) — both need the same fix. No
automated test exists for either class today (`FirebaseMessagingService`/
`ComponentActivity` both need Android's framework) — verified by inspection
+ manual check in Step 3.

- [ ] **Step 1: Change `CmuxMessagingService.onNewToken`**

```kotlin
    override fun onNewToken(token: String) {
        val container = (application as? CmuxApp)?.container ?: return
        scope.launch {
            try {
                container.bridgeClient(ConnectionSlot.RELAY)?.registerDevice(token)
            } catch (_: Exception) {
                // Bridge unreachable or unconfigured; token is resent on next start.
            }
        }
    }
```

And add the import:

```kotlin
import com.sodre90.cmuxremote.data.ConnectionSlot
```

- [ ] **Step 2: Change `MainActivity.registerFcmToken`**

```kotlin
    private fun registerFcmToken() {
        val container = (application as CmuxApp).container
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                scope.launch {
                    try {
                        container.bridgeClient(ConnectionSlot.RELAY)?.registerDevice(token)
                    } catch (_: Exception) {
                        // Bridge unreachable or unconfigured; retried next launch.
                    }
                }
            }
        } catch (_: Throwable) {
            // Firebase not configured (no google-services.json); push inactive.
        }
    }
```

(Only the `container.bridgeClient()` call in the inner `scope.launch` block
changes — everything else in the method, and the rest of the file, is
unchanged.) Add the same import:

```kotlin
import com.sodre90.cmuxremote.data.ConnectionSlot
```

- [ ] **Step 3: Build**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: the module still does NOT fully compile at this point — Task 8
(`PairingClient.kt`) and Task 9 (`CmuxNavHost.kt`) haven't landed yet, so
their call sites are still broken. This is expected, not a regression.
What matters is that neither `CmuxMessagingService.kt` nor `MainActivity.kt`
themselves introduce any *new* unresolved reference beyond what already
existed before this task — confirm this by reading the compiler's error
output and checking every error is attributed to `PairingClient.kt`,
`CmuxNavHost.kt`, or another already-known pending file, not to either file
this task touched. Paste the full remaining error output in your report.

- [ ] **Step 4: Manual verification note**

Confirm by inspection, for BOTH files: if only `DIRECT` is paired (relay
slot returns null), `bridgeClient(ConnectionSlot.RELAY)` is null,
`?.registerDevice` short-circuits to nothing, and the existing `catch` is
never even reached — this is the intended, correct behavior (push stays
unavailable until the relay slot is paired too, rather than the previous
silent-and-wrong behavior of calling an endpoint that doesn't exist on
whatever was active).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/push/CmuxMessagingService.kt android/app/src/main/java/com/sodre90/cmuxremote/MainActivity.kt
git commit -m "android: pin push registration to the relay slot"
```

---

### Task 7: Slot-fallback wiring in Sessions/Inbox/Terminal ViewModels

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsViewModel.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/inbox/InboxViewModel.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt`

**Interfaces:**
- Consumes: `AppContainer.activeBridge()`, `AppContainer.eventsSocket(slot)`, `AppContainer.terminalSocket(slot, id)`, `AppContainer.anyBridgeConfigured()`, `ConnectionSlot.other()` (Tasks 1, 4, 5).

These three files apply the identical pattern (try relay's socket, on
failure flip to direct, remember the working slot for the next reconnect
attempt) to three call sites — deliberately kept as three small, near-
identical diffs rather than one shared abstraction, matching this
codebase's existing style of three independent reconnect loops rather than
a generic reconnector (see `SessionsViewModel`'s pre-existing
`subscribeToEvents`/`InboxViewModel`'s near-identical loop/
`TerminalViewModel`'s own loop — none of the three share code today
either). `SessionsLogicTest.kt`'s existing pure-function tests
(`singlePaneTarget`, `paneCountLabel`, `needsAttention`) are untouched by
this task and must still pass unchanged.

- [ ] **Step 1: `SessionsViewModel.kt`** — replace every `container.bridgeClient()`
call with `container.activeBridge()`, and rewrite `subscribeToEvents()`:

```kotlin
    private fun subscribeToEvents() {
        if (!container.anyBridgeConfigured()) return
        viewModelScope.launch {
            var preferred = ConnectionSlot.RELAY
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                val events = container.eventsSocket(preferred) ?: container.eventsSocket(preferred.other())
                if (events == null) { delay(backoff); continue } // shouldn't happen given the guard above
                try {
                    events.connect().collect { frame ->
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
                refreshRequests.tryEmit(Unit) // catch up on anything missed while disconnected
            }
        }
    }
```

Add the import `com.sodre90.cmuxremote.data.ConnectionSlot`. `renameWorkspace`,
`setYoloMode`, `refresh`, `silentRefresh`, `autoRefresh`,
`fetchSessionsWithPairingRetry` each change their
`val client = container.bridgeClient() ?: run { _actionError.value = "Bridge not configured"; return }`
(or the equivalent `?:`-return in `refresh`) to
`val client = container.activeBridge() ?: ...` — same shape, only the accessor
name changes, since `activeBridge()` preserves the same nullable contract.

- [ ] **Step 2: `InboxViewModel.kt`** — same two changes:

```kotlin
class InboxViewModel(container: AppContainer) : ViewModel() {

    private val client = container.activeBridge()

    private val _items = MutableStateFlow<List<PendingFeedItem>>(emptyList())
    val items: StateFlow<List<PendingFeedItem>> = _items.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
        if (container.anyBridgeConfigured()) {
            viewModelScope.launch {
                var preferred = ConnectionSlot.RELAY
                var backoff = INITIAL_BACKOFF_MS
                while (isActive) {
                    val events = container.eventsSocket(preferred) ?: container.eventsSocket(preferred.other())
                    if (events == null) { delay(backoff); continue }
                    try {
                        events.connect().collect { frame ->
                            backoff = INITIAL_BACKOFF_MS
                            if (frame.type == "feed" && frame.needsAttention) refresh()
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (_: Exception) {
                        preferred = preferred.other()
                    }
                    if (!isActive) break
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    refresh()
                }
            }
        }
    }
```

(`client` is now assigned once from `container.activeBridge()` instead of
`container.bridgeClient()` — no other change to `refresh()`/`reply()`,
which already null-check `client` the same way. Add the same
`ConnectionSlot` import.)

- [ ] **Step 3: `TerminalViewModel.kt`** — track the currently-connected socket
so `sendText`/`resize`/`onCleared` route to whichever slot is actually live:

```kotlin
class TerminalViewModel(
    private val container: AppContainer,
    private val surfaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var preferredSlot = ConnectionSlot.RELAY

    @Volatile
    private var activeSocket: TerminalSocket? = null

    private val _yoloMode = MutableStateFlow("")
    val yoloMode: StateFlow<String> = _yoloMode.asStateFlow()

    init {
        connect()
        loadYoloMode()
    }

    private fun loadYoloMode() {
        val client = container.activeBridge() ?: return
        viewModelScope.launch {
            try {
                val ws = client.sessions().firstOrNull { ws -> ws.terminals.any { it.id == surfaceId } }
                _yoloMode.value = ws?.yoloMode.orEmpty()
            } catch (_: Exception) {
                // Best-effort display only; leave it blank on failure.
            }
        }
    }

    fun reconnect() = connect()

    private fun connect() {
        if (!container.anyBridgeConfigured()) {
            _state.value = TerminalUiState(error = "Bridge not configured")
            return
        }
        job?.cancel()
        _state.value = TerminalUiState() // loading (grid == null, error == null)
        job = viewModelScope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                val socket = container.terminalSocket(preferredSlot, surfaceId)
                    ?: container.terminalSocket(preferredSlot.other(), surfaceId)
                if (socket == null) { delay(backoff); continue }
                activeSocket = socket
                try {
                    socket.connect().collect { frame ->
                        val rg = frame.grid ?: return@collect
                        backoff = INITIAL_BACKOFF_MS
                        _state.value = TerminalUiState(
                            grid = RenderGridDecoder.decode(rg),
                            styles = rg.styles,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    preferredSlot = preferredSlot.other()
                }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun sendText(text: String) {
        activeSocket?.send(TerminalUp(type = "input", text = text))
    }

    fun resize(columns: Int, rows: Int) {
        activeSocket?.send(TerminalUp(type = "resize", columns = columns, rows = rows))
    }

    override fun onCleared() {
        activeSocket?.close()
    }
}
```

Add the same `ConnectionSlot` import. Note `container`/`surfaceId` become
constructor `private val`s (they were plain parameters before) since
`connect()`/`loadYoloMode()` are now called with no arguments instead of
threading `container`/`surfaceId` through explicitly — a small, intentional
cleanup enabled by this change, not a functional difference.

- [ ] **Step 4: Build and run the full suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: PASS. `SessionsLogicTest` (10 pre-existing tests) must be
unaffected since none of its pure helper functions changed.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsViewModel.kt android/app/src/main/java/com/sodre90/cmuxremote/ui/inbox/InboxViewModel.kt android/app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt
git commit -m "android: alternate relay/direct slots in reconnect loops"
```

---

### Task 8: Pairing flow slot-awareness

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingClient.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingViewModel.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingScreen.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingClientTest.kt` (existing — extend, don't replace)

**Interfaces:**
- Consumes: `ConnectionSlot`, `AppContainer.pairingClient(slot)` (Tasks 1, 5).
- Produces: `PairingClient(http, identity, session, settings, slot)`; `PairingViewModel(container, slot)`; `PairingScreen(vm, title, onPaired)`. The free functions `pairInternal`/`resolvePairingCode` are **unchanged** — only what closes over them changes.

- [ ] **Step 1: `PairingClient.kt`** — add the `slot` parameter and change the
two persistence callbacks:

```kotlin
class PairingClient(
    private val http: OkHttpClient,
    private val identity: Identity,
    private val session: Session,
    private val settings: Settings,
    private val slot: ConnectionSlot,
) {
    suspend fun pair(qr: PairingQr) = pairInternal(
        http = http,
        qr = qr,
        phonePrivateKey = identity.privateKey,
        phonePublicKey = identity.publicKey,
        onSetPairing = session::setPairing,
        onSetBaseUrl = { settings.setBaseUrl(slot, it) },
        onSetToken = { settings.setDeviceToken(slot, it) },
    )

    suspend fun resolveManualCode(serverUrl: String, code: String): PairingQr =
        resolvePairingCode(http, serverUrl, code)
}
```

Add the import `com.sodre90.cmuxremote.data.ConnectionSlot`. `pairInternal`
and `resolvePairingCode` (the free functions below this class in the same
file) are untouched.

- [ ] **Step 2: Extend `PairingClientTest.kt`**

The existing tests construct `TestablePairingClient` (a test-only wrapper
around the free `pairInternal` function with fake callbacks) — those are
unaffected, since `pairInternal` didn't change. Add one new test confirming
the real `PairingClient` class writes into the slot it was built with, not
a hardcoded one:

```kotlin
    @Test
    fun pairWritesIntoTheConstructedSlotNotAHardcodedOne() {
        val (agentPriv, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()
        server.enqueue(MockResponse().setBody("""{"token":"tok-direct","tenant_id":"t1"}"""))

        var recordedBaseUrlSlot: com.sodre90.cmuxremote.data.ConnectionSlot? = null
        var recordedTokenSlot: com.sodre90.cmuxremote.data.ConnectionSlot? = null
        val fakeSettingsSetBaseUrl = { slot: com.sodre90.cmuxremote.data.ConnectionSlot, _: String -> recordedBaseUrlSlot = slot }
        val fakeSettingsSetToken = { slot: com.sodre90.cmuxremote.data.ConnectionSlot, _: String -> recordedTokenSlot = slot }

        val slot = com.sodre90.cmuxremote.data.ConnectionSlot.DIRECT
        val client = TestablePairingClient(
            http = http,
            phonePrivateKey = phonePriv,
            phonePublicKey = phonePub,
            onSetPairing = { _, _ -> },
            onSetBaseUrl = { fakeSettingsSetBaseUrl(slot, it) },
            onSetToken = { fakeSettingsSetToken(slot, it) },
        )
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(),
            code = "CODE1",
            agentPubkey = Base64.getEncoder().encodeToString(agentPub),
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t1",
        )

        runBlocking { client.pair(qr) }

        assertEquals(slot, recordedBaseUrlSlot)
        assertEquals(slot, recordedTokenSlot)
    }
```

(This exercises the same closure shape the real `PairingClient.pair()` uses
— `onSetBaseUrl`/`onSetToken` each closing over a fixed `slot` — via the
existing `TestablePairingClient` seam (constructor confirmed at
`PairingClientTest.kt:152`: `http, phonePrivateKey, phonePublicKey,
onSetPairing, onSetBaseUrl, onSetToken`), without needing a real `Settings`
instance.)

- [ ] **Step 3: `PairingViewModel.kt`** — add the `slot` parameter:

```kotlin
class PairingViewModel(private val container: AppContainer, private val slot: ConnectionSlot) : ViewModel() {
```

Every `container.pairingClient()` call in this file becomes
`container.pairingClient(slot)`. Add the `ConnectionSlot` import.

- [ ] **Step 4: `PairingScreen.kt`** — add a `title` parameter:

```kotlin
@Composable
fun PairingScreen(vm: PairingViewModel, title: String, onPaired: () -> Unit) {
    ...
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { inner ->
```

(Only the function signature and the `TopAppBar`'s `Text(title)` change —
everything else in the file is unchanged.)

- [ ] **Step 5: Build and test**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.pairing.PairingClientTest" :app:compileDebugKotlin`
Expected: `CmuxNavHost.kt` still won't compile (it constructs
`PairingViewModel(container)`/`PairingScreen(vm, onPaired)` with the old
arities) — expected until Task 9. `PairingClientTest` itself: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingClient.kt android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingViewModel.kt android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingScreen.kt android/app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingClientTest.kt
git commit -m "android: make pairing flow ConnectionSlot-aware"
```

---

### Task 9: `ConnectionSettingsScreen` + navigation wiring

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/ConnectionSettingsScreen.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/Routes.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt`

**Interfaces:**
- Consumes: `ConnectionSlot`, `AppContainer.anyBridgeConfigured()`, `AppContainer.settings.bridgeConfig(slot)`, `PairingViewModel(container, slot)`, `PairingScreen(vm, title, onPaired)` (Tasks 1, 5, 8).

This is the last task — after it lands, the whole module (including Tasks
2's/3's/5's/8's intermediate non-compiling states, if committed separately)
compiles and passes its full test suite again.

- [ ] **Step 1: `Routes.kt`** — add the per-slot pairing route:

```kotlin
package com.sodre90.cmuxremote.ui

import com.sodre90.cmuxremote.data.ConnectionSlot

/** Navigation route constants. */
object Routes {
    const val SETTINGS = "settings"
    const val SESSIONS = "sessions"
    const val INBOX = "inbox"
    const val TERMINAL = "terminal" // terminal/{id}
    const val PAIR = "pair" // pair/{slot}

    fun terminal(surfaceId: String) = "$TERMINAL/$surfaceId"
    fun pair(slot: ConnectionSlot) = "$PAIR/${slot.name.lowercase()}"
}
```

- [ ] **Step 2: Create `ConnectionSettingsScreen.kt`**

```kotlin
package com.sodre90.cmuxremote.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.data.ConnectionSlot

/** Replaces the old single-pairing Settings screen: shows both
 *  [ConnectionSlot]s' paired/unpaired status side by side, each with its
 *  own (re)pair action, so the user can see at a glance whether they have
 *  the automatic-fallback benefit (both paired) or just one transport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    relayConfigured: Boolean,
    directConfigured: Boolean,
    onPair: (ConnectionSlot) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Connections") }) }) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionRow(
                label = "Relay",
                description = "Reaches your Mac from anywhere, via the home server.",
                configured = relayConfigured,
                onPair = { onPair(ConnectionSlot.RELAY) },
            )
            ConnectionRow(
                label = "Tailscale (direct)",
                description = "Reaches your Mac directly over your tailnet -- used automatically if the relay is unreachable.",
                configured = directConfigured,
                onPair = { onPair(ConnectionSlot.DIRECT) },
            )
            if (relayConfigured || directConfigured) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ConnectionRow(label: String, description: String, configured: Boolean, onPair: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label)
            Text(description)
            Text(if (configured) "Paired" else "Not paired")
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                Text(if (configured) "Re-pair" else "Pair")
            }
        }
    }
}
```

- [ ] **Step 3: `CmuxNavHost.kt`** — replace the `Routes.SETTINGS` composable,
add the new `Routes.PAIR` route, and update the start-destination check:

```kotlin
    val configured = container.anyBridgeConfigured()
    val start = if (!configured) Routes.SETTINGS else Routes.SESSIONS
```

```kotlin
        composable(Routes.SETTINGS) {
            ConnectionSettingsScreen(
                relayConfigured = container.settings.bridgeConfig(ConnectionSlot.RELAY) != null,
                directConfigured = container.settings.bridgeConfig(ConnectionSlot.DIRECT) != null,
                onPair = { slot -> navController.navigate(Routes.pair(slot)) },
                onDone = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = "${Routes.PAIR}/{slot}",
            arguments = listOf(navArgument("slot") { type = NavType.StringType }),
        ) { entry ->
            val slot = ConnectionSlot.valueOf(entry.arguments?.getString("slot").orEmpty().uppercase())
            val vm: PairingViewModel = viewModel(
                factory = viewModelFactory { initializer { PairingViewModel(container, slot) } },
            )
            PairingScreen(
                vm = vm,
                title = if (slot == ConnectionSlot.RELAY) "Pair via relay" else "Pair via Tailscale (direct)",
                onPaired = { navController.popBackStack() }, // back to ConnectionSettingsScreen, now showing this slot as paired
            )
        }
```

Add the imports `com.sodre90.cmuxremote.data.ConnectionSlot` and
`com.sodre90.cmuxremote.ui.pairing.ConnectionSettingsScreen`.

- [ ] **Step 4: Full build and test**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS — this restores a fully compiling, fully-tested module for
the whole feature.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/ConnectionSettingsScreen.kt android/app/src/main/java/com/sodre90/cmuxremote/ui/Routes.kt android/app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt
git commit -m "android: add ConnectionSettingsScreen for dual-slot pairing"
```

---

## Verification

- `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` clean
  across the whole module (not just per-task).
- Manual, on a real device (per the parent spec's Testing section): pair
  both slots; disconnect the Mac from its home network (or block the
  relay's port) while keeping Tailscale up, and confirm sessions/terminal/
  feed replies keep working via automatic fallback with no user action;
  restore relay reachability and confirm the app recovers within ~30s;
  confirm push still arrives (relay slot registered); confirm a phone with
  only one slot paired (today's real state, until you re-pair the relay
  slot once after this ships) behaves exactly as before.
