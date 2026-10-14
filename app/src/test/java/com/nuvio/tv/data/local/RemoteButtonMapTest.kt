package com.nuvio.tv.data.local

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteButtonMapTest {
    private val default = RemoteButtonMap.DEFAULT

    @Test
    fun `starting setup gives the spare keys their jobs`() {
        assertEquals(RemoteButtonJob.STATS, default.overrideFor(KeyEvent.KEYCODE_MENU)?.job)
        assertEquals(RemoteButtonJob.SKIP_INTRO_OR_NEXT, default.overrideFor(KeyEvent.KEYCODE_MEDIA_NEXT)?.job)
        assertEquals(RemoteButtonJob.RESTART, default.overrideFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS)?.job)
        assertEquals(RemoteButtonJob.SOURCES, default.overrideFor(KeyEvent.KEYCODE_PROG_RED)?.job)
        assertEquals(RemoteButtonJob.NEXT_AUDIO, default.overrideFor(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)?.job)
        assertEquals(RemoteButtonJob.EPISODES, default.overrideFor(KeyEvent.KEYCODE_GUIDE)?.job)
        val channelUp = default.overrideFor(KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals(RemoteButtonJob.SKIP_FORWARD, channelUp?.job)
        assertEquals(300_000L, channelUp?.skipMs)
    }

    @Test
    fun `keys the player already handles are left alone until changed`() {
        assertNull(default.overrideFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertNull(default.overrideFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertNull(default.overrideFor(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertNull(default.overrideFor(KeyEvent.KEYCODE_MEDIA_STOP))
        assertNull(default.overrideFor(KeyEvent.KEYCODE_CAPTIONS))
        val changed = default.with(
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            RemoteButtonBinding(RemoteButtonJob.SKIP_FORWARD, 30_000L)
        )
        assertEquals(RemoteButtonBinding(RemoteButtonJob.SKIP_FORWARD, 30_000L), changed.overrideFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    @Test
    fun `locked and volume keys are never taken`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE
        ).forEach { assertEquals(RemoteKeyKind.LOCKED, RemoteButtonMap.kindOf(it)) }
        assertEquals(RemoteKeyKind.VOLUME, RemoteButtonMap.kindOf(KeyEvent.KEYCODE_VOLUME_UP))
        val sneaky = RemoteButtonMap.decode("digits=1,${KeyEvent.KEYCODE_BACK}=stats,${KeyEvent.KEYCODE_VOLUME_UP}=sources")
        assertNull(sneaky.overrideFor(KeyEvent.KEYCODE_BACK))
        assertNull(sneaky.overrideFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(default, sneaky)
    }

    @Test
    fun `gamepad A and B and the space bar stay with navigation`() {
        listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_SPACE).forEach {
            assertEquals(RemoteKeyKind.LOCKED, RemoteButtonMap.kindOf(it))
            assertNull(RemoteButtonMap.decode("$it=stats").overrideFor(it))
        }
    }

    @Test
    fun `game controller shoulders seek, X plays and pauses, Y opens subtitles`() {
        assertEquals(RemoteButtonJob.SEEK_BACK, default.overrideFor(KeyEvent.KEYCODE_BUTTON_L1)?.job)
        assertEquals(RemoteButtonJob.SEEK_FORWARD, default.overrideFor(KeyEvent.KEYCODE_BUTTON_R1)?.job)
        assertEquals(RemoteButtonJob.PLAY_PAUSE, default.overrideFor(KeyEvent.KEYCODE_BUTTON_X)?.job)
        assertEquals(RemoteButtonJob.SUBTITLES, default.overrideFor(KeyEvent.KEYCODE_BUTTON_Y)?.job)
        listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)
            .forEach {
                assertEquals(RemoteKeyKind.ASSIGNABLE, RemoteButtonMap.kindOf(it))
                assertTrue("shown on the settings page", it in default.rowKeys)
                assertFalse(default.isCustomKey(it))
            }
    }

    @Test
    fun `game controller buttons can be rebound and set back`() {
        val changed = default.with(KeyEvent.KEYCODE_BUTTON_Y, RemoteButtonBinding(RemoteButtonJob.AUDIO))
        assertEquals(RemoteButtonJob.AUDIO, changed.overrideFor(KeyEvent.KEYCODE_BUTTON_Y)?.job)
        assertEquals(changed, RemoteButtonMap.decode(changed.encode()))
        assertEquals(default, changed.with(KeyEvent.KEYCODE_BUTTON_Y, RemoteButtonBinding(RemoteButtonJob.SUBTITLES)))
        val off = default.with(KeyEvent.KEYCODE_BUTTON_L1, RemoteButtonBinding.NOTHING)
        assertEquals(RemoteButtonJob.NOTHING, off.overrideFor(KeyEvent.KEYCODE_BUTTON_L1)?.job)
    }

    @Test
    fun `separate play and pause keys belong to the play pause row`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteButtonMap.learnTarget(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteButtonMap.learnTarget(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(KeyEvent.KEYCODE_PROG_RED, RemoteButtonMap.learnTarget(KeyEvent.KEYCODE_PROG_RED))
        assertNull(default.overrideFor(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertNull(RemoteButtonMap.decode("${KeyEvent.KEYCODE_MEDIA_PAUSE}=sources").overrideFor(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test
    fun `number keys jump through the video and can be switched off together`() {
        assertEquals(RemoteButtonJob.JUMP_TO_PERCENT, default.overrideFor(KeyEvent.KEYCODE_5)?.job)
        assertEquals(RemoteButtonJob.JUMP_TO_PERCENT, default.overrideFor(KeyEvent.KEYCODE_NUMPAD_0)?.job)
        assertEquals(5, RemoteButtonMap.numberOf(KeyEvent.KEYCODE_5))
        assertEquals(0, RemoteButtonMap.numberOf(KeyEvent.KEYCODE_NUMPAD_0))
        val off = default.withNumberKeysJump(false)
        assertNull(off.overrideFor(KeyEvent.KEYCODE_5))
        val five = off.with(KeyEvent.KEYCODE_5, RemoteButtonBinding(RemoteButtonJob.STATS))
        assertEquals(RemoteButtonJob.STATS, five.overrideFor(KeyEvent.KEYCODE_5)?.job)
        assertNull(five.overrideFor(KeyEvent.KEYCODE_6))
        assertTrue(KeyEvent.KEYCODE_5 in five.rowKeys)
    }

    @Test
    fun `jump through the video is only offered for number keys`() {
        assertTrue(RemoteButtonJob.JUMP_TO_PERCENT in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_3))
        assertFalse(RemoteButtonJob.JUMP_TO_PERCENT in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_PROG_RED))
        val bad = RemoteButtonMap.decode("${KeyEvent.KEYCODE_PROG_RED}=jump_percent")
        assertEquals(RemoteButtonJob.SOURCES, bad.overrideFor(KeyEvent.KEYCODE_PROG_RED)?.job)
    }

    @Test
    fun `a learned key gets its own row and can be removed`() {
        val f1 = KeyEvent.KEYCODE_F1
        assertNull(default.overrideFor(f1))
        val learned = default.with(f1, RemoteButtonBinding(RemoteButtonJob.SUBTITLES_TOGGLE))
        assertEquals(RemoteButtonJob.SUBTITLES_TOGGLE, learned.overrideFor(f1)?.job)
        assertTrue(learned.isCustomKey(f1))
        assertEquals(f1, learned.rowKeys.last())
        assertEquals(default, learned.without(f1))
    }

    @Test
    fun `picking the starting job again keeps nothing extra`() {
        val changed = default.with(KeyEvent.KEYCODE_MENU, RemoteButtonBinding(RemoteButtonJob.SOURCES))
        assertEquals(RemoteButtonJob.SOURCES, changed.overrideFor(KeyEvent.KEYCODE_MENU)?.job)
        val back = changed.with(KeyEvent.KEYCODE_MENU, RemoteButtonBinding(RemoteButtonJob.STATS))
        assertEquals(default, back)
        val nothing = default.with(KeyEvent.KEYCODE_MENU, RemoteButtonBinding.NOTHING)
        assertEquals(RemoteButtonJob.NOTHING, nothing.overrideFor(KeyEvent.KEYCODE_MENU)?.job)
    }

    @Test
    fun `saved setup reads back the same`() {
        val map = default
            .withNumberKeysJump(false)
            .with(KeyEvent.KEYCODE_PROG_GREEN, RemoteButtonBinding(RemoteButtonJob.SKIP_BACK, 30_000L))
            .with(KeyEvent.KEYCODE_F2, RemoteButtonBinding(RemoteButtonJob.LEAVE))
            .with(KeyEvent.KEYCODE_7, RemoteButtonBinding(RemoteButtonJob.JUMP_TO_PERCENT))
        val read = RemoteButtonMap.decode(map.encode())
        assertEquals(map, read)
        assertFalse(read.numberKeysJump)
        assertEquals(30_000L, read.overrideFor(KeyEvent.KEYCODE_PROG_GREEN)?.skipMs)
    }

    @Test
    fun `unknown or damaged saved text falls back safely`() {
        assertEquals(default, RemoteButtonMap.decode(null))
        assertEquals(default, RemoteButtonMap.decode(""))
        val odd = RemoteButtonMap.decode("digits=1,abc=stats,183=not_a_job,184=skip_forward@12345,=,,")
        assertEquals(RemoteButtonJob.SOURCES, odd.overrideFor(KeyEvent.KEYCODE_PROG_RED)?.job)
        assertEquals(RemoteButtonMap.DEFAULT_SKIP_MS, odd.overrideFor(KeyEvent.KEYCODE_PROG_GREEN)?.skipMs)
    }

    @Test
    fun `settings page starts with the starting set and the number keys row`() {
        val rows = default.rowKeys
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, rows.first())
        assertTrue(RemoteButtonMap.NUMBER_KEYS_ROW in rows)
        assertTrue(rows.indexOf(RemoteButtonMap.NUMBER_KEYS_ROW) == rows.indexOf(KeyEvent.KEYCODE_PROG_BLUE) + 1)
        assertFalse(rows.any { RemoteButtonMap.isNumberKey(it) })
    }
}
