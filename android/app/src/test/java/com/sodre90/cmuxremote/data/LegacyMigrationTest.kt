package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.e2e.LegacySessionRecord
import com.sodre90.cmuxremote.data.e2e.absorbLegacyIfTargetInternal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the one-shot legacy-pairing migration ([Settings.migrateLegacyIfNeeded]
 * + [com.sodre90.cmuxremote.data.e2e.CryptoSession.absorbLegacyIfTarget]) against
 * plain in-memory maps instead of real EncryptedSharedPreferences -- see
 * [migrateLegacyIfNeededInternal]/[absorbLegacyIfTargetInternal]'s doc
 * comments for why this seam exists. This is the safety-critical path: a bug
 * here silently strands an upgrading user with no e2e session, so these
 * tests focus on exactly the two properties that matter -- migration
 * happens (at most) once, and only the matching slot's session absorbs it.
 */
class SettingsLegacyMigrationTest {

    @Test
    fun noMigrationWhenNoLegacyBaseUrlIsStored() {
        val applied = mutableListOf<Triple<ConnectionSlot, String, String>>()
        val slot = migrateLegacyIfNeededInternal(
            readLegacyBaseUrl = { null },
            readLegacyToken = { "tok" },
            applyMigration = { s, u, t -> applied.add(Triple(s, u, t)) },
        )
        assertNull(slot)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun noMigrationWhenLegacyTokenIsBlank() {
        val applied = mutableListOf<Triple<ConnectionSlot, String, String>>()
        val slot = migrateLegacyIfNeededInternal(
            readLegacyBaseUrl = { "https://relay.example.com" },
            readLegacyToken = { "" },
            applyMigration = { s, u, t -> applied.add(Triple(s, u, t)) },
        )
        assertNull(slot)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun relayShapedUrlMigratesIntoTheRelaySlot() {
        var applied: Triple<ConnectionSlot, String, String>? = null
        val slot = migrateLegacyIfNeededInternal(
            readLegacyBaseUrl = { "https://relay.example.com" },
            readLegacyToken = { "tok-legacy" },
            applyMigration = { s, u, t -> applied = Triple(s, u, t) },
        )
        assertEquals(ConnectionSlot.RELAY, slot)
        assertEquals(Triple(ConnectionSlot.RELAY, "https://relay.example.com", "tok-legacy"), applied)
    }

    @Test
    fun tailscaleShapedUrlMigratesIntoTheDirectSlot() {
        var applied: Triple<ConnectionSlot, String, String>? = null
        val slot = migrateLegacyIfNeededInternal(
            readLegacyBaseUrl = { "https://phone.tailnet-name.ts.net" },
            readLegacyToken = { "tok-legacy" },
            applyMigration = { s, u, t -> applied = Triple(s, u, t) },
        )
        assertEquals(ConnectionSlot.DIRECT, slot)
        assertEquals(Triple(ConnectionSlot.DIRECT, "https://phone.tailnet-name.ts.net", "tok-legacy"), applied)
    }

    /** The safety-critical property: once a prior run has cleared the legacy
     *  keys, a later call (e.g. the next app launch) must not migrate again. */
    @Test
    fun migrationIsSelfTerminatingAndDoesNotFireTwice() {
        val store = mutableMapOf("base_url" to "https://relay.example.com", "device_token" to "tok-legacy")
        var applyCount = 0
        fun migrateOnce() = migrateLegacyIfNeededInternal(
            readLegacyBaseUrl = { store["base_url"] },
            readLegacyToken = { store["device_token"] },
            applyMigration = { _, _, _ ->
                applyCount++
                store.remove("base_url")
                store.remove("device_token")
            },
        )

        val first = migrateOnce()
        val second = migrateOnce() // simulates the next launch

        assertEquals(ConnectionSlot.RELAY, first)
        assertNull(second)
        assertEquals(1, applyCount)
    }
}

class CryptoSessionLegacyMigrationTest {

    private fun record(sendCounter: Long = 3L) = LegacySessionRecord(
        peerPublicKeyB64 = "cGVlcg==",
        sharedSecretB64 = "c2VjcmV0",
        sendCounter = sendCounter,
        recvHighest = 5L,
        recvWindowBits = 0b101L,
    )

    @Test
    fun nonTargetSlotNeverAbsorbsEvenWhenALegacyRecordExists() {
        var applyCalled = false
        val result = absorbLegacyIfTargetInternal(
            isMigrationTarget = false,
            hasLegacyRecord = { true },
            readLegacyRecord = { record() },
            applyMigration = { applyCalled = true },
        )
        assertNull(result)
        assertFalse(applyCalled)
    }

    @Test
    fun targetSlotIsANoOpWhenThereIsNoLegacyRecord() {
        var applyCalled = false
        val result = absorbLegacyIfTargetInternal(
            isMigrationTarget = true,
            hasLegacyRecord = { false },
            readLegacyRecord = { error("must not be read when hasLegacyRecord is false") },
            applyMigration = { applyCalled = true },
        )
        assertNull(result)
        assertFalse(applyCalled)
    }

    @Test
    fun targetSlotAbsorbsTheLegacyRecordExactlyOnce() {
        val applied = mutableListOf<LegacySessionRecord>()
        val want = record(sendCounter = 42L)
        val result = absorbLegacyIfTargetInternal(
            isMigrationTarget = true,
            hasLegacyRecord = { true },
            readLegacyRecord = { want },
            applyMigration = { applied.add(it) },
        )
        assertEquals(want, result)
        assertEquals(listOf(want), applied)
    }

    /** Mirrors the real CryptoSession.absorbLegacyIfTarget contract: applyMigration
     *  is expected to clear the legacy record it just moved, so a second
     *  call against the same (now-cleared) backing store is a no-op. */
    @Test
    fun absorptionIsSelfTerminatingAndDoesNotFireTwice() {
        val store = mutableMapOf("shared_secret_b64" to "c2VjcmV0")
        var applyCount = 0
        fun absorbOnce() = absorbLegacyIfTargetInternal(
            isMigrationTarget = true,
            hasLegacyRecord = { store.containsKey("shared_secret_b64") },
            readLegacyRecord = { record() },
            applyMigration = { _ ->
                applyCount++
                store.clear()
            },
        )

        val first = absorbOnce()
        val second = absorbOnce() // simulates the next launch

        assertEquals(record(), first)
        assertNull(second)
        assertEquals(1, applyCount)
    }
}

/**
 * End-to-end (still real-object-free) proof of the coordination
 * AppContainer.init performs: Settings decides which slot the legacy record
 * belongs to, then only that slot's CryptoSession may absorb it -- the other
 * slot's CryptoSession must stay untouched even though both are asked in the same
 * pass (see AppContainer.kt's `sessions.forEach { (slot, session) -> ... }`).
 */
class LegacyMigrationCoordinationTest {

    @Test
    fun onlyTheMigratedSlotsSessionAbsorbsTheLegacyE2eRecord() {
        val settingsStore = mutableMapOf("base_url" to "https://phone.tailnet-name.ts.net", "device_token" to "tok")
        val migratedSlot = migrateLegacyIfNeededInternal(
            readLegacyBaseUrl = { settingsStore["base_url"] },
            readLegacyToken = { settingsStore["device_token"] },
            applyMigration = { slot, url, token ->
                settingsStore["${slot.name.lowercase()}_base_url"] = url
                settingsStore["${slot.name.lowercase()}_device_token"] = token
                settingsStore.remove("base_url")
                settingsStore.remove("device_token")
            },
        )
        assertEquals(ConnectionSlot.DIRECT, migratedSlot)

        val legacyE2eStore = mutableMapOf("shared_secret_b64" to "c2VjcmV0")
        val legacyRecord = LegacySessionRecord(
            peerPublicKeyB64 = "cGVlcg==",
            sharedSecretB64 = "c2VjcmV0",
            sendCounter = 7L,
            recvHighest = -1L,
            recvWindowBits = 0L,
        )
        var relayAbsorbed = false
        var directAbsorbed = false

        // AppContainer.init's forEach, replayed for both slots against the same decision.
        val relayResult = absorbLegacyIfTargetInternal(
            isMigrationTarget = ConnectionSlot.RELAY == migratedSlot,
            hasLegacyRecord = { legacyE2eStore.containsKey("shared_secret_b64") },
            readLegacyRecord = { legacyRecord },
            applyMigration = { relayAbsorbed = true },
        )
        val directResult = absorbLegacyIfTargetInternal(
            isMigrationTarget = ConnectionSlot.DIRECT == migratedSlot,
            hasLegacyRecord = { legacyE2eStore.containsKey("shared_secret_b64") },
            readLegacyRecord = { legacyRecord },
            applyMigration = {
                directAbsorbed = true
                legacyE2eStore.clear()
            },
        )

        assertNull(relayResult)
        assertFalse(relayAbsorbed)
        assertEquals(legacyRecord, directResult)
        assertTrue(directAbsorbed)
    }
}
