package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** DV2.3: the setting that lets HDR Matroska tracks be signalled as Dolby Vision is off until set. */
class ConvertHdrToDolbyVisionSettingTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    @Test
    fun `off by default, stored per profile`() = runBlocking {
        val key = booleanPreferencesKey("convert_hdr_to_dolby_vision")
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(emptyPreferences()), MemoryStore(emptyPreferences()))
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            assertFalse(PlayerSettings().convertHdrToDolbyVision)
            assertFalse(settings.playerSettings.first().convertHdrToDolbyVision)
            settings.setConvertHdrToDolbyVision(true)
            assertEquals(true, stores[0].data.value[key])
            assertTrue(settings.playerSettings.first().convertHdrToDolbyVision)
            active.value = 1
            assertFalse(settings.playerSettings.first().convertHdrToDolbyVision)
            settings.setConvertHdrToDolbyVision(false, profileId = 0)
            active.value = 0
            assertFalse(settings.playerSettings.first().convertHdrToDolbyVision)
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }
}
