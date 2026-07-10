# `security-crypto` migration: direction and in-place data migration design

## Context

`docs/improvement-guide.md` §8 (Phase 5 — Polish backlog) flags
`androidx.security.crypto` (the `EncryptedSharedPreferences` + `MasterKey`
library, dependency `libs.androidx.security.crypto`,
`android/app/build.gradle.kts:85`) as deprecated upstream and load-bearing
for tokens and key material in this app: "Plan (don't yet execute) the
`security-crypto` migration ... including in-place data migration for
existing installs." This is an **L**-sized item per repo `CLAUDE.md`'s rule
("For anything L-sized ... write a spec + plan pair under
`docs/superpowers/` first ... get it reviewed, then implement") — this
document is that spec. **No production code changes accompany it.**

Invariant §1.4 (never weaken the e2e crypto: X25519+HKDF derivation, AEAD on
every body/terminal frame, replay-protected counters) and §1.5 (never log
secrets) both constrain any future implementation of this migration
directly: the data being moved *is* that key material, and any migration
code that logs it to debug the move would be a regression on day one.

## What's stored today (audit, confirmed from code)

Three separate `EncryptedSharedPreferences` instances, each its own prefs
file, each built with the same `MasterKey.Builder(context)
.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)` +
`PrefKeyEncryptionScheme.AES256_SIV` / `PrefValueEncryptionScheme.AES256_GCM`
construction repeated verbatim in all three classes:

- **`data/Settings.kt`** (`Settings` class, `PREFS_NAME = "cmux_secure_prefs"`,
  Settings.kt:97) — per-`ConnectionSlot` (`RELAY`/`DIRECT`) `base_url` and
  `device_token` (Settings.kt:39-47), keyed by `"${slot}_$base"`
  (Settings.kt:94). `device_token` is the per-device bearer token minted at
  pairing (README.md "Per-device bearer token"); losing it silently means
  the app can no longer authenticate until the operator re-pairs.
- **`data/e2e/Identity.kt`** (`Identity` class,
  `PREFS_NAME = "cmux_e2e_identity"`, Identity.kt:48) — the phone's own
  X25519 identity keypair (`private_key_b64`/`public_key_b64`), generated
  once on first use and persisted thereafter (Identity.kt:31-45). This is
  **not** per-slot — one identity, shared across both connection slots.
- **`data/e2e/Session.kt`** (`Session` class, `PREFS_NAME = "cmux_e2e_session"`,
  Session.kt:197) — per-slot `peer_pubkey`, `shared_secret` (the derived
  X25519+HKDF AEAD key itself — the single most sensitive value in the
  app), `send_counter`, `recv_highest`, `recv_window_bits`
  (Session.kt:198-202). `Session` additionally keeps an **in-memory mirror**
  of the counter/window fields for the hot path (Session.kt:45-59) — every
  keystroke and every inbound frame reads/writes this cache, not the prefs
  file directly, with actual persistence pushed onto a single-threaded
  `writeScope` (Session.kt:64-75) so the hot path never blocks on Keystore
  I/O. `peer_pubkey`/`shared_secret` are deliberately **not** cached —
  `isPaired()`/`sharedSecret()` read straight from prefs synchronously
  (Session.kt:56-59, 125-128) because callers need read-after-write
  consistency for those two fields specifically.

**Construction and wiring:** `AppContainer.kt:27-28` constructs
`Settings(appContext)` and `Identity(appContext)` directly; the per-slot
`Session` instances are built at `AppContainer.kt:32-33`. `CmuxApp.kt:10-26`
documents *why* this matters operationally: `AppContainer`'s init does
"EncryptedSharedPreferences + Android Keystore I/O, which can block for a
while (key generation on first launch, TEE round-trips thereafter)," so it's
built lazily and warmed up on a background thread rather than on the
`Application.onCreate()` main-thread path. Any replacement must preserve
this property — synchronous Keystore-backed construction that blocks the UI
thread would be a real regression, not a wash.

**One existing in-place migration this design must not break:**
`AppContainer.kt:35-43`'s `init` block already runs a one-time migration
from the pre-dual-pairing single-slot format into the current per-slot
format, via `Settings.migrateLegacyIfNeeded()` (Settings.kt:66-92) and
`Session.absorbLegacyIfTarget()` (Session.kt:77-123). Both are
self-terminating (they clear the legacy keys the moment they migrate) and
both already have a free-function/injectable-I/O twin
(`migrateLegacyIfNeededInternal`, `absorbLegacyIfTargetInternal`) purely so
a JVM test can exercise the migration logic against in-memory maps instead
of a real `EncryptedSharedPreferences`. This existing pattern — read old
location, write new location, clear old location, all guarded so it only
ever fires once — is the direct template for the backing-store migration
this document proposes below; it does not need to be reinvented.

**Out of scope, for contrast:** `WorkspaceOrderStore.kt:14` uses plain
`context.getSharedPreferences(...)` (unencrypted) for the drag-to-reorder
workspace list and the "waiting first" sort toggle — non-secret UI
preference state. This document is only about the three
`EncryptedSharedPreferences`-backed classes above; `WorkspaceOrderStore` is
correctly unencrypted today and switching it to any of the directions below
would be scope creep, not a fix.

## Why this needs a plan: the deprecation, precisely

`androidx.security:security-crypto`'s own release notes record the
deprecation directly: version **1.1.0-beta01 (June 4, 2025)** states
"Deprecated all APIs in favour of existing platform APIs and direct use of
Android Keystore" — the same note carries through 1.1.0-rc01 and the
1.1.0 stable release (July 30, 2025), which is the version line this app's
`libs.androidx.security.crypto` currently resolves to
(`developer.android.com/jetpack/androidx/releases/security`). Two things
follow from Google's own wording, not from third-party commentary:

1. The library is deprecated, not removed — `EncryptedSharedPreferences`
   still functions in 1.1.0 and there is no forcing function (no compile
   error, no runtime crash) today. This migration is prudent, not urgent;
   see "Sizing this correctly" below.
2. Google's own guidance points at "existing platform APIs and direct use
   of Android Keystore" — i.e., hand-rolled `javax.crypto.Cipher` +
   `KeyGenParameterSpec`-backed Keystore keys — not at adopting Tink.
   Community writeups (e.g. a widely-shared "Goodbye
   EncryptedSharedPreferences" migration guide from late 2025) popularize a
   **DataStore + Tink** pattern as a more ergonomic alternative, and note
   the real gap this deprecation leaves: DataStore itself has no built-in
   encryption, so *something* has to wrap values (or the whole file) before
   it hits disk regardless of which direction is chosen.

## Needs analysis: what this app actually requires

Before weighing directions, the concrete shape of the problem here, not the
generic one:

- **Single-device-local store.** Nothing here is shared across processes,
  across app installs, or across devices — contrast with the Go-side
  `internal/e2e/store.go`/`internal/yolo/store.go` SQLite migration
  (`docs/superpowers/specs/2026-07-10-e2e-store-persistence-design.md`),
  which exists specifically because *that* store is opened by two OS
  processes concurrently. The Android store has exactly one reader/writer:
  this app's own process.
- **Not multi-process.** No `ContentProvider`, no `WorkManager` isolated
  process, no widget/tile process reads this data. `AppContainer` is a
  singleton owned by `CmuxApp` (`CmuxApp.kt:18,30-31`).
- **No existing Tink or DataStore dependency.** `libs.androidx.security.crypto`
  is the *only* crypto-adjacent Jetpack dependency
  (`android/app/build.gradle.kts:85`); adopting either direction is a net
  new dependency, not an incremental one.
- **Small, fixed key set.** Three prefs files, at most ~15 keys total (2
  slots × {base_url, device_token} in `Settings`; 2 keys in `Identity`; 2
  slots × 5 fields in `Session`), all short strings/longs/base64 blobs — no
  large blobs, no streaming data, no need for `StreamingAead`-class
  primitives.
- **No key rotation, no multiple key types, no HSM/cloud KMS.** Every value
  is protected by exactly one on-device AES-256 key scheme; there is no
  requirement (today or foreseeably, given "Scale: ~4.9k lines main
  Kotlin ... small and healthy" per the guide's own orientation section) to
  support multiple concurrent key versions, remote key management, or
  cross-app key sharing — the class of problems Tink's `KeysetHandle`
  abstraction is built to solve.

## Directions evaluated

### (a) Tink directly

**Mechanism:** add `com.google.crypto.tink:tink-android`, generate a
`KeysetHandle` wrapped by `AndroidKeystoreKmsClient` (envelope encryption:
Tink's AEAD primitive does the actual encrypt/decrypt, with the DEK itself
protected by a non-exportable Keystore key), and use `Aead.encrypt`/`decrypt`
to wrap each stored value before writing it into *some* KV or file store
(this direction doesn't by itself replace the storage layer — Tink is
prescriptive about the crypto primitive, not about where the bytes live).

**In this app's favor:** Tink is the actual library
`EncryptedSharedPreferences` used internally (its `AES256_SIV`/`AES256_GCM`
schemes are Tink primitive names), so the on-disk crypto *shape* barely
changes — this is close to "keep the crypto, drop the deprecated wrapper."
Tink is Google-maintained, has had extensive external cryptographic review,
and its misuse-resistant API design (no raw IV handling, no mode/padding
footguns) is a genuine safety property worth having if the app's key
management ever grows more complex.

**Against, for this app specifically:** a full crypto library — `KeysetHandle`
management, keyset JSON serialization, registry initialization
(`AeadConfig.register()`), proto-based keyset formats — is machinery sized
for an app juggling multiple key types, rotation schedules, or
cross-service key sharing. None of that is present here: there is one
logical secret domain (this device's own local secrets) and one key scheme,
used identically across three files today via copy-pasted
`MasterKey.Builder`/`EncryptedSharedPreferences.create` boilerplate. Tink
would trade that boilerplate for a different, larger surface (keyset
handles, a registry, Tink's own proto dependency) without buying this app
anything from its abstraction — the guide's own repo-wide guidance to
"prefer surgical changes, never sweeping rewrites" (§0 Orientation) argues
against introducing a general-purpose crypto framework to solve a
fixed-shape, three-file problem.

### (b) `DataStore` + an app-managed Android Keystore key

**Mechanism:** generate one AES-256-GCM key directly in the Android
Keystore (`KeyGenParameterSpec.Builder(..., PURPOSE_ENCRYPT or
PURPOSE_DECRYPT).setBlockModes(GCM).setEncryptionPaddings(NONE).build()`,
non-exportable, hardware-backed where the device supports it — no
`MasterKey` wrapper needed, this is literally "direct use of Android
Keystore," matching Google's own deprecation-note wording verbatim). Wrap
each value with `javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")`
before writing it; store the resulting `{iv, ciphertext}` pairs as
`Preferences DataStore` entries (`androidx.datastore:datastore-preferences`)
in place of the three `SharedPreferences` files. `Settings`/`Identity`/
`Session`'s public APIs (`baseUrl()`, `sharedSecret()`, `nextSendCounter()`,
etc.) stay the same shape; only their private storage internals change —
callers (`AppContainer`, `PairingClient`, `E2eInterceptor`, and the
free-function migration helpers) need no changes beyond the constructor.

**In this app's favor:**

- Directly matches Google's own stated replacement guidance ("existing
  platform APIs and direct use of Android Keystore") rather than a
  community-popularized alternative.
- `androidx.datastore:datastore-preferences` is a small, actively
  maintained, widely adopted Jetpack library (unlike `security-crypto`,
  which has no maintenance commitment post-deprecation) with a
  `SharedPreferences`-shaped async API — a natural fit for `Settings`/
  `Identity`/`Session`'s existing `get`/`put` call shape, and async-native
  (Coroutines/`Flow`), which sits well with `Session`'s already-async
  `writeScope` pattern (Session.kt:64-75) rather than fighting it.
  DataStore's own docs also explicitly frame it as `SharedPreferences`'s
  direct successor for key-value data generally, independent of this
  deprecation — so this migration also picks up that broader modernization
  for free instead of trading one soon-to-be-legacy store for another.
- The encryption code this app would own (~30-40 lines: key generation +
  `Cipher.doFinal` wrap/unwrap) is small enough to read, test, and reason
  about directly — appropriate for a codebase whose comment discipline
  favors "express intent through naming" over hiding logic behind a large
  dependency's abstractions (repo `CLAUDE.md`).
- Zero new heavyweight dependency; no keyset-serialization format to manage
  or version.

**Against:** the app now owns the AES-GCM wrap/unwrap code and its test
coverage directly, rather than delegating correctness to a library — a real
cost, though a bounded and well-precedented one (this is exactly the
`Cipher`/AEAD pattern already used for the e2e crypto layer itself,
`data/e2e/Cipher.kt`, so the codebase already has both the pattern and the
test-writing muscle for it). If this app's key-management needs ever grow
(multiple key types, rotation, cross-app sharing), this direction has less
room to grow into than Tink's abstraction — judged not likely given the
app's stated scale, but worth naming as the direction's real tradeoff.

## Recommendation: (b), `DataStore` + an app-managed Android Keystore key

Direction (b) is recommended. It is the smaller, more surgical change for
this app's actual shape (three files, ~15 keys, one process, one key
scheme, no rotation/multi-key/cross-app requirements), it tracks Google's
own stated deprecation guidance rather than a third-party pattern, it
replaces both the deprecated crypto wrapper *and* the aging
`SharedPreferences` storage layer in one pass instead of leaving a second
soon-to-be-legacy dependency in place, and it adds one small, well-precedented
(mirrors the existing `data/e2e/Cipher.kt` AEAD pattern), test-covered
dependency instead of a general-purpose crypto framework whose extra
capabilities this app has no concrete use for. Direction (a) is not wrong,
merely oversized for the problem as audited above — worth revisiting only if
this app's key-management needs concretely grow (e.g. multiple cooperating
apps sharing key material, which is explicitly not this app's architecture
per the README's single-phone/single-Mac model).

## In-place migration for existing installs (required — this is not a
greenfield decision)

Users have live paired sessions today: a `device_token` that lets the app
authenticate at all, and a `shared_secret` that *is* the AEAD key protecting
every subsequent frame. Losing either on upgrade means the paired device is
functionally unpaired — the app would have to fall back to "not paired,"
which for `Session`'s `shared_secret` specifically also means the send/recv
counters reset to zero (`Session.setPairing`, Session.kt:134-148), which is
only safe because a *fresh* shared secret comes with it. Silently losing
just the counters while somehow keeping a stale secret would be a nonce-reuse
regression (invariant §1.4) — so the migration must move each prefs file's
full record atomically, not field-by-field, or fail closed into "re-pair,"
never into "half-migrated."

**Design, directly extending the pattern already in this codebase**
(`Settings.migrateLegacyIfNeeded`/`Session.absorbLegacyIfTarget`,
self-terminating, guarded, tested via free-function/injectable-I/O twins):

1. **Detect.** On construction, each of the three new
   `DataStore`-backed classes checks whether its *old*
   `EncryptedSharedPreferences` file still exists and is non-empty
   (`context.getSharedPreferences(...).all.isNotEmpty()`, or
   `context.getDatabasePath`/prefs-file existence check — implementation
   detail to nail down in the paired plan doc, not here). The old
   `EncryptedSharedPreferences` construction code (`MasterKey.Builder` +
   `EncryptedSharedPreferences.create`) is **kept, unchanged, read-only**,
   purely as the migration source — it is not removed from the codebase
   until a later cleanup item, separately scoped (see non-goals below).
2. **Read via the old, still-functional API.** Because
   `EncryptedSharedPreferences` still works in 1.1.0 (deprecated, not
   broken), the migration reads every existing key through the *existing*
   `SharedPreferences` interface — this is the one step that requires no
   new crypto code at all, since decryption is still whatever
   `EncryptedSharedPreferences` already does.
3. **Write via the new AES-GCM-over-Keystore + DataStore path,
   transactionally.** Each record (all of `Settings`'s slot fields, all of
   `Identity`'s keypair, all of `Session`'s slot fields) is written as one
   `DataStore.edit { }` transaction — `DataStore`'s transactional
   `updateData`/`edit` already gives atomicity per file, so a process death
   mid-migration cannot leave a torn write in the *new* store (mirroring
   why the old `EncryptedSharedPreferences.Editor.apply()` +
   `os.Rename`-free single-file-commit semantics were safe for the same
   reason on the Go side's now-fixed `e2e.Store`, see the paired
   e2e-store-persistence design doc for the analogous cross-process
   version of this same "atomic unit of persistence" property).
4. **Verify before clearing.** Read the just-written new-store record back
   and compare against the source values **in memory** (not by re-reading
   the old store, to avoid a second decrypt round-trip) before touching the
   old file. Only on a successful match does step 5 run — if verification
   fails, the migration is retried on next launch (the old store hasn't
   been touched yet, so this is safe to retry indefinitely) rather than the
   app falling back to "not paired" while a viable secret still exists
   on disk.
5. **Clear the old file, not just its keys.** Once verified, delete the old
   `EncryptedSharedPreferences`-backed file entirely
   (`context.deleteSharedPreferences(name)`, API 24+, matches this app's
   `minSdk`) rather than clearing individual keys — self-terminating by
   construction, matching the existing `migrateLegacyIfNeeded`/
   `absorbLegacyIfTarget` pattern's own self-terminating design (detect →
   migrate → clear-so-it-never-fires-again).
6. **Fail-closed guarantee.** If the process dies between steps 2 and 4
   (read succeeded, write not yet verified), the old file is still intact
   and untouched — the migration simply reruns from step 1 on next launch.
   The only state that must never be reached is "old file cleared, new
   store unverified" — step 5 is ordered last and is the *only* step that
   is irreversible, which is why it is gated on step 4's explicit
   verification rather than on the write in step 3 alone succeeding without
   readback.
7. **Order relative to the existing legacy-slot migration.** This
   store-format migration is orthogonal to (and must run independently of)
   the existing single-slot→dual-slot migration
   (`AppContainer.kt:35-43`): whichever format each is stored in, the
   slot-migration logic only cares about *which slot* a legacy record
   belongs to, not which underlying store API served it. The simplest
   sequencing is running the store-format migration first (old
   `EncryptedSharedPreferences` → new `DataStore`, preserving whatever slot
   layout was already on disk, legacy-single-slot or already-dual-slot),
   then leaving `AppContainer`'s existing `migrateLegacyIfNeeded`/
   `absorbLegacyIfTarget` call unchanged to run against the now-`DataStore`-backed
   classes exactly as it runs against the `SharedPreferences`-backed ones
   today — those two methods already only depend on their injected
   read/write callbacks (`migrateLegacyIfNeededInternal`,
   `absorbLegacyIfTargetInternal`), not on `SharedPreferences` directly, so
   this reordering needs no changes to that existing logic, only to what
   backs the callbacks.
8. **One-shot cost is acceptable.** All three files together hold ~15
   small values; the full read-old/write-new/verify/delete-old sequence
   for all three happens once, at first launch after the app update,
   already on the same background thread `CmuxApp.kt:25` uses to warm up
   `AppContainer` today — no new blocking-the-UI risk beyond what already
   exists for ordinary Keystore I/O.

## Acceptance criteria (for the paired implementation plan, not this design)

- An install with an existing paired `Session`/`Settings`/`Identity`
  (real `EncryptedSharedPreferences` state) upgrades and remains paired —
  `device_token`, `shared_secret`, and both counters are bit-identical
  before and after migration, and no re-pairing prompt appears.
- A fresh install (no legacy files at all) never attempts migration and
  incurs no extra I/O.
- Killing the process at any point before old-file deletion and relaunching
  leaves the app in a state that either completes migration cleanly or
  retries it — never in a state where both the old and new stores are
  simultaneously absent/invalid for the same field.
- The existing `TestConcurrentDecryptOfSameCounterAcceptsExactlyOnce`-style
  invariant on the Kotlin side (replay-window correctness) is unaffected —
  migration only changes *where* the counters are read from, never their
  values.
- No secret value (token, private key, shared secret, or counter — per
  invariant §1.5) appears in any log line added by the migration code.

## Explicit non-goals (this design)

- **Not implementing the migration.** Per the guide: "Plan (don't yet
  execute)." This document and its (not-yet-written) paired plan are the
  full deliverable for this guide item.
- **Removing the `security-crypto` dependency or the old
  `EncryptedSharedPreferences` construction code** as part of the migration
  itself — it must stay, read-only, as the migration source until a
  separately scoped follow-up confirms all installs have migrated (e.g.
  gated on a minimum supported "migrated from version" floor, itself a
  product decision outside this design's scope).
- **Changing `WorkspaceOrderStore`** — it is unencrypted today by design
  (no secrets), and this document's scope is strictly the three
  `EncryptedSharedPreferences`-backed classes audited above.
- **Any change to the e2e crypto primitives themselves** (X25519, HKDF,
  AEAD scheme, nonce/counter construction) — this is a storage/at-rest
  migration only; the values being protected and how they're used
  on the wire are unchanged (invariant §1.4).
- **Multi-device or cloud key sync** — out of scope; this remains a
  single-device-local store per the needs analysis above.
