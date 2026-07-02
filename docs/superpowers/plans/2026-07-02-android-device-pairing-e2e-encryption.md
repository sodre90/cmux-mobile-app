# Android Device Pairing + E2E Encryption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Android app's manual base-URL/token/.p12 setup with self-service QR-code pairing against the already-shipped Go relay/agent, and add end-to-end content encryption (X25519 + HKDF-SHA256 + XChaCha20-Poly1305) so the phone can talk to `cmux-bridge agent` through the untrusted relay exactly like the Go side already expects.

**Architecture:** A new `data/e2e` package provides crypto primitives (`Cipher.kt`, split across two verified libraries — see Global Constraints), a persistent device identity (`Identity.kt`), and paired-session state with a sliding-window replay gate (`Session.kt`), plus wire-format encode/decode (`Envelope.kt` for HTTP bodies, `Frame.kt` for WebSocket frames) and an OkHttp `Interceptor` (`E2eInterceptor.kt`) that makes encryption transparent to `BridgeClient`. A new `data/pairing` package scans the agent's QR code and completes the handshake against `POST /devices/pair`. A new `ui/pairing/PairingScreen.kt` (CameraX + ML Kit) replaces `ui/settings/SettingsScreen.kt` as the app's onboarding screen. `TerminalSocket`/`EventsSocket` switch from JSON text WebSocket frames to encrypted binary frames. A small, scoped amendment to the already-shipped Go code (`bridge/internal/e2e/store.go`) replaces its strict-monotonic replay counter with the same sliding-window algorithm, since three concurrent phone-side channels (HTTP, terminal WS, events WS) draw from one send counter and can arrive out of order.

**Tech Stack:** Kotlin (existing app: Compose, OkHttp 4.12.0, kotlinx.serialization); `org.bouncycastle:bcprov-jdk18on:1.84` for X25519 ECDH + HKDF-SHA256; `com.goterl:lazysodium-android:5.2.0` (+ `net.java.dev.jna:jna:5.17.0`) for XChaCha20-Poly1305 AEAD, with `com.goterl:lazysodium-java:5.2.0` as a JVM-unit-test-only sibling; `androidx.camera:*:1.5.3` (CameraX) + `com.google.mlkit:barcode-scanning:17.3.0` for QR scanning. Go: stdlib only (`bridge/internal/e2e/store.go` amendment).

## Global Constraints

- **Spec:** This plan implements `docs/superpowers/specs/2026-07-02-android-device-pairing-e2e-encryption-design.md`. Read it first if anything below is ambiguous — it has the full rationale for every decision.
- **Crypto library split is deliberate and empirically verified, not a guess.** Tink was rejected (no caller-supplied-nonce API). BouncyCastle was then verified — by actually compiling and running a Java program against `bridge/internal/e2e/cipher_test.go`'s fixed vectors — to correctly implement X25519 ECDH (`X25519PrivateKeyParameters`/`X25519Agreement`) and HKDF-SHA256 (`HKDFBytesGenerator`), but to have **no XChaCha20-Poly1305 implementation at all** in any version through 1.84 (its only ChaCha-Poly1305 class throws `IllegalArgumentException: Nonce must be 96 bits` on a 24-byte nonce; no XChaCha/HChaCha20 class or JCE registration exists in the jar). `com.goterl:lazysodium-android`'s `AEAD.Native.cryptoAeadXChaCha20Poly1305IetfEncrypt`/`Decrypt` methods were then verified the same way — compiled and run against the same fixed vectors via the `lazysodium-java` sibling artifact — and produce byte-identical ciphertext. **Every task below that touches crypto assumes these exact, verified API shapes; do not substitute a different method signature without re-verifying against the fixed vectors.**
- **Exact dependency versions** (all confirmed to exist on Maven Central / Google's Maven at plan-writing time): `org.bouncycastle:bcprov-jdk18on:1.84`, `com.goterl:lazysodium-android:5.2.0`, `com.goterl:lazysodium-java:5.2.0` (test-only), `net.java.dev.jna:jna:5.17.0`, `androidx.camera:camera-core:1.5.3`, `androidx.camera:camera-camera2:1.5.3`, `androidx.camera:camera-lifecycle:1.5.3`, `androidx.camera:camera-view:1.5.3`, `com.google.mlkit:barcode-scanning:17.3.0`. Add via the version catalog (`android/gradle/libs.versions.toml`), matching the existing project convention — do not hand-add raw coordinate strings to `build.gradle.kts`. **CameraX is pinned to 1.5.3, not the newer 1.6.x line, deliberately:** verified by downloading each AAR and inspecting its `META-INF/com/android/build/gradle/aar-metadata.properties` — 1.5.3's four artifacts (`camera-core`/`camera-camera2`/`camera-lifecycle`/`camera-view`) all declare `minCompileSdk=35`/`minAndroidGradlePluginVersion=8.6.0`, matching this project's current `compileSdk 35`/AGP 8.7.0 exactly. CameraX 1.6.1 declares `minCompileSdk=36`/`minAndroidGradlePluginVersion=8.9.1`, which this project does not meet and which is out of scope to bump for this plan.
- **Direction tags are perspective-relative — get this exactly right.** The Go agent sends with `DirAgentToDevice = 0x00` and opens received frames with `DirDeviceToAgent = 0x01` (`bridge/internal/e2e/envelope.go`, `frame.go`). The phone is the "device" side of that same protocol, so it is the **mirror image**: the phone sends with `DirDeviceToAgent = 0x01` and opens received frames with `DirAgentToDevice = 0x00`. Every task that calls `nonce(...)` states which constant to use and why — cross-check against this paragraph if unsure, since swapping these two silently produces ciphertext that fails to decrypt on the other side (not a crash here, a mysterious failure there).
- **Sliding-window replay gate, W = 64, same algorithm on phone and agent.** Both `Session.kt` (this plan) and the Go amendment (Task 19) implement the identical RFC 6479-style bitmap: `highestSeen: Long/uint64` plus a single 64-bit `windowBits` mask (bit `i` set means counter `highestSeen - i` was already accepted). `canAccept(n)` is a read-only check; `commit(n)` is a separate, mutating call — **never merge them**. Callers must call `canAccept` before attempting AEAD decryption and `commit` only after the AEAD tag verifies successfully, exactly mirroring the already-shipped Go `ValidateRecvCounter`/`CommitRecvCounter` split (Tasks 6-8 below preserve this).
- **`EncryptedSharedPreferences` for all new persisted secrets**, matching the existing `Settings.kt` pattern (AES256_GCM master key, AES256_SIV key encryption, AES256_GCM value encryption) — no plain `SharedPreferences`, no files on external/app-private storage.
- **No retrofit of the old manual-setup fallback.** Per the spec's "full enforcement, no coexistence" decision, `SettingsScreen`/`SettingsViewModel`/`Mtls.kt`'s client-cert code are deleted, not kept behind a flag.
- **Commit authorship:** every commit must be authored solely by the human developer. Never add `Co-Authored-By: Claude` or any AI attribution trailer.
- **Kotlin, not Java, for all new/modified files** — match the existing codebase (100% Kotlin) even though the verification spikes referenced above were written in Java for speed.
- **4-space indentation, minimal comments** (one line max, only for non-obvious WHY), matching every existing file in `android/app/src/main/java/com/sodre90/cmuxremote/`.

## File Structure

New:
- `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt` — X25519/HKDF/XChaCha20-Poly1305 primitives + `Nonce`
- `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Identity.kt` — persistent phone X25519 identity keypair
- `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Session.kt` — paired-device state: shared secret, durable send counter, sliding-window receive gate
- `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Envelope.kt` — HTTP body envelope encrypt/decrypt orchestration
- `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Frame.kt` — WS binary frame encrypt/decrypt orchestration
- `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/E2eInterceptor.kt` — OkHttp `Interceptor` wiring `Envelope` into `BridgeClient`
- `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingQr.kt` — QR JSON payload DTO + expiry validation
- `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingClient.kt` — `POST /devices/pair` + shared-secret derivation + persistence
- `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingViewModel.kt`
- `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingScreen.kt` — CameraX + ML Kit scanner UI

Modified:
- `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` — new dependencies
- `android/app/src/main/AndroidManifest.xml` — camera permission
- `android/app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt` — drop `.p12`/CA fields, add one-time legacy-prefs wipe
- `android/app/src/main/java/com/sodre90/cmuxremote/data/Mtls.kt` — drop client-cert key-manager code, bearer-only
- `android/app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt` — binary encrypted frames
- `android/app/src/main/java/com/sodre90/cmuxremote/data/EventsSocket.kt` — binary encrypted frames
- `android/app/src/main/java/com/sodre90/cmuxremote/data/AppContainer.kt` — wire `Session`/`E2eInterceptor`/`Cipher` singletons
- `android/app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt` — swap `SettingsScreen` → `PairingScreen`
- `android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsScreen.kt` — "Settings" button → "Re-pair device"

Deleted:
- `android/app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsScreen.kt`
- `android/app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsViewModel.kt`
- `android/app/src/test/java/com/sodre90/cmuxremote/data/MtlsTest.kt` (client-cert tests no longer apply; replaced by a smaller bearer-only test, see Task 12)

Go side (scoped amendment, not a new feature):
- Modified: `bridge/internal/e2e/store.go`, `bridge/internal/e2e/store_test.go`

---

### Task 1: Gradle dependencies + camera manifest permission

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `libs.bouncycastle`, `libs.lazysodium.android`, `libs.lazysodium.java`, `libs.jna`, `libs.jna.test`, `libs.androidx.camera.core`, `libs.androidx.camera.camera2`, `libs.androidx.camera.lifecycle`, `libs.androidx.camera.view`, `libs.mlkit.barcode.scanning` version-catalog aliases, consumed by every later task's `build.gradle.kts` edits.

- [ ] **Step 1: Add version catalog entries**

Edit `android/gradle/libs.versions.toml`, in `[versions]` (after `junit = "4.13.2"`):

```toml
bouncycastle = "1.84"
lazysodium = "5.2.0"
jna = "5.17.0"
cameraX = "1.5.3"
mlkitBarcode = "17.3.0"
```

In `[libraries]` (after `junit = { group = "junit", name = "junit", version.ref = "junit" }`):

```toml
bouncycastle = { group = "org.bouncycastle", name = "bcprov-jdk18on", version.ref = "bouncycastle" }
lazysodium-android = { group = "com.goterl", name = "lazysodium-android", version.ref = "lazysodium" }
lazysodium-java = { group = "com.goterl", name = "lazysodium-java", version.ref = "lazysodium" }
jna = { group = "net.java.dev.jna", name = "jna", version.ref = "jna" }
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "cameraX" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "cameraX" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraX" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "cameraX" }
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkitBarcode" }
```

- [ ] **Step 2: Add dependencies to build.gradle.kts**

Edit `android/app/build.gradle.kts`, in the `dependencies { ... }` block, after `implementation(libs.firebase.messaging)`. `lazysodium-android` transitively pulls `net.java.dev.jna:jna` as a plain jar, which collides with the explicit AAR variant of the same coordinate below (`checkDebugDuplicateClasses` fails on duplicate `com.sun.jna.*` classes without this exclude — verified empirically):

```kotlin
    implementation(libs.bouncycastle)
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
```

After `testImplementation(libs.kotlinx.coroutines.test)`:

```kotlin
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
```

- [ ] **Step 3: Exclude a duplicate packaged resource**

`org.bouncycastle:bcprov-jdk18on:1.84` and `org.jspecify:jspecify:1.0.0` (pulled in transitively by several AndroidX libraries already in this project, e.g. `androidx.exifinterface`, `androidx.lifecycle`) both ship `META-INF/versions/9/OSGI-INF/MANIFEST.MF`, which fails `mergeDebugJavaResource` as a resource collision (verified empirically). Edit `android/app/build.gradle.kts`, adding a `packaging { ... }` block inside `android { ... }`, after the existing `buildFeatures { compose = true }`:

```kotlin
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
```

- [ ] **Step 4: Add camera permission and feature to the manifest**

Edit `android/app/src/main/AndroidManifest.xml`, after `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`:

```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
```

- [ ] **Step 5: Verify the build still resolves and compiles**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (no source changes yet, so this only proves the new dependencies resolve and don't conflict).

- [ ] **Step 6: Commit**

```bash
cd android && git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "android: add e2e crypto, QR scanning dependencies"
```

---

### Task 2: `data/e2e/Cipher.kt` — X25519 ECDH + HKDF-SHA256

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/CipherTest.kt`

**Interfaces:**
- Produces: `nonce(direction: Byte, counter: Long): ByteArray`, `DIR_AGENT_TO_DEVICE: Byte`, `DIR_DEVICE_TO_AGENT: Byte`, `generateX25519KeyPair(): Pair<ByteArray, ByteArray>` (privateRaw32 to publicRaw32), `x25519PublicKeyFromPrivate(privateKeyRaw: ByteArray): ByteArray`, `deriveSharedSecret(myPrivateKeyRaw: ByteArray, theirPublicKeyRaw: ByteArray): ByteArray` — all top-level functions in package `com.sodre90.cmuxremote.data.e2e`, consumed by `Identity.kt` (Task 4), `PairingClient.kt` (Task 11).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/CipherTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private fun hex(s: String): ByteArray {
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }
    return out
}

private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

class CipherTest {

    @Test
    fun nonceLaysOutDirectionAndCounter() {
        val n = nonce(DIR_AGENT_TO_DEVICE, 42L)
        assertEquals(24, n.size)
        assertEquals(0x00.toByte(), n[15])
        // big-endian 42 in the last 8 bytes
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 42), n.copyOfRange(16, 24))

        val n2 = nonce(DIR_DEVICE_TO_AGENT, 1L)
        assertEquals(0x01.toByte(), n2[15])
    }

    @Test
    fun deriveSharedSecretMatchesGoFixedVector() {
        // Mirrors bridge/internal/e2e/cipher_test.go TestDeriveSharedSecretFixedVector.
        val agentPriv = ByteArray(32) { 0x01 }
        val devicePriv = ByteArray(32) { 0x02 }

        val agentPub = x25519PublicKeyFromPrivate(agentPriv)
        val devicePub = x25519PublicKeyFromPrivate(devicePriv)

        assertEquals(
            "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209",
            hexOf(agentPub),
        )
        assertEquals(
            "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59",
            hexOf(devicePub),
        )

        val agentSide = deriveSharedSecret(agentPriv, devicePub)
        val deviceSide = deriveSharedSecret(devicePriv, agentPub)

        val want = "0c657b7b4a6f6eede1d9f03bad4f9c898e9291c22eeb4cd09f12df79394837d6"
        assertEquals(want, hexOf(agentSide))
        assertEquals(want, hexOf(deviceSide))
    }

    @Test
    fun generateX25519KeyPairProducesConsistentKeys() {
        val (priv, pub) = generateX25519KeyPair()
        assertEquals(32, priv.size)
        assertEquals(32, pub.size)
        assertArrayEquals(pub, x25519PublicKeyFromPrivate(priv))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.CipherTest"`
Expected: FAIL — `Cipher.kt` doesn't exist yet, compilation error (`Unresolved reference: nonce`, etc).

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Agent's outgoing / phone's incoming direction tag (see Global Constraints). */
const val DIR_AGENT_TO_DEVICE: Byte = 0x00

/** Phone's outgoing / agent's incoming direction tag (see Global Constraints). */
const val DIR_DEVICE_TO_AGENT: Byte = 0x01

private const val HKDF_INFO_PREFIX = "cmux-bridge e2e v1|"

/**
 * 24-byte XChaCha20-Poly1305 nonce: byte 15 is the direction tag, bytes
 * 16-23 are the big-endian counter. Mirrors bridge/internal/e2e/cipher.go's
 * Nonce exactly -- this is the cross-language wire-format contract.
 */
fun nonce(direction: Byte, counter: Long): ByteArray {
    val n = ByteArray(24)
    n[15] = direction
    for (i in 0 until 8) {
        n[16 + i] = (counter ushr (8 * (7 - i))).toByte()
    }
    return n
}

/** Generates a fresh X25519 keypair; returns (privateKeyRaw32, publicKeyRaw32). */
fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
    val generator = X25519KeyPairGenerator()
    generator.init(X25519KeyGenerationParameters(SecureRandom()))
    val keyPair = generator.generateKeyPair()
    val priv = keyPair.private as X25519PrivateKeyParameters
    val pub = keyPair.public as X25519PublicKeyParameters
    return priv.encoded to pub.encoded
}

fun x25519PublicKeyFromPrivate(privateKeyRaw: ByteArray): ByteArray =
    X25519PrivateKeyParameters(privateKeyRaw, 0).generatePublicKey().encoded

/** Sorts the two public keys lexicographically before concatenation, so both peers
 *  derive an identical HKDF info string regardless of which side computes it --
 *  mirrors bridge/internal/e2e/cipher.go's buildInfo exactly. */
private fun buildInfo(pubA: ByteArray, pubB: ByteArray): ByteArray {
    val (first, second) = if (compareBytes(pubA, pubB) > 0) pubB to pubA else pubA to pubB
    val out = ByteArrayOutputStream()
    out.write(HKDF_INFO_PREFIX.toByteArray(Charsets.UTF_8))
    out.write(first)
    out.write('|'.code)
    out.write(second)
    return out.toByteArray()
}

private fun compareBytes(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (diff != 0) return diff
    }
    return a.size - b.size
}

/**
 * ECDH(myPriv, theirPub) then HKDF-SHA256(secret, salt=null, info=buildInfo(...))
 * -> 32-byte shared secret. Mirrors bridge/internal/e2e/cipher.go's
 * DeriveSharedSecret; both peers derive the same value independently.
 */
fun deriveSharedSecret(myPrivateKeyRaw: ByteArray, theirPublicKeyRaw: ByteArray): ByteArray {
    val myPriv = X25519PrivateKeyParameters(myPrivateKeyRaw, 0)
    val myPub = myPriv.generatePublicKey()
    val theirPub = X25519PublicKeyParameters(theirPublicKeyRaw, 0)

    val agreement = X25519Agreement()
    agreement.init(myPriv)
    val ecdh = ByteArray(agreement.agreementSize)
    agreement.calculateAgreement(theirPub, ecdh, 0)

    val info = buildInfo(myPub.encoded, theirPublicKeyRaw)
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(HKDFParameters(ecdh, null, info))
    val out = ByteArray(32)
    hkdf.generateBytes(out, 0, 32)
    return out
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.CipherTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt app/src/test/java/com/sodre90/cmuxremote/data/e2e/CipherTest.kt
git commit -m "android: add X25519 + HKDF-SHA256 e2e primitives"
```

---

### Task 3: `data/e2e/Cipher.kt` — XChaCha20-Poly1305 AEAD

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt`
- Modify: `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/CipherTest.kt`

**Interfaces:**
- Consumes: nothing new from earlier tasks.
- Produces: `class Cipher(sodium: LazySodium)` with `fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray` and `fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray` (throws `DecryptFailedException` on AEAD failure), plus `class DecryptFailedException : Exception("decrypt_failed")` — consumed by `Envelope.kt` (Task 7), `Frame.kt` (Task 8), `PairingClient.kt` indirectly via `AppContainer` (Task 16).

- [ ] **Step 1: Write the failing test**

Append to `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/CipherTest.kt`, inside the `CipherTest` class (add these imports at the top of the file first: `import com.goterl.lazysodium.LazySodiumJava` and `import com.goterl.lazysodium.SodiumJava`):

```kotlin
    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Test
    fun sealMatchesGoFixedVector() {
        // Mirrors bridge/internal/e2e/cipher_test.go TestFixedCipherVector.
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "cmux-bridge e2e test vector".toByteArray(Charsets.UTF_8)
        val n = nonce(DIR_AGENT_TO_DEVICE, 42L)

        val ct = cipher.seal(key, n, plaintext)

        val want = "3adf930c2c38c2dc6de9e1fab5be816f607fea9f2d9e503a7f22277d65a588c593c28255c0dc93cac7a52a"
        assertEquals(want, hexOf(ct))

        val pt = cipher.open(key, n, ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test(expected = DecryptFailedException::class)
    fun openRejectsWrongKey() {
        val key1 = ByteArray(32)
        val key2 = ByteArray(32).also { it[0] = 0xff.toByte() }
        val n = nonce(DIR_AGENT_TO_DEVICE, 0L)
        val ct = cipher.seal(key1, n, "secret".toByteArray())
        cipher.open(key2, n, ct)
    }

    @Test(expected = DecryptFailedException::class)
    fun openRejectsWrongDirection() {
        val key = ByteArray(32)
        val ct = cipher.seal(key, nonce(DIR_AGENT_TO_DEVICE, 0L), "secret".toByteArray())
        cipher.open(key, nonce(DIR_DEVICE_TO_AGENT, 0L), ct)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.CipherTest"`
Expected: FAIL — compilation error (`Unresolved reference: Cipher`, `Unresolved reference: DecryptFailedException`).

- [ ] **Step 3: Write the implementation**

Append to `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt` (add `import com.goterl.lazysodium.LazySodium` and `import com.goterl.lazysodium.interfaces.AEAD` at the top first):

```kotlin
class DecryptFailedException : Exception("decrypt_failed")

/**
 * XChaCha20-Poly1305 AEAD via lazysodium (BouncyCastle has no XChaCha
 * implementation -- see Global Constraints). [sodium] is injected so
 * production code passes a LazySodiumAndroid instance and tests pass
 * LazySodiumJava; both implement the same AEAD.Native interface.
 */
class Cipher(sodium: LazySodium) {
    private val aead = sodium as AEAD.Native

    /** key must be 32 bytes, nonce must be 24 bytes (see [nonce]). */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val ciphertext = ByteArray(plaintext.size + 16) // 16-byte Poly1305 tag
        val ciphertextLen = LongArray(1)
        val ok = aead.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext, ciphertextLen, plaintext, plaintext.size.toLong(),
            null, 0, null, nonce, key,
        )
        check(ok) { "xchacha20poly1305 seal failed" }
        return ciphertext.copyOf(ciphertextLen[0].toInt())
    }

    /** Throws [DecryptFailedException] on any AEAD verification failure --
     *  wrong key, wrong nonce/direction, or corrupted ciphertext. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val plaintext = ByteArray(ciphertext.size)
        val plaintextLen = LongArray(1)
        val ok = aead.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext, plaintextLen, null, ciphertext, ciphertext.size.toLong(),
            null, 0, nonce, key,
        )
        if (!ok) throw DecryptFailedException()
        return plaintext.copyOf(plaintextLen[0].toInt())
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.CipherTest"`
Expected: PASS (6 tests total).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/Cipher.kt app/src/test/java/com/sodre90/cmuxremote/data/e2e/CipherTest.kt
git commit -m "android: add XChaCha20-Poly1305 AEAD via lazysodium"
```

---

### Task 4: `data/e2e/Identity.kt` — persistent phone identity keypair

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Identity.kt`

**Interfaces:**
- Consumes: `generateX25519KeyPair()` (Task 2).
- Produces: `class Identity(context: Context)` with `val privateKey: ByteArray`, `val publicKey: ByteArray` (both 32 bytes, generated once and persisted) — consumed by `PairingClient.kt` (Task 11), `AppContainer.kt` (Task 16).

**No unit test for this task.** `EncryptedSharedPreferences` requires Android
Keystore, which is not available in a plain JVM unit test — the existing
`com.sodre90.cmuxremote.data.Settings` class uses the identical pattern and
has no test for the same reason (confirmed: no `SettingsTest.kt` exists in
`android/app/src/test/`). Introducing Robolectric now to test this one class
would be new test infrastructure inconsistent with the rest of the project;
`Identity`'s only real logic (generate-once, else load) is a few lines and
its crypto correctness is already covered by `CipherTest.
generateX25519KeyPairProducesConsistentKeys` (Task 2). `Session` (Task 6)
factors its actual algorithmic logic (the sliding-window gate) out into a
separately-testable, Android-independent class specifically to avoid this
same problem recurring for the code that most needs coverage.

- [ ] **Step 1: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Identity.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The phone's own X25519 identity keypair, generated once on first use and
 * persisted thereafter. Encrypted at rest, matching [com.sodre90.cmuxremote.data.Settings].
 */
class Identity(context: Context) {

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

    val privateKey: ByteArray
    val publicKey: ByteArray

    init {
        val existingPriv = prefs.getString(KEY_PRIVATE, null)
        if (existingPriv != null) {
            privateKey = Base64.decode(existingPriv, Base64.NO_WRAP)
            publicKey = Base64.decode(prefs.getString(KEY_PUBLIC, null), Base64.NO_WRAP)
        } else {
            val (priv, pub) = generateX25519KeyPair()
            prefs.edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(priv, Base64.NO_WRAP))
                .putString(KEY_PUBLIC, Base64.encodeToString(pub, Base64.NO_WRAP))
                .apply()
            privateKey = priv
            publicKey = pub
        }
    }

    private companion object {
        const val PREFS_NAME = "cmux_e2e_identity"
        const val KEY_PRIVATE = "private_key_b64"
        const val KEY_PUBLIC = "public_key_b64"
    }
}
```

- [ ] **Step 2: Verify the module still compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/Identity.kt
git commit -m "android: add persistent e2e identity keypair"
```

---

### Task 5: `data/e2e/ReplayWindow.kt` — sliding-window replay gate (pure logic)

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/ReplayWindow.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/ReplayWindowTest.kt`

**Interfaces:**
- Produces: `class ReplayWindow(highestSeen: Long = -1L, windowBits: Long = 0L)` with `val highestSeen: Long`, `val windowBits: Long` (read-only, for persistence round-tripping), `fun canAccept(n: Long): Boolean`, `fun commit(n: Long): ReplayWindow` (returns a new instance with the counter recorded — immutable, so callers can't accidentally commit before verifying) — consumed by `Session.kt` (Task 6).

This is the Android-side half of the "window on both sides" decision (spec:
"Replay gate" section) — replaces what would otherwise be a strict
`n <= last -> reject` check (wrong once a phone has three concurrent
agent-to-device channels: HTTP responses, `/terminal` WS, `/events` WS, all
drawing from one agent-side send counter with no cross-channel ordering
guarantee). The algorithm is a standard RFC 6479-style bitmap: `windowBits`
bit `i` set means counter `highestSeen - i` was already accepted; W = 64
fits exactly in one `Long`. **This class never touches storage** — Task 6
persists its two `Long` fields directly.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/ReplayWindowTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    @Test
    fun freshWindowAcceptsAnything() {
        val w = ReplayWindow()
        assertTrue(w.canAccept(0L))
        assertTrue(w.canAccept(1000L))
    }

    @Test
    fun inOrderAcceptThenRejectExactReplay() {
        var w = ReplayWindow()
        assertTrue(w.canAccept(5L))
        w = w.commit(5L)
        assertFalse(w.canAccept(5L)) // exact replay
        assertTrue(w.canAccept(6L)) // new high still fine
    }

    @Test
    fun outOfOrderWithinWindowIsAccepted() {
        var w = ReplayWindow()
        w = w.commit(10L)
        assertTrue(w.canAccept(7L)) // arrived late, within window, never seen
        w = w.commit(7L)
        assertFalse(w.canAccept(7L)) // now a replay
        assertEquals(10L, w.highestSeen) // committing a lower n doesn't move the high-water mark
    }

    @Test
    fun tooOldOutsideWindowIsRejected() {
        var w = ReplayWindow()
        w = w.commit(1000L)
        assertFalse(w.canAccept(1000L - 64L)) // exactly at the boundary: too old
        assertTrue(w.canAccept(1000L - 63L)) // one inside the boundary: still fine
    }

    @Test
    fun windowSlidesForwardAsNewHighsArrive() {
        var w = ReplayWindow()
        w = w.commit(100L)
        w = w.commit(50L) // accepted, within window at the time
        w = w.commit(200L) // big jump forward -- window re-centers on 200
        assertFalse(w.canAccept(50L)) // now outside the new window (200-50=150 > 64)
        assertTrue(w.canAccept(199L)) // still inside the new window
    }

    @Test
    fun replayAfterWindowSlidePastItIsStillRejectedAsExactMatch() {
        // A counter committed just before a big forward jump, then replayed
        // immediately after the jump but still within the new window, must
        // still be caught as a replay, not silently accepted as "old but new."
        var w = ReplayWindow()
        w = w.commit(100L)
        w = w.commit(101L) // small jump; 100 still within window (101-100=1)
        assertFalse(w.canAccept(100L))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.ReplayWindowTest"`
Expected: FAIL — `ReplayWindow` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/ReplayWindow.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

private const val WINDOW_SIZE = 64

/**
 * RFC 6479-style anti-replay bitmap: tolerates out-of-order delivery within
 * the last [WINDOW_SIZE] counters while still rejecting exact replays and
 * anything older than the window. Immutable -- [commit] returns a new
 * instance so callers can't advance state before an AEAD tag has verified
 * (see Global Constraints: canAccept is read-only, commit is separate).
 */
class ReplayWindow(val highestSeen: Long = -1L, val windowBits: Long = 0L) {

    /** Read-only: does NOT record [n] as seen. Call [commit] separately, and
     *  only after the corresponding ciphertext has verified. */
    fun canAccept(n: Long): Boolean {
        if (highestSeen < 0) return true
        if (n > highestSeen) return true
        val age = highestSeen - n
        if (age >= WINDOW_SIZE) return false
        val bit = 1L shl age.toInt()
        return (windowBits and bit) == 0L
    }

    /** Records [n] as seen, sliding the window forward if [n] is a new high. */
    fun commit(n: Long): ReplayWindow {
        if (highestSeen < 0) {
            return ReplayWindow(n, 1L)
        }
        if (n > highestSeen) {
            val shift = n - highestSeen
            val slid = if (shift >= WINDOW_SIZE) 0L else windowBits shl shift.toInt()
            return ReplayWindow(n, slid or 1L)
        }
        val age = highestSeen - n
        if (age >= WINDOW_SIZE) return this // already unrepresentable; no-op
        return ReplayWindow(highestSeen, windowBits or (1L shl age.toInt()))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.ReplayWindowTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/ReplayWindow.kt app/src/test/java/com/sodre90/cmuxremote/data/e2e/ReplayWindowTest.kt
git commit -m "android: add sliding-window replay gate"
```

---

### Task 6: `data/e2e/Session.kt` — paired-device state (persistence)

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Session.kt`

**Interfaces:**
- Consumes: `ReplayWindow` (Task 5).
- Produces: `interface PairedSession` (`fun sharedSecret(): ByteArray?`, `fun nextSendCounter(): Long`, `fun canAcceptRecvCounter(n: Long): Boolean`, `fun commitRecvCounter(n: Long)`) and `class Session(context: Context) : PairedSession` adding `fun isPaired(): Boolean`, `fun setPairing(peerPublicKey: ByteArray, sharedSecret: ByteArray)`, `fun clear()` — the interface is consumed by `Envelope.kt` (Task 7) and `Frame.kt` (Task 8) so their tests can substitute an in-memory fake instead of needing Android Keystore; the concrete class is consumed by `PairingClient.kt` (Task 11) and `AppContainer.kt` (Task 16).

**No unit test for the `Session` class itself**, same reasoning as
`Identity` (Task 4): `EncryptedSharedPreferences` needs Android Keystore.
All of its actual logic is either trivial (read/increment/store a `Long`)
or already covered by `ReplayWindowTest` (Task 5) — it is a thin
persistence shell around `ReplayWindow`. Splitting out `PairedSession` as
an interface is what makes Tasks 7-8's tests possible without Robolectric.

- [ ] **Step 1: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Session.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The subset of [Session] that [encryptBody]/[decryptBody]/[encryptFrame]/
 *  [decryptFrame] need -- lets tests substitute an in-memory fake. */
interface PairedSession {
    fun sharedSecret(): ByteArray?
    fun nextSendCounter(): Long
    fun canAcceptRecvCounter(n: Long): Boolean
    fun commitRecvCounter(n: Long)
}

/**
 * The phone's single paired-agent session: the derived shared secret, a
 * durable monotonic send counter, and the sliding-window receive gate.
 * Unlike the Go agent (which pairs with many devices), the phone pairs with
 * exactly one agent at a time -- re-pairing overwrites this record.
 */
class Session(context: Context) : PairedSession {

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

    fun isPaired(): Boolean = prefs.contains(KEY_SHARED_SECRET)

    override fun sharedSecret(): ByteArray? =
        prefs.getString(KEY_SHARED_SECRET, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Called once, by [com.sodre90.cmuxremote.data.pairing.PairingClient] after a
     *  successful pairing handshake. Resets counters and the replay window --
     *  a fresh pairing means a fresh shared secret, so old counter state is
     *  meaningless (and reusing it would incorrectly reject the first messages). */
    fun setPairing(peerPublicKey: ByteArray, sharedSecret: ByteArray) {
        prefs.edit()
            .putString(KEY_PEER_PUBLIC_KEY, Base64.encodeToString(peerPublicKey, Base64.NO_WRAP))
            .putString(KEY_SHARED_SECRET, Base64.encodeToString(sharedSecret, Base64.NO_WRAP))
            .putLong(KEY_SEND_COUNTER, 0L)
            .putLong(KEY_RECV_HIGHEST, -1L)
            .putLong(KEY_RECV_WINDOW_BITS, 0L)
            .apply()
    }

    /** Durable, never reset across reconnects (see Global Constraints). */
    override fun nextSendCounter(): Long {
        val n = prefs.getLong(KEY_SEND_COUNTER, 0L)
        prefs.edit().putLong(KEY_SEND_COUNTER, n + 1).apply()
        return n
    }

    private fun replayWindow(): ReplayWindow =
        ReplayWindow(prefs.getLong(KEY_RECV_HIGHEST, -1L), prefs.getLong(KEY_RECV_WINDOW_BITS, 0L))

    /** Read-only check -- call before attempting to decrypt. */
    override fun canAcceptRecvCounter(n: Long): Boolean = replayWindow().canAccept(n)

    /** Mutating -- call only after the corresponding ciphertext has verified. */
    override fun commitRecvCounter(n: Long) {
        val updated = replayWindow().commit(n)
        prefs.edit()
            .putLong(KEY_RECV_HIGHEST, updated.highestSeen)
            .putLong(KEY_RECV_WINDOW_BITS, updated.windowBits)
            .apply()
    }

    /** Wipes the whole session -- used when re-pairing or on the legacy-settings migration. */
    fun clear() {
        prefs.edit().clear().apply()
    }

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

- [ ] **Step 2: Verify the module still compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/Session.kt
git commit -m "android: add paired-session state (secret, counters, replay window)"
```

---

### Task 7: `data/e2e/Envelope.kt` — HTTP body envelope

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Envelope.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/EnvelopeTest.kt`

**Interfaces:**
- Consumes: `Cipher` (Task 3), `PairedSession`, `nonce`/`DIR_AGENT_TO_DEVICE`/`DIR_DEVICE_TO_AGENT` (Tasks 2-3, 6).
- Produces: `class NotPairedException : Exception("not_paired")`, `fun encryptBody(session: PairedSession, cipher: Cipher, plaintext: ByteArray): ByteArray`, `fun decryptBody(session: PairedSession, cipher: Cipher, envelopeBytes: ByteArray): ByteArray` — consumed by `E2eInterceptor.kt` (Task 9).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/EnvelopeTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory PairedSession double -- avoids needing Android Keystore in tests. */
class FakeSession(private var secret: ByteArray?) : PairedSession {
    private var sendCounter = 0L
    private var window = ReplayWindow()

    override fun sharedSecret(): ByteArray? = secret
    override fun nextSendCounter(): Long = sendCounter++
    override fun canAcceptRecvCounter(n: Long): Boolean = window.canAccept(n)
    override fun commitRecvCounter(n: Long) { window = window.commit(n) }
}

class EnvelopeTest {

    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Test
    fun encryptThenDecryptRoundTrips() {
        val secret = ByteArray(32) { it.toByte() }
        val sender = FakeSession(secret)
        val receiver = FakeSession(secret)
        val plaintext = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)

        // The phone is the sender here (outgoing == DIR_DEVICE_TO_AGENT), so
        // the receiver must open with the agent's perspective... but since
        // this test exercises the phone's own encryptBody/decryptBody pair
        // (both phone-side), decryptBody expects DIR_AGENT_TO_DEVICE-tagged
        // ciphertext (what the phone RECEIVES). We build that ciphertext
        // manually here to test decryptBody in isolation from encryptBody,
        // which always encrypts as DIR_DEVICE_TO_AGENT (what the phone SENDS).
        val n = receiver.nextSendCounter() // reuse counter machinery for a manual envelope
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, n), plaintext)
        val envelope = """{"v":1,"n":$n,"ct":"${android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)}"}"""

        val decrypted = decryptBody(sender, cipher, envelope.toByteArray(Charsets.UTF_8))
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptBodyProducesDecryptableEnvelopeForTheAgentDirection() {
        // encryptBody always tags DIR_DEVICE_TO_AGENT (phone's outgoing
        // direction) -- verify by opening it that way directly, matching
        // what the Go agent's DecryptBody does on receipt.
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val plaintext = "input text".toByteArray(Charsets.UTF_8)

        val envelopeBytes = encryptBody(session, cipher, plaintext)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(envelopeBytes.toString(Charsets.UTF_8)).jsonObject
        val n = json["n"]!!.jsonPrimitive.long
        val ctB64 = json["ct"]!!.jsonPrimitive.content
        val ct = android.util.Base64.decode(ctB64, android.util.Base64.NO_WRAP)

        val opened = cipher.open(secret, nonce(DIR_DEVICE_TO_AGENT, n), ct)
        assertArrayEquals(plaintext, opened)
    }

    @Test(expected = NotPairedException::class)
    fun encryptBodyThrowsWhenNotPaired() {
        encryptBody(FakeSession(null), cipher, "x".toByteArray())
    }

    @Test(expected = DecryptFailedException::class)
    fun decryptBodyRejectsReplayedCounter() {
        val secret = ByteArray(32) { it.toByte() }
        val receiver = FakeSession(secret)
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 0L), "x".toByteArray())
        val envelope = """{"v":1,"n":0,"ct":"${android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)}"}"""

        decryptBody(receiver, cipher, envelope.toByteArray(Charsets.UTF_8)) // first: fine
        decryptBody(receiver, cipher, envelope.toByteArray(Charsets.UTF_8)) // replay: must throw
    }
}
```

Note: this test uses `kotlinx.serialization.json.Json.parseToJsonElement` directly rather than the app's `BridgeJson`/typed DTOs, so it stays independent of `Envelope.kt`'s internal envelope data class shape. Add these imports at the top: `import kotlinx.serialization.json.jsonObject`, `import kotlinx.serialization.json.jsonPrimitive`, `import kotlinx.serialization.json.long`, `import kotlinx.serialization.json.content`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.EnvelopeTest"`
Expected: FAIL — `Envelope.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Envelope.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import android.util.Base64
import com.sodre90.cmuxremote.model.BridgeJson
import kotlinx.serialization.Serializable

@Serializable
private data class BodyEnvelope(val v: Int, val n: Long, val ct: String)

/** No shared secret on file -- the app's local state was wiped, or pairing
 *  never completed. Mirrors the Go side's 409 not_paired case. */
class NotPairedException : Exception("not_paired")

/**
 * Encrypts an outgoing HTTP request/response body into the wire envelope
 * `{"v":1,"n":<counter>,"ct":"<base64>"}`. The phone's outgoing direction is
 * DIR_DEVICE_TO_AGENT (see Global Constraints) -- mirrors
 * bridge/internal/e2e/envelope.go's EncryptBody exactly, mirrored.
 */
fun encryptBody(session: PairedSession, cipher: Cipher, plaintext: ByteArray): ByteArray {
    val secret = session.sharedSecret() ?: throw NotPairedException()
    val n = session.nextSendCounter()
    val ct = cipher.seal(secret, nonce(DIR_DEVICE_TO_AGENT, n), plaintext)
    val envelope = BodyEnvelope(v = 1, n = n, ct = Base64.encodeToString(ct, Base64.NO_WRAP))
    return BridgeJson.encodeToString(BodyEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
}

/**
 * Decrypts an incoming HTTP body. The phone's incoming direction is
 * DIR_AGENT_TO_DEVICE. Two-phase: the counter is checked BEFORE opening and
 * committed only after the AEAD tag verifies (see Global Constraints) --
 * mirrors bridge/internal/e2e/envelope.go's DecryptBody.
 */
fun decryptBody(session: PairedSession, cipher: Cipher, envelopeBytes: ByteArray): ByteArray {
    val envelope = try {
        BridgeJson.decodeFromString(BodyEnvelope.serializer(), envelopeBytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw DecryptFailedException()
    }
    if (envelope.v != 1) throw DecryptFailedException()
    val secret = session.sharedSecret() ?: throw NotPairedException()
    if (!session.canAcceptRecvCounter(envelope.n)) throw DecryptFailedException()
    val ct = try {
        Base64.decode(envelope.ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        throw DecryptFailedException()
    }
    val pt = cipher.open(secret, nonce(DIR_AGENT_TO_DEVICE, envelope.n), ct)
    session.commitRecvCounter(envelope.n)
    return pt
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.EnvelopeTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/Envelope.kt app/src/test/java/com/sodre90/cmuxremote/data/e2e/EnvelopeTest.kt
git commit -m "android: add HTTP body e2e envelope"
```

---

### Task 8: `data/e2e/Frame.kt` — WebSocket binary frame

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Frame.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/FrameTest.kt`

**Interfaces:**
- Consumes: `Cipher` (Task 3), `PairedSession` (Task 6), `nonce`/direction constants (Task 2).
- Produces: `fun encryptFrame(session: PairedSession, cipher: Cipher, plaintext: ByteArray): ByteArray`, `fun decryptFrame(session: PairedSession, cipher: Cipher, frame: ByteArray): ByteArray` — consumed by `TerminalSocket.kt` (Task 14), `EventsSocket.kt` (Task 15).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/FrameTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer

class FrameTest {

    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Test
    fun encryptFrameThenAgentSideDecodeRoundTrips() {
        // encryptFrame tags DIR_DEVICE_TO_AGENT (phone's outgoing direction);
        // verify by decoding as the agent would: read the 8-byte
        // big-endian counter prefix, then open with the same direction.
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val plaintext = """{"type":"input","text":"ls\n"}""".toByteArray(Charsets.UTF_8)

        val frame = encryptFrame(session, cipher, plaintext)
        val counter = ByteBuffer.wrap(frame, 0, 8).long
        val opened = cipher.open(secret, nonce(DIR_DEVICE_TO_AGENT, counter), frame.copyOfRange(8, frame.size))
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun decryptFrameOpensAgentOriginatedFrame() {
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val plaintext = """{"type":"replay"}""".toByteArray(Charsets.UTF_8)

        // Build a frame as the agent would send it: DIR_AGENT_TO_DEVICE, counter 0.
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 0L), plaintext)
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(0L)
        ct.copyInto(frame, 8)

        val decrypted = decryptFrame(session, cipher, frame)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = DecryptFailedException::class)
    fun decryptFrameRejectsTooShortFrame() {
        decryptFrame(FakeSession(ByteArray(32)), cipher, ByteArray(4))
    }

    @Test(expected = DecryptFailedException::class)
    fun decryptFrameRejectsReplayedCounter() {
        val secret = ByteArray(32) { it.toByte() }
        val session = FakeSession(secret)
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, 0L), "x".toByteArray())
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(0L)
        ct.copyInto(frame, 8)

        decryptFrame(session, cipher, frame) // first: fine
        decryptFrame(session, cipher, frame) // replay: must throw
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.FrameTest"`
Expected: FAIL — `Frame.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/Frame.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import java.nio.ByteBuffer

/**
 * Encrypts an outgoing WS message into `[8-byte big-endian counter][ciphertext+tag]`.
 * Phone's outgoing direction is DIR_DEVICE_TO_AGENT -- mirrors
 * bridge/internal/e2e/frame.go's EncodeFrame/EncryptFrame.
 */
fun encryptFrame(session: PairedSession, cipher: Cipher, plaintext: ByteArray): ByteArray {
    val secret = session.sharedSecret() ?: throw NotPairedException()
    val n = session.nextSendCounter()
    val ct = cipher.seal(secret, nonce(DIR_DEVICE_TO_AGENT, n), plaintext)
    val out = ByteArray(8 + ct.size)
    ByteBuffer.wrap(out, 0, 8).putLong(n)
    ct.copyInto(out, 8)
    return out
}

/**
 * Decrypts an incoming WS message. Phone's incoming direction is
 * DIR_AGENT_TO_DEVICE. Two-phase counter check, same as [decryptBody] --
 * mirrors bridge/internal/e2e/frame.go's DecodeFrame/DecryptFrame.
 */
fun decryptFrame(session: PairedSession, cipher: Cipher, frame: ByteArray): ByteArray {
    if (frame.size < 8) throw DecryptFailedException()
    val n = ByteBuffer.wrap(frame, 0, 8).long
    val secret = session.sharedSecret() ?: throw NotPairedException()
    if (!session.canAcceptRecvCounter(n)) throw DecryptFailedException()
    val pt = cipher.open(secret, nonce(DIR_AGENT_TO_DEVICE, n), frame.copyOfRange(8, frame.size))
    session.commitRecvCounter(n)
    return pt
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.FrameTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/Frame.kt app/src/test/java/com/sodre90/cmuxremote/data/e2e/FrameTest.kt
git commit -m "android: add WS binary frame e2e envelope"
```

---

### Task 9: `data/e2e/E2eInterceptor.kt` — OkHttp interceptor

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/E2eInterceptor.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/E2eInterceptorTest.kt`

**Interfaces:**
- Consumes: `encryptBody`/`decryptBody` (Task 7), `PairedSession`, `Cipher`.
- Produces: `class E2eInterceptor(session: PairedSession, cipher: Cipher) : okhttp3.Interceptor` — consumed by `AppContainer.kt` (Task 16).

**Critical detail this task must get right:** this interceptor sits on the
*same* `OkHttpClient` used for `TerminalSocket`/`EventsSocket`'s WebSocket
connections (see `AppContainer`'s existing single-client-per-config cache).
OkHttp runs application interceptors on the WS upgrade handshake too. A 101
Switching Protocols response has no JSON body to decrypt — if this
interceptor tried to run `decryptBody` on it, every WebSocket connection
would fail immediately with a spurious `decrypt_failed`. The implementation
below explicitly detects and skips `Upgrade: websocket` requests; the test
in Step 1 asserts this directly.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sodre90/cmuxremote/data/e2e/E2eInterceptorTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class E2eInterceptorTest {

    private lateinit var server: MockWebServer
    private val cipher = Cipher(LazySodiumJava(SodiumJava()))
    private val secret = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(session: PairedSession) = OkHttpClient.Builder()
        .addInterceptor(E2eInterceptor(session, cipher))
        .build()

    @Test
    fun encryptsRequestBodyAndDecryptsResponseBody() {
        val serverSideSession = FakeSession(secret) // stands in for the agent's own counters
        val agentReply = encryptFrameAsBody("""{"ok":true}""", serverSideSession)
        server.enqueue(MockResponse().setBody(agentReply))

        val client = clientFor(FakeSession(secret))
        val request = Request.Builder()
            .url(server.url("/sessions"))
            .post("""{"fcm_token":"t"}""".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val response = client.newCall(request).execute()

        assertEquals("""{"ok":true}""", response.body!!.string())

        val recorded = server.takeRequest()
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"v\":1")) // request body was encrypted, not the raw JSON
        assertTrue(!sentBody.contains("fcm_token"))
    }

    @Test
    fun skipsWebSocketUpgradeRequests() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("hello") // plaintext -- e2e for WS is handled by Frame.kt, not this interceptor
                }
            }),
        )

        val client = clientFor(FakeSession(secret))
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        val ws = client.newWebSocket(
            Request.Builder().url(server.url("/events")).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            },
        )

        val got = received.poll(5, TimeUnit.SECONDS)
        assertEquals("hello", got) // not intercepted/mangled as if it were an HTTP body
        ws.cancel()
    }

    /** Encodes [json] the way a Go agent's EncryptBody would, so the test client's
     *  E2eInterceptor has something valid to decrypt. */
    private fun encryptFrameAsBody(json: String, session: PairedSession): String {
        val n = session.nextSendCounter()
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, n), json.toByteArray(Charsets.UTF_8))
        return """{"v":1,"n":$n,"ct":"${android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)}"}"""
    }
}
```

Add `import okhttp3.MediaType.Companion.toMediaTypeOrNull` at the top.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.E2eInterceptorTest"`
Expected: FAIL — `E2eInterceptor` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/E2eInterceptor.kt`:

```kotlin
package com.sodre90.cmuxremote.data.e2e

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Transparently encrypts outgoing HTTP request bodies and decrypts incoming
 * response bodies for every call through this client -- BridgeClient and its
 * callers never see ciphertext. Skips WebSocket upgrade requests entirely
 * (see this task's header note) since those have no JSON body to encrypt;
 * WS frame encryption is handled separately by Frame.kt.
 */
class E2eInterceptor(private val session: PairedSession, private val cipher: Cipher) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header("Upgrade")?.equals("websocket", ignoreCase = true) == true) {
            return chain.proceed(original)
        }

        val requestBody = original.body
        val request = if (requestBody != null) {
            val plaintext = Buffer().also { requestBody.writeTo(it) }.readByteArray()
            val encrypted = try {
                encryptBody(session, cipher, plaintext)
            } catch (e: NotPairedException) {
                throw IOException("not_paired", e)
            }
            original.newBuilder()
                .method(original.method, encrypted.toRequestBody(requestBody.contentType()))
                .build()
        } else {
            original
        }

        val response = chain.proceed(request)
        val responseBody = response.body ?: return response
        val plaintext = try {
            decryptBody(session, cipher, responseBody.bytes())
        } catch (e: NotPairedException) {
            throw IOException("not_paired", e)
        } catch (e: DecryptFailedException) {
            throw IOException("decrypt_failed", e)
        }
        return response.newBuilder()
            .body(plaintext.toResponseBody(responseBody.contentType()))
            .build()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.e2e.E2eInterceptorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/e2e/E2eInterceptor.kt app/src/test/java/com/sodre90/cmuxremote/data/e2e/E2eInterceptorTest.kt
git commit -m "android: add OkHttp e2e interceptor, skip WS upgrade requests"
```

---

### Task 10: `data/pairing/PairingQr.kt` — QR payload DTO + validation

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingQr.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingQrTest.kt`

**Interfaces:**
- Produces: `@Serializable data class PairingQr(val pairUrl, val code, val agentPubkey, val expiresAt, val tenantId)`, `fun parsePairingQr(raw: String): PairingQr?` (null on malformed JSON), `fun PairingQr.isExpired(): Boolean` — consumed by `PairingClient.kt` (Task 11), `PairingViewModel.kt` (Task 17).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingQrTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PairingQrTest {

    @Test
    fun parsesValidPayload() {
        val raw = """{"pair_url":"https://cmux.example.com/devices/pair","code":"ABC123",
            "agent_pubkey":"YWJj","expires_at":"2099-01-01T00:00:00Z","tenant_id":"t1"}"""

        val qr = parsePairingQr(raw)

        assertEquals("https://cmux.example.com/devices/pair", qr?.pairUrl)
        assertEquals("ABC123", qr?.code)
        assertEquals("YWJj", qr?.agentPubkey)
        assertEquals("t1", qr?.tenantId)
    }

    @Test
    fun returnsNullForMalformedOrForeignQrContent() {
        assertNull(parsePairingQr("not json at all"))
        assertNull(parsePairingQr("""{"totally":"unrelated"}""")) // missing required fields still decodes with defaults...
    }

    @Test
    fun isExpiredTrueForPastTimestamp() {
        val qr = PairingQr(pairUrl = "u", code = "c", agentPubkey = "p", expiresAt = "2000-01-01T00:00:00Z", tenantId = "t")
        assertTrue(qr.isExpired())
    }

    @Test
    fun isExpiredFalseForFutureTimestamp() {
        val future = Instant.now().plusSeconds(600).toString()
        val qr = PairingQr(pairUrl = "u", code = "c", agentPubkey = "p", expiresAt = future, tenantId = "t")
        assertFalse(qr.isExpired())
    }

    @Test
    fun isExpiredFalseForUnparseableTimestamp() {
        // Can't tell -- don't block a possibly-valid code on our own parse failure;
        // the server's 410 pairing_code_invalid is the authoritative check.
        val qr = PairingQr(pairUrl = "u", code = "c", agentPubkey = "p", expiresAt = "garbage", tenantId = "t")
        assertFalse(qr.isExpired())
    }
}
```

Note: `returnsNullForMalformedOrForeignQrContent`'s second assertion
(`{"totally":"unrelated"}`) relies on `parsePairingQr` treating a missing
`code` field as invalid even though `kotlinx.serialization` would otherwise
happily decode it with an empty-string default — Step 3's implementation
must explicitly reject blank `code`/`pairUrl`, not just catch parse
exceptions.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.pairing.PairingQrTest"`
Expected: FAIL — `PairingQr` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingQr.kt`:

```kotlin
package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.model.BridgeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** The JSON payload rendered into the pairing QR by `cmux-bridge pair-device`
 *  (bridge/cmd/cmux-bridge/pair.go's pairingQR struct). */
@Serializable
data class PairingQr(
    @SerialName("pair_url") val pairUrl: String = "",
    val code: String = "",
    @SerialName("agent_pubkey") val agentPubkey: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("tenant_id") val tenantId: String = "",
)

/** Returns null for anything that isn't a valid pairing QR -- malformed
 *  JSON, or JSON missing the fields this flow actually needs. The camera
 *  scanner resumes scanning on null rather than crashing (a scanned code
 *  may simply be unrelated to cmux). */
fun parsePairingQr(raw: String): PairingQr? {
    val qr = try {
        BridgeJson.decodeFromString(PairingQr.serializer(), raw)
    } catch (e: Exception) {
        return null
    }
    if (qr.pairUrl.isBlank() || qr.code.isBlank() || qr.agentPubkey.isBlank()) return null
    return qr
}

/** Client-side expiry check so a stale/reused QR fails fast with a clear
 *  message instead of waiting on the server's 410. Unparseable timestamps
 *  are treated as not-expired -- the server is the authoritative check. */
fun PairingQr.isExpired(): Boolean = try {
    Instant.parse(expiresAt).isBefore(Instant.now())
} catch (e: Exception) {
    false
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.pairing.PairingQrTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingQr.kt app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingQrTest.kt
git commit -m "android: add pairing QR payload parsing and expiry check"
```

---

### Task 11: `data/pairing/PairingClient.kt` — complete the pairing handshake

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingClient.kt`
- Test: `android/app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingClientTest.kt`

**Interfaces:**
- Consumes: `PairingQr` (Task 10), `deriveSharedSecret` (Task 2), `Identity`, `Session` (Tasks 4, 6). Also needs a narrow persistence surface from `Settings` — this task adds `var baseUrl` / `var deviceToken` setters to the *existing* `Settings` class if not already present as mutable vars (they already are, per `Settings.kt`'s current `var baseUrl: String?` / `var deviceToken: String?` — no `Settings` change needed here; Task 12 is what trims `Settings`, and runs after this task touches it only as a consumer).
- Produces: `class PairingCodeInvalidException : Exception("pairing_code_invalid")`, `class PairingClient(http: OkHttpClient, identity: Identity, session: Session, settings: Settings)` with `suspend fun pair(qr: PairingQr)` — consumed by `PairingViewModel.kt` (Task 17).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingClientTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data.pairing

import com.sodre90.cmuxremote.data.e2e.deriveSharedSecret
import com.sodre90.cmuxremote.data.e2e.generateX25519KeyPair
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Records what PairingClient persisted -- stands in for real Settings/Session/Identity. */
private class FakeIdentity(val priv: ByteArray, val pub: ByteArray)

class PairingClientTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pairSuccessDerivesSecretAndPersistsTokenAndBaseUrl() {
        val (agentPriv, agentPub) = generateX25519KeyPair()
        val (phonePriv, phonePub) = generateX25519KeyPair()

        server.enqueue(MockResponse().setBody("""{"token":"tok-abc","tenant_id":"t1"}"""))

        val recordedBaseUrl = arrayOfNulls<String>(1)
        val recordedToken = arrayOfNulls<String>(1)
        var setPairingCalledWith: Pair<ByteArray, ByteArray>? = null

        val client = TestablePairingClient(
            http = http,
            phonePrivateKey = phonePriv,
            phonePublicKey = phonePub,
            onSetPairing = { peerPub, secret -> setPairingCalledWith = peerPub to secret },
            onSetBaseUrl = { recordedBaseUrl[0] = it },
            onSetToken = { recordedToken[0] = it },
        )

        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(),
            code = "CODE1",
            agentPubkey = android.util.Base64.encodeToString(agentPub, android.util.Base64.NO_WRAP),
            expiresAt = "2099-01-01T00:00:00Z",
            tenantId = "t1",
        )

        runBlocking { client.pair(qr) }

        assertEquals(server.url("/").toString().trimEnd('/'), recordedBaseUrl[0])
        assertEquals("tok-abc", recordedToken[0])

        val wantSecret = deriveSharedSecret(agentPriv, phonePub) // agent's side of the same ECDH
        assertArrayEquals(agentPub, setPairingCalledWith!!.first)
        assertArrayEquals(wantSecret, setPairingCalledWith!!.second)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/devices/pair", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"code\":\"CODE1\""))
    }

    @Test
    fun pairThrowsPairingCodeInvalidOn410() {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"pairing_code_invalid"}"""))
        val (phonePriv, phonePub) = generateX25519KeyPair()
        val client = TestablePairingClient(http, phonePriv, phonePub, {}, {}, {})
        val qr = PairingQr(
            pairUrl = server.url("/devices/pair").toString(), code = "X",
            agentPubkey = android.util.Base64.encodeToString(ByteArray(32), android.util.Base64.NO_WRAP),
            expiresAt = "2099-01-01T00:00:00Z", tenantId = "t",
        )
        try {
            runBlocking { client.pair(qr) }
            fail("expected PairingCodeInvalidException")
        } catch (e: PairingCodeInvalidException) {
            // expected
        }
    }
}
```

This test uses a `TestablePairingClient` seam (constructor callbacks instead
of concrete `Identity`/`Session`/`Settings`) rather than the real
`PairingClient`, since `Identity`/`Session`/`Settings` all require Android
Keystore (same constraint as Tasks 4 and 6). Step 3 below implements
`PairingClient` directly against the real classes; `TestablePairingClient`
is a small test-only subclass/wrapper defined in the test file itself.
Add this to the bottom of `PairingClientTest.kt`:

```kotlin
/** Test seam: same logic as PairingClient.pair, but with persistence as
 *  three callbacks instead of real Identity/Session/Settings instances. */
private class TestablePairingClient(
    private val http: OkHttpClient,
    private val phonePrivateKey: ByteArray,
    private val phonePublicKey: ByteArray,
    private val onSetPairing: (peerPublicKey: ByteArray, sharedSecret: ByteArray) -> Unit,
    private val onSetBaseUrl: (String) -> Unit,
    private val onSetToken: (String) -> Unit,
) {
    suspend fun pair(qr: PairingQr) = pairInternal(
        http, qr, phonePrivateKey, phonePublicKey, onSetPairing, onSetBaseUrl, onSetToken,
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.pairing.PairingClientTest"`
Expected: FAIL — `pairInternal` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingClient.kt`:

```kotlin
package com.sodre90.cmuxremote.data.pairing

import android.util.Base64
import com.sodre90.cmuxremote.data.Settings
import com.sodre90.cmuxremote.data.e2e.Identity
import com.sodre90.cmuxremote.data.e2e.Session
import com.sodre90.cmuxremote.data.e2e.deriveSharedSecret
import com.sodre90.cmuxremote.model.BridgeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The pairing code was not found, already redeemed, or expired -- the relay's
 *  RedeemPairingCode doesn't distinguish these (see the Go spec's error
 *  handling section), so neither does this. */
class PairingCodeInvalidException : Exception("pairing_code_invalid")

@Serializable
private data class DevicePairRequest(
    val code: String,
    val name: String,
    @SerialName("device_pubkey") val devicePubkey: String,
)

@Serializable
private data class DevicePairResponse(
    val token: String = "",
    @SerialName("tenant_id") val tenantId: String = "",
)

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Completes self-service pairing against POST /devices/pair: submits the
 * phone's e2e public key alongside the scanned code, and on success derives
 * the shared secret via ECDH and persists everything -- bearer token + base
 * URL into Settings, e2e session into Session. Mirrors
 * bridge/cmd/cmux-bridge/pair.go's agent-side half of the same handshake.
 */
class PairingClient(
    private val http: OkHttpClient,
    private val identity: Identity,
    private val session: Session,
    private val settings: Settings,
) {
    suspend fun pair(qr: PairingQr) = pairInternal(
        http = http,
        qr = qr,
        phonePrivateKey = identity.privateKey,
        phonePublicKey = identity.publicKey,
        onSetPairing = session::setPairing,
        onSetBaseUrl = { settings.baseUrl = it },
        onSetToken = { settings.deviceToken = it },
    )
}

/** Free function (not a Session/Settings method) so PairingClientTest can
 *  exercise the real handshake logic via plain callbacks -- see Task 11's
 *  Step 1 note on why Identity/Session/Settings can't be constructed in a
 *  local JVM unit test. */
internal suspend fun pairInternal(
    http: OkHttpClient,
    qr: PairingQr,
    phonePrivateKey: ByteArray,
    phonePublicKey: ByteArray,
    onSetPairing: (peerPublicKey: ByteArray, sharedSecret: ByteArray) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetToken: (String) -> Unit,
): Unit = withContext(Dispatchers.IO) {
    val payload = DevicePairRequest(
        code = qr.code,
        name = "phone",
        devicePubkey = Base64.encodeToString(phonePublicKey, Base64.NO_WRAP),
    )
    val request = Request.Builder()
        .url(qr.pairUrl)
        .post(BridgeJson.encodeToString(DevicePairRequest.serializer(), payload).toRequestBody(JSON_MEDIA))
        .build()

    http.newCall(request).execute().use { response ->
        if (response.code == 410) throw PairingCodeInvalidException()
        if (!response.isSuccessful) throw IOException("pairing failed: HTTP ${response.code}")
        val body = BridgeJson.decodeFromString(
            DevicePairResponse.serializer(),
            response.body?.string().orEmpty(),
        )

        val agentPublicKey = Base64.decode(qr.agentPubkey, Base64.NO_WRAP)
        val sharedSecret = deriveSharedSecret(phonePrivateKey, agentPublicKey)

        onSetPairing(agentPublicKey, sharedSecret)
        onSetBaseUrl(baseUrlFromPairUrl(qr.pairUrl))
        onSetToken(body.token)
    }
}

/** "https://host/devices/pair" -> "https://host" -- the same main vhost the
 *  bridge's other endpoints live on (bridge/cmd/cmux-bridge/pair.go derives
 *  the QR's pair_url this same way, in reverse). */
private fun baseUrlFromPairUrl(pairUrl: String): String = pairUrl.removeSuffix("/devices/pair")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.pairing.PairingClientTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingClient.kt app/src/test/java/com/sodre90/cmuxremote/data/pairing/PairingClientTest.kt
git commit -m "android: add pairing handshake client"
```

---

### Task 12: `data/Settings.kt` — add legacy-prefs wipe (purely additive)

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt`

**Interfaces:**
- Produces: adds an `init` block; every existing public member (`baseUrl`, `deviceToken`, `serverCaPem`, `p12Password`, `hasClientCert`, `setClientP12`, `clientP12()`, `bridgeConfig()`) is otherwise **untouched**.

**Deliberately does not remove the old manual-setup fields yet, even though
the design spec calls for their eventual removal.** `SettingsViewModel.kt`/
`SettingsScreen.kt` still reference `serverCaPem`/`p12Password`/
`hasClientCert`/`setClientP12`/`clientP12()` and aren't deleted until Task
18 (once `CmuxNavHost.kt` stops routing to them). Removing those fields
here would break the whole module's compile for the entire span of Tasks
12-17 — every later task's `./gradlew :app:testDebugUnitTest` would fail at
the compilation step before any test even ran, regardless of how correct
that task's own new code is. This task is purely additive; Task 18 does the
actual removal, bundled with deleting the last files that reference them.

**No unit test** — same `EncryptedSharedPreferences`/Android-Keystore
reasoning as Tasks 4 and 6. The one new piece of logic (the legacy-key wipe
check) is a two-line branch not worth extracting into a separate testable
unit the way `ReplayWindow` was.

- [ ] **Step 1: Add the legacy-wipe init block**

In `android/app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt`,
add this `init` block immediately after the `prefs` property declaration
(before `var baseUrl`):

```kotlin
    init {
        // An upgrading install may still have the pre-pairing manual-setup
        // format's client-cert key on disk. Wipe the whole prefs file once
        // and force re-pairing. Self-terminating: nothing writes this key
        // again once cleared (Task 18 removes the code path that ever did),
        // so this branch never fires on later launches.
        if (prefs.contains(KEY_P12)) {
            prefs.edit().clear().apply()
        }
    }
```

Nothing else in the file changes — `baseUrl`, `deviceToken`, `serverCaPem`,
`p12Password`, `hasClientCert`, `setClientP12`, `clientP12()`,
`bridgeConfig()`, and the existing `companion object` constants (including
`KEY_P12`, already defined) all stay exactly as they are today.

- [ ] **Step 2: Verify the module still compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt
git commit -m "android: wipe legacy manual-setup prefs on upgrade"
```

---

### Task 13: `data/Mtls.kt` — bearer-only transport (keep `BridgeConfig`'s shape for now)

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/Mtls.kt`
- Modify: `android/app/src/test/java/com/sodre90/cmuxremote/data/MtlsTest.kt`

**Interfaces:**
- Produces: `object Mtls { fun client(cfg: BridgeConfig): OkHttpClient }` now builds a bearer-only client (system trust store, no client cert) — consumed by `AppContainer.kt` (Task 16). `internal class BearerInterceptor` unchanged.

**`BridgeConfig`'s constructor is deliberately NOT simplified in this
task**, even though the design spec's end state has a 2-param class. The
*old* `AppContainer.kt` (not rewritten until Task 16) still reads
`cfg.serverCaPem` and `cfg.clientP12` inside its `httpClient()` cache-key
logic — removing those fields now would break `AppContainer.kt`'s compile
for the entire span of Tasks 13-16, the same class of problem Task 12's
note above describes for `Settings.kt`. This task only changes
`Mtls.client(cfg)`'s *implementation* (what it does with `cfg`), not the
`BridgeConfig` class itself. Task 18 removes the now-truly-unused fields
once every consumer has moved on.

**This task does not follow strict test-first ordering, and that's
deliberate.** `Mtls.client()`'s new bearer-only behavior is a strict subset
of what the old implementation already did (it still attaches the bearer
header) — a test asserting just that would already pass against the old
implementation, so there's no way to get a genuine RED state first for the
behavior that's actually changing (dropping the TLS customization, which
has no direct test either way — it's exercised implicitly by every other
test in this suite using `MockWebServer`'s default HTTP, not HTTPS with a
custom trust manager). Instead: simplify the implementation, then confirm
the (unchanged) bearer-token test still passes.

- [ ] **Step 1: Rewrite Mtls.kt's implementation, keep BridgeConfig's class declaration**

Replace the full contents of `android/app/src/main/java/com/sodre90/cmuxremote/data/Mtls.kt`:

```kotlin
package com.sodre90.cmuxremote.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Everything needed to reach the bridge: its base URL, the per-device
 * bearer token minted by pairing, and (still declared here for
 * [AppContainer]'s pre-Task-16 cache-key logic to keep compiling in the
 * interim -- see Task 18, which removes these two fields once nothing
 * reads them anymore) the now-unused client-cert/CA fields from the old
 * manual-setup flow.
 */
class BridgeConfig(
    val baseUrl: String,
    val deviceToken: String,
    val clientP12: ByteArray? = null,
    val p12Password: String = "",
    val serverCaPem: String? = null,
)

/**
 * Builds the single [OkHttpClient] used for every bridge call (HTTP + WS).
 * The relay presents a publicly-trusted server certificate (Let's
 * Encrypt, per the multi-tenant relay design), so no custom trust manager
 * is needed -- the platform default trust store applies. No client
 * certificate is presented either: self-service pairing replaced the old
 * mTLS-client-cert setup entirely, so [cfg]'s `clientP12`/`serverCaPem`
 * fields are intentionally unused here now.
 */
object Mtls {
    fun client(cfg: BridgeConfig): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor(cfg.deviceToken))
            .build()
}

/** Adds `Authorization: Bearer <token>` to every request (when a token is set). */
internal class BearerInterceptor(private val token: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = if (token.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
```

This deletes the old `keyManagers()`/`trustManager()` private functions and
their `javax.net.ssl`/`java.security` imports, but keeps `BridgeConfig`'s
5-parameter shape (with defaults) exactly as it already was.

- [ ] **Step 2: Rewrite MtlsTest.kt**

Replace the full contents of `android/app/src/test/java/com/sodre90/cmuxremote/data/MtlsTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MtlsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun clientAttachesBearerToken() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = Mtls.client(BridgeConfig(baseUrl = server.url("/").toString(), deviceToken = "tok-9"))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertEquals("Bearer tok-9", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun clientOmitsAuthorizationHeaderWhenTokenBlank() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = Mtls.client(BridgeConfig(baseUrl = server.url("/").toString(), deviceToken = ""))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
```

`BridgeConfig(baseUrl = ..., deviceToken = ...)` compiles fine against the
5-parameter class above (the other three all default) — this test file
does not need to know or care that those extra fields still exist.

- [ ] **Step 3: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.MtlsTest"`
Expected: PASS (2 tests).

- [ ] **Step 4: Run the full unit test suite as a regression check**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, every test (old and new) passing. The old
`AppContainer.kt` (not yet rewritten until Task 16) still compiles: it
reads `cfg.serverCaPem`/`cfg.clientP12`, both of which still exist on
`BridgeConfig` per Step 1 above.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/Mtls.kt app/src/test/java/com/sodre90/cmuxremote/data/MtlsTest.kt
git commit -m "android: bearer-only transport, drop client-cert TLS logic"
```

---

### Task 14: `data/TerminalSocket.kt` — encrypted binary WS frames

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt`
- Modify: `android/app/src/test/java/com/sodre90/cmuxremote/data/TerminalSocketTest.kt`

**Interfaces:**
- Consumes: `PairedSession`, `Cipher`, `encryptFrame`/`decryptFrame` (Tasks 3, 6, 8).
- Produces: `class TerminalSocket(http: OkHttpClient, baseUrl: String, surfaceId: String, session: PairedSession, cipher: Cipher)` — same `connect()`/`send()`/`close()` public shape as before, consumed by `AppContainer.kt` (Task 16).

- [ ] **Step 1: Update the test for binary frames**

Replace the full contents of `android/app/src/test/java/com/sodre90/cmuxremote/data/TerminalSocketTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.DIR_AGENT_TO_DEVICE
import com.sodre90.cmuxremote.data.e2e.DIR_DEVICE_TO_AGENT
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.ReplayWindow
import com.sodre90.cmuxremote.data.e2e.nonce
import com.sodre90.cmuxremote.model.RenderGridDecoder
import com.sodre90.cmuxremote.model.TerminalDown
import com.sodre90.cmuxremote.model.TerminalUp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Simple PairedSession double sharing one secret with independent counters,
 *  used to simulate "the other side" (a mock agent) in these WS tests. */
private class SharedSecretSession(private val secret: ByteArray) : PairedSession {
    private var sendCounter = 0L
    private var window = ReplayWindow()
    override fun sharedSecret(): ByteArray = secret
    override fun nextSendCounter(): Long = sendCounter++
    override fun canAcceptRecvCounter(n: Long): Boolean = window.canAccept(n)
    override fun commitRecvCounter(n: Long) { window = window.commit(n) }
}

class TerminalSocketTest {

    private lateinit var server: MockWebServer
    private val received = LinkedBlockingQueue<ByteString>()
    private val secret = ByteArray(32) { it.toByte() }
    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun receivesReplayFrameAndSendsEncryptedInput() = runBlocking {
        val serverSession = SharedSecretSession(secret)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val plaintext = """{"type":"replay","columns":3,"rows":1,"seq":1,
                        "grid":{"columns":3,"rows":1,"row_spans":[{"row":0,"column":0,"text":"hi"}]}}"""
                    val n = serverSession.nextSendCounter()
                    val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, n), plaintext.toByteArray(Charsets.UTF_8))
                    val frame = ByteArray(8 + ct.size)
                    ByteBuffer.wrap(frame, 0, 8).putLong(n)
                    ct.copyInto(frame, 8)
                    webSocket.send(frame.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received.add(bytes)
                }
            }),
        )

        val clientSession = SharedSecretSession(secret)
        val ts = TerminalSocket(OkHttpClient(), server.url("/").toString(), "surface-1", clientSession, cipher)

        withTimeout(5_000) {
            val first = CompletableDeferred<TerminalDown>()
            val job = launch(Dispatchers.IO) {
                ts.connect().collect { frame ->
                    if (!first.isCompleted) {
                        ts.send(TerminalUp(type = "input", text = "ls\n"))
                        first.complete(frame)
                    }
                }
            }

            val frame = first.await()
            assertEquals("replay", frame.type)
            // columns=3 so "hi" is padded with one trailing blank to full width.
            assertEquals("hi ", RenderGridDecoder.decode(frame.grid!!).lines[0].text)

            val gotBytes = withContext(Dispatchers.IO) { received.poll(5, TimeUnit.SECONDS) }
            assertNotNull(gotBytes)
            // Decode as the agent would: an 8-byte counter prefix, then open
            // with DIR_DEVICE_TO_AGENT (the phone's outgoing direction).
            val raw = gotBytes!!.toByteArray()
            val n = ByteBuffer.wrap(raw, 0, 8).long
            val opened = cipher.open(secret, nonce(DIR_DEVICE_TO_AGENT, n), raw.copyOfRange(8, raw.size))
            assertTrue(String(opened, Charsets.UTF_8).contains("\"type\":\"input\""))

            job.cancelAndJoin()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.TerminalSocketTest"`
Expected: FAIL — compilation error (`TerminalSocket` doesn't take a `session`/`cipher` constructor param yet).

- [ ] **Step 3: Rewrite TerminalSocket.kt**

Replace the full contents of `android/app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt`:

```kotlin
package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.decryptFrame
import com.sodre90.cmuxremote.data.e2e.encryptFrame
import com.sodre90.cmuxremote.model.BridgeJson
import com.sodre90.cmuxremote.model.TerminalDown
import com.sodre90.cmuxremote.model.TerminalUp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Bidirectional `WS /terminal/{surfaceId}`: [connect] streams server frames
 * (replay snapshot then output updates), [send] pushes input/paste/resize.
 * Every frame is XChaCha20-Poly1305-encrypted binary (see data/e2e/Frame.kt) --
 * plaintext JSON-over-WS is no longer the wire format.
 */
class TerminalSocket(
    private val http: OkHttpClient,
    baseUrl: String,
    surfaceId: String,
    private val session: PairedSession,
    private val cipher: Cipher,
) {
    private val url = "${baseUrl.trimEnd('/')}/terminal/$surfaceId"

    @Volatile
    private var socket: WebSocket? = null

    fun connect(): Flow<TerminalDown> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { decryptFrame(session, cipher, bytes.toByteArray()) }
                    .mapCatching { BridgeJson.decodeFromString(TerminalDown.serializer(), it.toString(Charsets.UTF_8)) }
                    .onFailure { android.util.Log.w("TerminalSocket", "dropped frame: ${it.message}") }
                    .getOrNull()
                    ?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })
        socket = ws
        awaitClose {
            ws.cancel()
            if (socket === ws) socket = null
        }
    }

    /** Sends a client->server message; no-op if the socket is not open. */
    fun send(up: TerminalUp) {
        val plaintext = BridgeJson.encodeToString(TerminalUp.serializer(), up).toByteArray(Charsets.UTF_8)
        socket?.send(encryptFrame(session, cipher, plaintext).toByteString())
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
```

Note: `it.toString(Charsets.UTF_8)` in `.mapCatching { ... }` calls
`ByteArray.toString(Charset)`, not `Any.toString()` — this is a real Kotlin
stdlib extension (`kotlin.text.toString(charset: Charset): String`), not a
typo; confirm it resolves during Step 4's build if unsure.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.TerminalSocketTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt app/src/test/java/com/sodre90/cmuxremote/data/TerminalSocketTest.kt
git commit -m "android: encrypt terminal WebSocket frames"
```

---

### Task 15: `data/EventsSocket.kt` — encrypted binary WS frames

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/EventsSocket.kt`
- Modify: `android/app/src/test/java/com/sodre90/cmuxremote/data/EventsSocketTest.kt`

**Interfaces:**
- Consumes: `PairedSession`, `Cipher`, `decryptFrame` (Tasks 3, 6, 8).
- Produces: `class EventsSocket(http: OkHttpClient, baseUrl: String, session: PairedSession, cipher: Cipher)` — same `connect()` public shape as before, consumed by `AppContainer.kt` (Task 16).

- [ ] **Step 1: Update the test for binary frames**

Replace the full contents of `android/app/src/test/java/com/sodre90/cmuxremote/data/EventsSocketTest.kt`:

```kotlin
package com.sodre90.cmuxremote.data

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.DIR_AGENT_TO_DEVICE
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.ReplayWindow
import com.sodre90.cmuxremote.data.e2e.nonce
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

private class SendOnlySession(private val secret: ByteArray) : PairedSession {
    private var counter = 0L
    private val window = ReplayWindow()
    override fun sharedSecret(): ByteArray = secret
    override fun nextSendCounter(): Long = counter++
    override fun canAcceptRecvCounter(n: Long): Boolean = window.canAccept(n)
    override fun commitRecvCounter(n: Long) {}
}

class EventsSocketTest {

    private lateinit var server: MockWebServer
    private val secret = ByteArray(32) { it.toByte() }
    private val cipher = Cipher(LazySodiumJava(SodiumJava()))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun frameFor(json: String, counter: Long): okio.ByteString {
        val ct = cipher.seal(secret, nonce(DIR_AGENT_TO_DEVICE, counter), json.toByteArray(Charsets.UTF_8))
        val frame = ByteArray(8 + ct.size)
        ByteBuffer.wrap(frame, 0, 8).putLong(counter)
        ct.copyInto(frame, 8)
        return frame.toByteString()
    }

    @Test
    fun emitsTwoDecodedFrames() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        frameFor(
                            """{"type":"feed","name":"feed.updated","needs_attention":true,"feed_id":"f1","kind":"permissionRequest"}""",
                            0L,
                        ),
                    )
                    webSocket.send(frameFor("""{"type":"heartbeat"}""", 1L))
                }
            }),
        )

        val es = EventsSocket(OkHttpClient(), server.url("/").toString(), SendOnlySession(secret), cipher)
        val frames = runBlocking { withTimeout(5_000) { es.connect().take(2).toList() } }

        assertEquals(2, frames.size)
        assertEquals("feed", frames[0].type)
        assertTrue(frames[0].needsAttention)
        assertEquals("f1", frames[0].feedId)
        assertEquals("permissionRequest", frames[0].kind)
        assertEquals("heartbeat", frames[1].type)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.EventsSocketTest"`
Expected: FAIL — compilation error (`EventsSocket` doesn't take `session`/`cipher` yet).

- [ ] **Step 3: Rewrite EventsSocket.kt**

Replace the full contents of `android/app/src/main/java/com/sodre90/cmuxremote/data/EventsSocket.kt`:

```kotlin
package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.decryptFrame
import com.sodre90.cmuxremote.model.BridgeJson
import com.sodre90.cmuxremote.model.EventFrame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Streams the bridge's `WS /events` as decoded [EventFrame]s. Every frame is
 *  XChaCha20-Poly1305-encrypted binary (see data/e2e/Frame.kt). */
class EventsSocket(
    private val http: OkHttpClient,
    baseUrl: String,
    private val session: PairedSession,
    private val cipher: Cipher,
) {
    private val url = "${baseUrl.trimEnd('/')}/events"

    /** Cold flow; opening the socket on collect and closing it on cancel. */
    fun connect(): Flow<EventFrame> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { decryptFrame(session, cipher, bytes.toByteArray()) }
                    .mapCatching { BridgeJson.decodeFromString(EventFrame.serializer(), it.toString(Charsets.UTF_8)) }
                    .getOrNull()
                    ?.let { trySend(it) }
            }
        })
        awaitClose { socket.cancel() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sodre90.cmuxremote.data.EventsSocketTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/EventsSocket.kt app/src/test/java/com/sodre90/cmuxremote/data/EventsSocketTest.kt
git commit -m "android: encrypt events WebSocket frames"
```

---

### Task 16: `data/AppContainer.kt` — wire e2e singletons

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/AppContainer.kt`

**Interfaces:**
- Consumes: `Identity`, `Session`, `Cipher` (Tasks 3, 4, 6), `E2eInterceptor` (Task 9), `PairingClient` (Task 11), the now-simplified `Mtls`/`BridgeConfig` (Task 13), the now-5-param `TerminalSocket`/`EventsSocket` (Tasks 14-15).
- Produces: `val identity: Identity`, `val session: Session`, `val cipher: Cipher`, `fun pairingClient(): PairingClient` added to `AppContainer`; `bridgeClient()`/`eventsSocket()`/`terminalSocket()` keep their existing public signatures — consumed by every `ui/*ViewModel.kt`.

**No unit test** — `AppContainer` directly constructs `Identity`/`Session`
(Android Keystore-dependent, same reasoning as Tasks 4 and 6) and is pure
wiring with no independent logic of its own.

- [ ] **Step 1: Rewrite AppContainer.kt**

Replace the full contents of `android/app/src/main/java/com/sodre90/cmuxremote/data/AppContainer.kt`:

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

/**
 * Manual dependency container held by [com.sodre90.cmuxremote.CmuxApp]. Builds the
 * shared [OkHttpClient] (bearer token + opt-in e2e encryption) from the
 * current [Settings]/[Session] and hands out bridge clients/sockets.
 * Returns null while the bridge is not yet paired.
 */
class AppContainer(appContext: Context) {

    val settings = Settings(appContext)
    val identity = Identity(appContext)
    val session = Session(appContext)
    val cipher = Cipher(LazySodiumAndroid(SodiumAndroid()))

    private var clientKey: String? = null
    private var client: OkHttpClient? = null

    @Synchronized
    private fun httpClient(cfg: BridgeConfig): OkHttpClient {
        val key = "${cfg.baseUrl}|${cfg.deviceToken}|${session.isPaired()}"
        if (key != clientKey || client == null) {
            var built = Mtls.client(cfg)
            if (session.isPaired()) {
                built = built.newBuilder().addInterceptor(E2eInterceptor(session, cipher)).build()
            }
            client = built
            clientKey = key
        }
        return client!!
    }

    fun bridgeClient(): BridgeClient? =
        settings.bridgeConfig()?.let { BridgeClient(httpClient(it), it.baseUrl) }

    fun eventsSocket(): EventsSocket? =
        settings.bridgeConfig()?.let { EventsSocket(httpClient(it), it.baseUrl, session, cipher) }

    fun terminalSocket(surfaceId: String): TerminalSocket? =
        settings.bridgeConfig()?.let { TerminalSocket(httpClient(it), it.baseUrl, surfaceId, session, cipher) }

    /** Unauthenticated -- POST /devices/pair takes no bearer token (see
     *  bridge/internal/relay/relay.go's handleDevicePair). */
    fun pairingClient(): PairingClient = PairingClient(OkHttpClient(), identity, session, settings)
}
```

- [ ] **Step 2: Verify the module still compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. `SettingsViewModel.kt`/`SettingsScreen.kt`
still exist and still compile at this point — Tasks 12-13 deliberately kept
`Settings`/`BridgeConfig`'s old fields in place until Task 18 retires them,
specifically so every task in between (including this one) compiles
cleanly on its own.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/data/AppContainer.kt
git commit -m "android: wire e2e identity/session/cipher into AppContainer"
```

---

### Task 17: `ui/pairing/PairingViewModel.kt` + `PairingScreen.kt`

**Files:**
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingViewModel.kt`
- Create: `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.pairingClient()` (Task 16), `parsePairingQr`/`isExpired` (Task 10), `PairingCodeInvalidException` (Task 11).
- Produces: `sealed interface PairingUiState` (`Scanning`, `Pairing`, `Success`, `Error(message)`), `class PairingViewModel(container: AppContainer) : ViewModel()` with `val state: PairingUiState`, `fun onQrScanned(raw: String)`, `fun retry()`; `@Composable fun PairingScreen(vm: PairingViewModel, onPaired: () -> Unit)` — consumed by `CmuxNavHost.kt` (Task 18).

**No unit test for either file**, matching this codebase's established
pattern: `TerminalViewModel`, `InboxViewModel`, and `SessionsViewModel` all
take `AppContainer` directly and have no corresponding `*ViewModelTest.kt`
(the one exception, `SessionsLogicTest.kt`, tests a separately-extracted
pure-function file, not a ViewModel) — and no `*Screen.kt` in this project
has a test (Compose UI testing infra isn't set up here). `PairingQr`
parsing/expiry logic (the part that actually has edge cases) is already
covered by `PairingQrTest` (Task 10).

- [ ] **Step 1: Write PairingViewModel.kt**

Create `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingViewModel.kt`:

```kotlin
package com.sodre90.cmuxremote.ui.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.data.pairing.PairingCodeInvalidException
import com.sodre90.cmuxremote.data.pairing.isExpired
import com.sodre90.cmuxremote.data.pairing.parsePairingQr
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data object Scanning : PairingUiState
    data object Pairing : PairingUiState
    data object Success : PairingUiState
    data class Error(val message: String) : PairingUiState
}

/** Backs the QR-scan onboarding screen. [onQrScanned] is called by the
 *  camera analyzer on every decoded barcode payload -- most calls are
 *  ignored (foreign QR content, or a scan while already mid-pairing). */
class PairingViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf<PairingUiState>(PairingUiState.Scanning)
        private set

    fun onQrScanned(raw: String) {
        if (state !is PairingUiState.Scanning) return
        val qr = parsePairingQr(raw) ?: return
        if (qr.isExpired()) {
            state = PairingUiState.Error("This code has expired -- ask the Mac to generate a new one.")
            return
        }
        state = PairingUiState.Pairing
        viewModelScope.launch {
            try {
                container.pairingClient().pair(qr)
                state = PairingUiState.Success
            } catch (e: PairingCodeInvalidException) {
                state = PairingUiState.Error("This code has expired or was already used -- scan a fresh one.")
            } catch (e: Exception) {
                state = PairingUiState.Error(e.message ?: "Pairing failed")
            }
        }
    }

    /** Returns to the scanning state after an error. */
    fun retry() {
        state = PairingUiState.Scanning
    }
}
```

- [ ] **Step 2: Write PairingScreen.kt**

Create `android/app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingScreen.kt`:

```kotlin
package com.sodre90.cmuxremote.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

@Composable
fun PairingScreen(vm: PairingViewModel, onPaired: () -> Unit) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(vm.state) {
        if (vm.state is PairingUiState.Success) onPaired()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair with cmux-bridge") }) }) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner),
            verticalArrangement = Arrangement.Top,
        ) {
            when (val state = vm.state) {
                is PairingUiState.Scanning -> if (hasCameraPermission) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraPreview(onQrDetected = vm::onQrScanned)
                    }
                } else {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Camera permission is required to scan the pairing QR code.")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant camera permission")
                        }
                    }
                }
                is PairingUiState.Pairing -> Text("Pairing...", modifier = Modifier.padding(16.dp))
                is PairingUiState.Error -> Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message)
                    Button(onClick = vm::retry) { Text("Scan again") }
                }
                is PairingUiState.Success -> Unit // LaunchedEffect above navigates away
            }
        }
    }
}

@Composable
private fun CameraPreview(onQrDetected: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder().build().also { analysis ->
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                            processImageProxy(scanner, imageProxy, onQrDetected)
                        }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(scanner: BarcodeScanner, imageProxy: ImageProxy, onQrDetected: (String) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let(onQrDetected) }
        .addOnCompleteListener { imageProxy.close() }
}
```

CameraX/ML Kit API names above (`ProcessCameraProvider`, `Preview.Builder`,
`ImageAnalysis.Builder`, `PreviewView`, `CameraSelector.DEFAULT_BACK_CAMERA`,
`BarcodeScanning.getClient`, `InputImage.fromMediaImage`) are long-stable,
widely-documented APIs unlike the crypto libraries earlier in this plan
(which had genuine, verified gaps) — high confidence, but if anything fails
to resolve during the build in Step 3, check the exact class/package
against `androidx.camera:*:1.5.3` and `com.google.mlkit:barcode-scanning:
17.3.0`'s current docs before assuming the logic itself is wrong.

- [ ] **Step 3: Verify the module compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd android && git add app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingViewModel.kt app/src/main/java/com/sodre90/cmuxremote/ui/pairing/PairingScreen.kt
git commit -m "android: add QR pairing screen (CameraX + ML Kit)"
```

---

### Task 18: Wire `PairingScreen` into onboarding, delete dead manual-setup code

**Files:**
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsScreen.kt`
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/Mtls.kt` (finally simplify `BridgeConfig` to 2 params)
- Modify: `android/app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt` (finally remove the old manual-setup fields)
- Delete: `android/app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsScreen.kt`
- Delete: `android/app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `PairingScreen`/`PairingViewModel` (Task 17).
- Produces: `BridgeConfig(val baseUrl: String, val deviceToken: String)` (finally the strict 2-param shape the design spec describes); `Settings` loses `serverCaPem`, `p12Password`, `hasClientCert`, `setClientP12`, `clientP12()` — nothing else in this plan reads any of these by this point.

This task is the payoff for the scaffolding Tasks 12 and 13 deliberately
left in place: `AppContainer.kt` (Task 16) is the last consumer of
`BridgeConfig`'s extra fields and `Settings`'s old manual-setup members,
and it was already rewritten in Task 16 to not reference any of them. Once
`SettingsScreen.kt`/`SettingsViewModel.kt` (the only other consumers) are
deleted in this same task, every field this task removes is genuinely
unreferenced — confirm that with Step 1 before deleting anything.

- [ ] **Step 1: Confirm nothing else references what's about to be removed**

Run:
```bash
cd android
grep -rn "SettingsScreen\|SettingsViewModel" app/src --include="*.kt"
grep -rn "serverCaPem\|p12Password\|hasClientCert\|setClientP12\|clientP12(" app/src --include="*.kt"
```
Expected: the first grep matches only `ui/CmuxNavHost.kt` (fixed in Step 2
below) and the two files being deleted; the second matches only
`ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, and
`data/Mtls.kt`'s/`data/Settings.kt`'s own declarations (fixed in Steps 5-6
below). If anything else matches, stop and investigate before deleting.

- [ ] **Step 2: Rewire CmuxNavHost.kt's onboarding route**

In `android/app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt`, replace the imports:

```kotlin
import com.sodre90.cmuxremote.ui.settings.SettingsScreen
import com.sodre90.cmuxremote.ui.settings.SettingsViewModel
```

with:

```kotlin
import com.sodre90.cmuxremote.ui.pairing.PairingScreen
import com.sodre90.cmuxremote.ui.pairing.PairingViewModel
```

Then replace the `composable(Routes.SETTINGS) { ... }` block:

```kotlin
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(container.settings) } },
            )
            SettingsScreen(
                vm = vm,
                onSaved = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                    }
                },
            )
        }
```

with:

```kotlin
        composable(Routes.SETTINGS) {
            val vm: PairingViewModel = viewModel(
                factory = viewModelFactory { initializer { PairingViewModel(container) } },
            )
            PairingScreen(
                vm = vm,
                onPaired = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                    }
                },
            )
        }
```

Everything else in this file (the `configured`/`start` logic, the other
routes) is unchanged — `Routes.SETTINGS`'s string value and its role as
the conditional start destination stay exactly as they are; only the
screen/ViewModel it renders changes.

- [ ] **Step 3: Update the "Settings" entry point's label**

In `android/app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsScreen.kt`, find:

```kotlin
                    TextButton(onClick = onSettings) { Text("Settings") }
```

Replace with:

```kotlin
                    TextButton(onClick = onSettings) { Text("Re-pair device") }
```

The `onSettings` callback name and the `Routes.SETTINGS` navigation target
stay unchanged — only the visible label, since tapping this now starts a
fresh QR scan rather than opening manual settings fields.

- [ ] **Step 4: Delete the old screen and ViewModel**

```bash
cd android && git rm app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsScreen.kt app/src/main/java/com/sodre90/cmuxremote/ui/settings/SettingsViewModel.kt
```

- [ ] **Step 5: Simplify BridgeConfig to 2 params**

In `android/app/src/main/java/com/sodre90/cmuxremote/data/Mtls.kt`,
replace:

```kotlin
/**
 * Everything needed to reach the bridge: its base URL, the per-device
 * bearer token minted by pairing, and (still declared here for
 * [AppContainer]'s pre-Task-16 cache-key logic to keep compiling in the
 * interim -- see Task 18, which removes these two fields once nothing
 * reads them anymore) the now-unused client-cert/CA fields from the old
 * manual-setup flow.
 */
class BridgeConfig(
    val baseUrl: String,
    val deviceToken: String,
    val clientP12: ByteArray? = null,
    val p12Password: String = "",
    val serverCaPem: String? = null,
)
```

with:

```kotlin
/**
 * Everything needed to reach the bridge: its base URL and the per-device
 * bearer token minted by pairing.
 */
class BridgeConfig(
    val baseUrl: String,
    val deviceToken: String,
)
```

- [ ] **Step 6: Remove Settings.kt's dead manual-setup fields**

In `android/app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt`,
remove the `serverCaPem`, `p12Password`, `hasClientCert`, `setClientP12`,
and `clientP12()` members, and simplify `bridgeConfig()`:

```kotlin
    /** Assembles a [BridgeConfig], or null if base URL or token is not yet set. */
    fun bridgeConfig(): BridgeConfig? {
        val url = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val token = deviceToken?.takeIf { it.isNotBlank() } ?: return null
        return BridgeConfig(baseUrl = url, deviceToken = token)
    }
```

In the `companion object`, remove `KEY_CA_PEM` and `KEY_P12_PASSWORD` (now
unused) but **keep `KEY_P12`** — Task 12's legacy-wipe `init` block still
checks `prefs.contains(KEY_P12)` to detect an upgrading install, even
though nothing ever writes that key again.

- [ ] **Step 7: Run the full build and test suite**

Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, every test passing. This is the first point
where the whole module (all 18 prior tasks' changes) compiles and links
together end to end — the scaffolding Tasks 12 and 13 deliberately left in
place is now fully retired.

- [ ] **Step 8: Commit**

```bash
cd android && git add ui/CmuxNavHost.kt ui/sessions/SessionsScreen.kt data/Mtls.kt data/Settings.kt
git commit -m "android: wire QR pairing into onboarding, remove all dead manual-setup code"
```

---

### Task 19: `bridge/internal/e2e/store.go` — sliding-window replay gate (Go amendment)

**Files:**
- Modify: `bridge/internal/e2e/store.go`
- Modify: `bridge/internal/e2e/store_test.go`

**Interfaces:**
- Produces: `ValidateRecvCounter`/`CommitRecvCounter` keep their existing
  public signatures (`func (s *Store) ValidateRecvCounter(deviceID string, n uint64) (bool, error)`,
  `func (s *Store) CommitRecvCounter(deviceID string, n uint64) error`) —
  no caller outside this package needs to change (`internal/server/
  encryption.go`, `events.go`, `terminal.go` all call these two methods
  and are unaffected by the internal algorithm swap).

This is the Go-side half of the "window on both sides" decision (design
spec's "Replay gate" section) — the algorithm must exactly mirror Android's
`ReplayWindow` (Task 5): `W = 64`, one `uint64` bitmask, bit `i` set means
counter `highest - i` was already accepted. This is the **only** already-
shipped Go file this plan touches; every other Go file from the prior
implementation cycle (commits up through `225293a`) is untouched.

- [ ] **Step 1: Update store_test.go with new/changed tests (RED)**

The existing `TestValidateAndCommitRecvCounter` (accept 0 → commit → reject
replayed 0 → accept 1) already tests behavior identical under both the old
strict-monotonic algorithm and the new sliding-window one — leave it
unchanged. Add two new tests to `bridge/internal/e2e/store_test.go`, after
`TestValidateAndCommitRecvCounter`:

```go
func TestOutOfOrderWithinWindowIsAccepted(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	if err := s.CommitRecvCounter("dev1", 10); err != nil {
		t.Fatalf("CommitRecvCounter(10): %v", err)
	}

	// 7 arrives late (e.g. a slower HTTP response overtaken by a faster WS
	// frame) but was never seen and is within the last 64 counters -- must
	// be accepted, not rejected as "old."
	valid, err := s.ValidateRecvCounter("dev1", 7)
	if err != nil || !valid {
		t.Fatalf("expected counter 7 valid (out-of-order, within window), got valid=%v err=%v", valid, err)
	}
	if err := s.CommitRecvCounter("dev1", 7); err != nil {
		t.Fatalf("CommitRecvCounter(7): %v", err)
	}

	valid, err = s.ValidateRecvCounter("dev1", 7)
	if err != nil {
		t.Fatalf("ValidateRecvCounter replay check: %v", err)
	}
	if valid {
		t.Fatal("expected counter 7 to now be rejected as a replay")
	}

	valid, err = s.ValidateRecvCounter("dev1", 10)
	if err != nil {
		t.Fatalf("ValidateRecvCounter: %v", err)
	}
	if valid {
		t.Fatal("expected counter 10 to still be rejected as a replay (it was committed first)")
	}
}

func TestTooOldOutsideWindowIsRejected(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	if err := s.CommitRecvCounter("dev1", 1000); err != nil {
		t.Fatalf("CommitRecvCounter(1000): %v", err)
	}

	valid, err := s.ValidateRecvCounter("dev1", 1000-64) // exactly at the boundary: too old
	if err != nil {
		t.Fatalf("ValidateRecvCounter: %v", err)
	}
	if valid {
		t.Fatal("expected counter 1000-64 to be rejected as outside the window")
	}

	valid, err = s.ValidateRecvCounter("dev1", 1000-63) // one inside the boundary: still fine
	if err != nil || !valid {
		t.Fatalf("expected counter 1000-63 valid (just inside window), got valid=%v err=%v", valid, err)
	}
}
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `cd bridge && go test ./internal/e2e/... -run 'TestOutOfOrderWithinWindowIsAccepted|TestTooOldOutsideWindowIsRejected' -v`
Expected: FAIL — `TestOutOfOrderWithinWindowIsAccepted` fails because the
current strict-monotonic `ValidateRecvCounter` rejects counter 7 after 10
has been committed (7 <= 10).

- [ ] **Step 3: Implement the sliding-window algorithm**

In `bridge/internal/e2e/store.go`, replace the `deviceSession` struct:

```go
type deviceSession struct {
	DevicePubKey   string `json:"device_pubkey"`
	SharedSecret   string `json:"shared_secret"`
	SendCounter    uint64 `json:"send_counter"`
	RecvCounter    uint64 `json:"recv_counter"`
	RecvCounterSet bool   `json:"recv_counter_set"`
}
```

with:

```go
type deviceSession struct {
	DevicePubKey   string `json:"device_pubkey"`
	SharedSecret   string `json:"shared_secret"`
	SendCounter    uint64 `json:"send_counter"`
	RecvHighest    uint64 `json:"recv_highest"`
	RecvHighestSet bool   `json:"recv_highest_set"`
	RecvWindowBits uint64 `json:"recv_window_bits"`
}
```

Add these two functions above `ValidateRecvCounter` (pure logic, no store
access, so they're trivially testable in isolation if ever needed later):

```go
const replayWindowSize = 64

// canAcceptRecvCounter reports whether n is new (never committed) and
// within the last replayWindowSize counters of the current high-water
// mark. Mirrors the Android Session.ReplayWindow algorithm exactly (see
// android/app/src/main/java/com/sodre90/cmuxremote/data/e2e/ReplayWindow.kt)
// -- both sides must tolerate the same degree of cross-channel reordering,
// since a phone's HTTP responses, /terminal WS, and /events WS frames all
// draw from one agent-side send counter with no cross-channel ordering
// guarantee.
func canAcceptRecvCounter(highest uint64, highestSet bool, windowBits uint64, n uint64) bool {
	if !highestSet || n > highest {
		return true
	}
	age := highest - n
	if age >= replayWindowSize {
		return false
	}
	return windowBits&(1<<age) == 0
}

// commitRecvCounter records n as seen, sliding the window forward if n is a
// new high-water mark.
func commitRecvCounter(highest uint64, highestSet bool, windowBits uint64, n uint64) (newHighest, newWindowBits uint64) {
	if !highestSet {
		return n, 1
	}
	if n > highest {
		shift := n - highest
		if shift >= replayWindowSize {
			return n, 1
		}
		return n, (windowBits << shift) | 1
	}
	age := highest - n
	if age >= replayWindowSize {
		return highest, windowBits
	}
	return highest, windowBits | (1 << age)
}
```

Replace `ValidateRecvCounter` and `CommitRecvCounter`:

```go
func (s *Store) ValidateRecvCounter(deviceID string, n uint64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return false, err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return false, fmt.Errorf("unknown device %q", deviceID)
	}
	return canAcceptRecvCounter(d.RecvHighest, d.RecvHighestSet, d.RecvWindowBits, n), nil
}

func (s *Store) CommitRecvCounter(deviceID string, n uint64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return fmt.Errorf("unknown device %q", deviceID)
	}
	d.RecvHighest, d.RecvWindowBits = commitRecvCounter(d.RecvHighest, d.RecvHighestSet, d.RecvWindowBits, n)
	d.RecvHighestSet = true
	f.Devices[deviceID] = d
	return s.save(f)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/e2e/... -v`
Expected: PASS — every test in the package, including the two new ones and
the unchanged `TestValidateAndCommitRecvCounter`.

- [ ] **Step 5: Run the full test suite as a regression check**

Run: `cd bridge && go build ./... && go vet ./... && go test ./...`
Expected: `BUILD_OK`, `VET_OK`, every package `ok`. No other package's tests
should be affected — `ValidateRecvCounter`/`CommitRecvCounter`'s external
signatures are unchanged, only their internal algorithm.

- [ ] **Step 6: Commit**

```bash
cd bridge && git add internal/e2e/store.go internal/e2e/store_test.go
git commit -m "e2e: replace strict-monotonic replay counter with sliding window"
```
