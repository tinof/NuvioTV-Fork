package com.nuvio.tv.core.player.thumbnail

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SeekThumbnailPreferencesTest {
    private val modeKey = stringPreferencesKey("seek_thumbnails_mode")
    private val legacyEnabledKey = booleanPreferencesKey("seek_thumbnails_enabled")
    private val legacyFourKKey = stringPreferencesKey("seek_thumbnails_4k_mode")
    private val copyKey = booleanPreferencesKey("seek_thumbnails_from_copy")
    private val prepareKey = stringPreferencesKey("seek_thumbnails_prepare")
    private val migration = SeekThumbnailPreferences.copyDefaultMigration

    private fun prefs(block: MutablePreferences.() -> Unit = {}): Preferences =
        emptyPreferences().toMutablePreferences().apply(block).toPreferences()

    private fun migrated(p: Preferences): Preferences = runBlocking {
        if (migration.shouldMigrate(p)) migration.migrate(p) else p
    }

    @Test
    fun `untouched store reads as all resolutions`() {
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(emptyPreferences()))
    }

    @Test
    fun `untouched store is on with 4 GB of RAM and off below`() {
        val mb = 1024L * 1024
        val am9 = SeekThumbnailPreferences.defaultMode(3_712 * mb)
        val shield = SeekThumbnailPreferences.defaultMode(2_900 * mb)
        val fireStick = SeekThumbnailPreferences.defaultMode(1_669 * mb)
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(emptyPreferences(), am9))
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(emptyPreferences(), shield))
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(emptyPreferences(), fireStick))
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.defaultMode(0L))
    }

    @Test
    fun `the Shield TV Pro 2019 starts on though it has 3 GB`() {
        val shieldMb = 2_900L * 1024 * 1024
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.defaultMode(shieldMb, null, "NVIDIA", "mdarcy"))
        assertEquals("the 2019 tube keeps the RAM rule",
            SeekThumbMode.OFF, SeekThumbnailPreferences.defaultMode(shieldMb, null, "NVIDIA", "sif"))
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.defaultMode(shieldMb, null, "Amlogic", "mdarcy"))
        assertEquals("switch 0 keeps the RAM rule on the Pro",
            SeekThumbMode.OFF, SeekThumbnailPreferences.defaultMode(shieldMb, false, "NVIDIA", "mdarcy"))
        assertEquals("switch 1 starts on anywhere",
            SeekThumbMode.ALL, SeekThumbnailPreferences.defaultMode(1_669L * 1024 * 1024, true, "Amazon", "sheldon"))
        assertEquals("a stored Off still wins on the Pro", SeekThumbMode.OFF,
            SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = SeekThumbMode.OFF.name },
                SeekThumbnailPreferences.defaultMode(shieldMb, null, "NVIDIA", "mdarcy")))
    }

    @Test
    fun `a stored choice is kept whatever the RAM`() {
        listOf(SeekThumbMode.ALL, SeekThumbMode.OFF).forEach { default ->
            SeekThumbMode.entries.forEach { mode ->
                assertEquals(mode, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = mode.name }, default))
            }
            assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true }, default))
            assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = false }, default))
        }
    }

    @Test
    fun `stored mode is kept`() {
        SeekThumbMode.entries.forEach { mode ->
            assertEquals(mode, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = mode.name }))
        }
    }

    @Test
    fun `unknown stored mode reads as off`() {
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = "FOURK_ONLY" }))
    }

    @Test
    fun `legacy switch on reads as all, with 4K off as up to 1080p`() {
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true }))
        assertEquals(
            SeekThumbMode.ALL,
            SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true; this[legacyFourKKey] = "AUTO" })
        )
        assertEquals(
            SeekThumbMode.HD,
            SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true; this[legacyFourKKey] = "OFF" })
        )
    }

    @Test
    fun `legacy switch off stays off`() {
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = false }))
        assertEquals(
            SeekThumbMode.OFF,
            SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = false; this[legacyFourKKey] = "OFF" })
        )
    }

    @Test
    fun `untouched store gets copies off`() {
        val out = migrated(emptyPreferences())
        assertEquals(false, out[copyKey])
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(out))
    }

    @Test
    fun `store with only other settings counts as untouched`() {
        val out = migrated(prefs { this[prepareKey] = ThumbPrepareMode.ALWAYS.name })
        assertEquals(false, out[copyKey])
        assertEquals(ThumbPrepareMode.ALWAYS.name, out[prepareKey])
    }

    @Test
    fun `chosen mode without copy key keeps copies on`() {
        listOf(SeekThumbMode.ALL, SeekThumbMode.HD, SeekThumbMode.OFF).forEach { mode ->
            val out = migrated(prefs { this[modeKey] = mode.name })
            assertEquals(true, out[copyKey])
            assertEquals(mode, SeekThumbnailPreferences.modeOf(out))
        }
    }

    @Test
    fun `legacy switch without copy key keeps copies on and the mode`() {
        val on = migrated(prefs { this[legacyEnabledKey] = true; this[legacyFourKKey] = "OFF" })
        assertEquals(true, on[copyKey])
        assertEquals(SeekThumbMode.HD, SeekThumbnailPreferences.modeOf(on))
        val off = migrated(prefs { this[legacyEnabledKey] = false })
        assertEquals(true, off[copyKey])
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(off))
    }

    @Test
    fun `stored copy choice is never changed`() = runBlocking {
        listOf(true, false).forEach { choice ->
            listOf(prefs { this[copyKey] = choice }, prefs { this[modeKey] = "ALL"; this[copyKey] = choice }).forEach { p ->
                assertFalse(migration.shouldMigrate(p))
                assertEquals(choice, migration.migrate(p)[copyKey])
            }
        }
    }

    @Test
    fun `migration runs once`() = runBlocking {
        val first = migration.migrate(emptyPreferences())
        assertFalse(migration.shouldMigrate(first))
        assertTrue(migration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun `changing the mode later leaves copies off for a new install`() {
        val out = migrated(emptyPreferences()).toMutablePreferences().apply {
            this[modeKey] = SeekThumbMode.HD.name
            remove(legacyEnabledKey)
        }.toPreferences()
        assertEquals(false, migrated(out)[copyKey])
    }

    @Test
    fun `unreadable store reads as off with no copies`() = runBlocking {
        val failing: Flow<Preferences> = flow { throw IOException("disk full") }
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeFlow(failing).first())
        assertEquals(false, SeekThumbnailPreferences.copyFlow(failing).first())
        assertEquals(ThumbPrepareMode.OFF, SeekThumbnailPreferences.prepareModeFlow(failing).first())
    }

    @Test(expected = IllegalStateException::class)
    fun `other read errors are not swallowed`() = runBlocking<Unit> {
        SeekThumbnailPreferences.modeFlow(flow<Preferences> { throw IllegalStateException() }).first()
    }

    private fun <T> withStore(file: File, block: suspend (Flow<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = listOf(migration),
                scope = scope,
                produceFile = { file }
            )
            block(store.data)
        } finally {
            scope.cancel()
        }
    }

    private fun tempStoreFile(): File =
        File(Files.createTempDirectory("seek-thumbs").toFile(), "seek_thumbnails.preferences_pb")

    @Test
    fun `new store is migrated before the first read and saved`() {
        val file = tempStoreFile()
        withStore(file) { data ->
            assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeFlow(data).first())
            assertEquals(false, SeekThumbnailPreferences.copyFlow(data).first())
        }
        assertTrue(file.length() > 0)
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun `corrupted store file starts again as a new install`() {
        val file = tempStoreFile()
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04, 0x05))
        withStore(file) { data ->
            assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeFlow(data).first())
            assertEquals(false, SeekThumbnailPreferences.copyFlow(data).first())
            assertEquals(ThumbPrepareMode.OFF, SeekThumbnailPreferences.prepareModeFlow(data).first())
        }
        file.parentFile?.deleteRecursively()
    }
}
