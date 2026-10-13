package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class TunnelStallExpiryTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(data.value).also { data.value = it } }
    }

    private val classesKey = stringSetPreferencesKey("tunnel_dead_audio_classes")
    private val signatureKey = stringPreferencesKey("tunnel_dead_audio_signature")
    private val savedAtKey = longPreferencesKey("tunnel_dead_audio_saved_at_ms")
    private val versionKey = intPreferencesKey("tunnel_dead_audio_app_version")
    private val start = 1_790_000_000_000L

    private fun withSettings(block: suspend (PlayerSettingsDataStore, MemoryStore, MutableStateFlow<Int>) -> Unit): Unit = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(), MemoryStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            block(settings, stores[0], active)
        } finally {
            val scope = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scope.get(settings) as CoroutineScope).cancel()
        }
    }

    private fun assertNoStallKeys(prefs: Preferences) {
        assertNull(prefs[classesKey])
        assertNull(prefs[signatureKey])
        assertNull(prefs[savedAtKey])
        assertNull(prefs[versionKey])
    }

    @Test fun `a saved list lasts exactly 14 days on the same app version`() {
        assertFalse(tunnelStallsExpired(start, 1462, start, 1462))
        assertFalse(tunnelStallsExpired(start, 1462, start + TUNNEL_STALL_MAX_AGE_MS, 1462))
        assertTrue(tunnelStallsExpired(start, 1462, start + TUNNEL_STALL_MAX_AGE_MS + 1, 1462))
        assertTrue(tunnelStallsExpired(start, 1462, start + 1, 1463))
        assertTrue(tunnelStallsExpired(null, null, start, 1462))
        assertTrue(tunnelStallsExpired(null, 1462, start, 1462))
        assertTrue(tunnelStallsExpired(start, null, start, 1462))
    }

    @Test fun `a fresh list is kept and read back`() = withSettings { settings, store, _ ->
        var now = start
        settings.tunnelStallClock = { now }
        settings.tunnelStallAppVersion = { 1462 }
        settings.recordTunnelDeadAudioClass("audio/eac3", "chain-a")
        now += TUNNEL_STALL_MAX_AGE_MS
        assertTrue(settings.expireTunnelDeadAudioClasses().isEmpty())
        val read = settings.playerSettings.first()
        assertEquals(setOf("audio/eac3"), read.tunnelDeadAudioClasses)
        assertEquals("chain-a", read.tunnelDeadAudioSignature)
        assertEquals(start, store.data.value[savedAtKey])
        assertEquals(1462, store.data.value[versionKey])
    }

    @Test fun `a list older than 14 days reads as empty and its keys are removed`() = withSettings { settings, store, _ ->
        var now = start
        settings.tunnelStallClock = { now }
        settings.tunnelStallAppVersion = { 1462 }
        settings.recordTunnelDeadAudioClass("audio/eac3", "chain-a")
        settings.recordTunnelDeadAudioClass("pcm", "chain-a")
        now += TUNNEL_STALL_MAX_AGE_MS + 1
        val read = settings.playerSettings.first()
        assertTrue(read.tunnelDeadAudioClasses.isEmpty())
        assertNull(read.tunnelDeadAudioSignature)
        assertEquals(setOf("audio/eac3", "pcm"), settings.expireTunnelDeadAudioClasses())
        assertNoStallKeys(store.data.value)
        assertTrue(settings.expireTunnelDeadAudioClasses().isEmpty())
    }

    @Test fun `an app update ends the saved list`() = withSettings { settings, store, _ ->
        var version = 1462
        settings.tunnelStallClock = { start }
        settings.tunnelStallAppVersion = { version }
        settings.recordTunnelDeadAudioClass("audio/ac3", "chain-a")
        version = 1463
        assertTrue(settings.playerSettings.first().tunnelDeadAudioClasses.isEmpty())
        assertEquals(setOf("audio/ac3"), settings.expireTunnelDeadAudioClasses())
        assertNoStallKeys(store.data.value)
    }

    @Test fun `a list saved before expiry existed counts as run out`() = withSettings { settings, store, _ ->
        settings.tunnelStallClock = { start }
        settings.tunnelStallAppVersion = { 1462 }
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[classesKey] = setOf("audio/eac3")
                this[signatureKey] = "chain-a"
            }
        }
        assertTrue(settings.playerSettings.first().tunnelDeadAudioClasses.isEmpty())
        assertEquals(setOf("audio/eac3"), settings.expireTunnelDeadAudioClasses())
        assertNoStallKeys(store.data.value)
    }

    @Test fun `a new stall after expiry starts a new list`() = withSettings { settings, store, _ ->
        var now = start
        settings.tunnelStallClock = { now }
        settings.tunnelStallAppVersion = { 1462 }
        settings.recordTunnelDeadAudioClass("audio/eac3", "chain-a")
        now += TUNNEL_STALL_MAX_AGE_MS + 1
        settings.recordTunnelDeadAudioClass("pcm", "chain-a")
        assertEquals(setOf("pcm"), store.data.value[classesKey])
        assertEquals(now, store.data.value[savedAtKey])
        assertEquals(setOf("pcm"), settings.playerSettings.first().tunnelDeadAudioClasses)
    }

    @Test fun `entries keyed by decoder sit next to old bare entries and expire with them`() = withSettings { settings, store, _ ->
        var now = start
        settings.tunnelStallClock = { now }
        settings.tunnelStallAppVersion = { 1462 }
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[classesKey] = setOf("audio/eac3")
                this[signatureKey] = "chain-a"
                this[savedAtKey] = start
                this[versionKey] = 1462
            }
        }
        settings.recordTunnelDeadAudioClass("pcm@OMX.vendor.avc.tunneled", "chain-a")
        assertEquals(
            setOf("audio/eac3", "pcm@OMX.vendor.avc.tunneled"),
            settings.playerSettings.first().tunnelDeadAudioClasses
        )
        now += TUNNEL_STALL_MAX_AGE_MS + 1
        assertEquals(setOf("audio/eac3", "pcm@OMX.vendor.avc.tunneled"), settings.expireTunnelDeadAudioClasses())
        assertNoStallKeys(store.data.value)
    }

    @Test fun `forget and the tunneling switch still clear everything`() = withSettings { settings, store, active ->
        settings.tunnelStallClock = { start }
        settings.tunnelStallAppVersion = { 1462 }
        settings.recordTunnelDeadAudioClass("audio/eac3", "chain-a")
        active.value = 1
        settings.recordTunnelDeadAudioClass("audio/ac3", "chain-b")
        active.value = 0
        settings.clearTunnelDeadAudioClasses()
        assertNoStallKeys(store.data.value)
        assertTrue(settings.playerSettings.first().tunnelDeadAudioClasses.isEmpty())
        active.value = 1
        assertEquals(setOf("audio/ac3"), settings.playerSettings.first().tunnelDeadAudioClasses)
        active.value = 0

        settings.setTunnelingEnabled(true)
        settings.recordTunnelDeadAudioClass("pcm", "chain-a")
        settings.setTunnelingEnabled(false)
        assertNoStallKeys(store.data.value)
    }
}
