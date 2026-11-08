package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.player.DvManifestFolder
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ROADMAP DV4.8: the manifest folder is a saved per-profile setting, off until set, with the add-on folder as default. */
class DvManifestSettingTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private val enabledKey = booleanPreferencesKey("dv_manifest_enabled")
    private val urlKey = stringPreferencesKey("dv_manifest_url")

    private fun withSettings(block: suspend (PlayerSettingsDataStore, List<MemoryStore>, MutableStateFlow<Int>) -> Unit) = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(emptyPreferences()), MemoryStore(emptyPreferences()))
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            block(settings, stores, active)
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }

    @Test
    fun `off with the add-on folder by default`() = withSettings { settings, _, _ ->
        assertEquals("https://subs.m4ntic.tech/dv/", DvManifestFolder.DEFAULT_URL)
        assertFalse(PlayerSettings().dvManifestEnabled)
        assertEquals(DvManifestFolder.DEFAULT_URL, PlayerSettings().dvManifestUrl)
        val read = settings.playerSettings.first()
        assertFalse(read.dvManifestEnabled)
        assertEquals(DvManifestFolder.DEFAULT_URL, read.dvManifestUrl)
    }

    @Test
    fun `the toggle and the folder round-trip, stored per profile`() = withSettings { settings, stores, active ->
        settings.setDvManifestEnabled(true)
        assertTrue(settings.setDvManifestUrl("  https://subs.example/dv/ "))
        assertEquals(true, stores[0].data.value[enabledKey])
        assertEquals("https://subs.example/dv/", stores[0].data.value[urlKey])
        settings.playerSettings.first().let {
            assertTrue(it.dvManifestEnabled)
            assertEquals("https://subs.example/dv/", it.dvManifestUrl)
        }
        active.value = 1
        settings.playerSettings.first().let {
            assertFalse(it.dvManifestEnabled)
            assertEquals(DvManifestFolder.DEFAULT_URL, it.dvManifestUrl)
        }
        assertNull(stores[1].data.value[urlKey])
    }

    @Test
    fun `an invalid folder is refused and the saved one stays`() = withSettings { settings, stores, _ ->
        assertTrue(settings.setDvManifestUrl("https://subs.example/dv/"))
        assertFalse(settings.setDvManifestUrl("ftp://subs.example/dv"))
        assertFalse(settings.setDvManifestUrl("https://subs.example/dv?key=1"))
        assertEquals("https://subs.example/dv/", stores[0].data.value[urlKey])
    }

    @Test
    fun `a blank folder restores the default, and turning the toggle off keeps it`() = withSettings { settings, stores, _ ->
        settings.setDvManifestEnabled(true)
        assertTrue(settings.setDvManifestUrl("https://subs.example/dv/"))
        settings.setDvManifestEnabled(false)
        assertEquals("https://subs.example/dv/", settings.playerSettings.first().dvManifestUrl)
        assertTrue(settings.setDvManifestUrl("   "))
        assertNull(stores[0].data.value[urlKey])
        assertEquals(DvManifestFolder.DEFAULT_URL, settings.playerSettings.first().dvManifestUrl)
    }
}
