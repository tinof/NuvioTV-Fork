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
    fun `a saved setup from before holds reads back with its press jobs and no holds`() {
        val old = "digits=0,${KeyEvent.KEYCODE_PROG_GREEN}=skip_back@30000,${KeyEvent.KEYCODE_F2}=leave,${KeyEvent.KEYCODE_7}=jump_percent"
        val read = RemoteButtonMap.decode(old)
        assertEquals(RemoteButtonBinding(RemoteButtonJob.SKIP_BACK, 30_000L), read.overrideFor(KeyEvent.KEYCODE_PROG_GREEN))
        assertEquals(RemoteButtonJob.LEAVE, read.overrideFor(KeyEvent.KEYCODE_F2)?.job)
        assertEquals(RemoteButtonJob.JUMP_TO_PERCENT, read.overrideFor(KeyEvent.KEYCODE_7)?.job)
        assertFalse(read.numberKeysJump)
        assertTrue(read.rowKeys.none { read.holdFor(it) != null })
        assertEquals(old, read.encode())
    }

    @Test
    fun `a hold job is saved next to the press job and reads back`() {
        val map = default
            .withHold(KeyEvent.KEYCODE_MENU, RemoteButtonBinding(RemoteButtonJob.SOURCES))
            .withHold(KeyEvent.KEYCODE_PROG_RED, RemoteButtonBinding(RemoteButtonJob.SKIP_FORWARD, 60_000L))
            .with(KeyEvent.KEYCODE_F1, RemotePressKind.HOLD, RemoteButtonBinding(RemoteButtonJob.LEAVE))
        val read = RemoteButtonMap.decode(map.encode())
        assertEquals(map, read)
        assertEquals(RemoteButtonJob.STATS, read.takeOver(KeyEvent.KEYCODE_MENU)?.press?.job)
        assertEquals(RemoteButtonJob.SOURCES, read.takeOver(KeyEvent.KEYCODE_MENU)?.hold?.job)
        assertEquals(60_000L, read.holdFor(KeyEvent.KEYCODE_PROG_RED)?.skipMs)
        val f1 = read.takeOver(KeyEvent.KEYCODE_F1)
        assertNull(f1?.press)
        assertEquals(RemoteButtonJob.LEAVE, f1?.hold?.job)
        assertTrue(KeyEvent.KEYCODE_F1 in read.rowKeys)
        assertEquals(RemoteButtonJob.LEAVE, read.bindingFor(KeyEvent.KEYCODE_F1, RemotePressKind.HOLD)?.job)
    }

    @Test
    fun `a player key is taken over as soon as it has a hold job`() {
        assertNull(default.takeOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        val held = default.withHold(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteButtonBinding(RemoteButtonJob.SPEED))
        val jobs = held.takeOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals(RemoteButtonJob.PLAY_PAUSE, jobs?.press?.job)
        assertEquals(RemoteButtonJob.SPEED, jobs?.hold?.job)
        assertNull(held.takeOver(KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    @Test
    fun `a hold that does nothing is removed and removing a button drops both jobs`() {
        val held = default.withHold(KeyEvent.KEYCODE_MENU, RemoteButtonBinding(RemoteButtonJob.SOURCES))
        assertEquals(default, held.withHold(KeyEvent.KEYCODE_MENU, RemoteButtonBinding.NOTHING))
        val both = default
            .with(KeyEvent.KEYCODE_F1, RemoteButtonBinding(RemoteButtonJob.STATS))
            .withHold(KeyEvent.KEYCODE_F1, RemoteButtonBinding(RemoteButtonJob.SOURCES))
        assertEquals(default, both.without(KeyEvent.KEYCODE_F1))
        assertEquals(default, RemoteButtonMap.decode("${KeyEvent.KEYCODE_MENU}h=nothing"))
    }

    @Test
    fun `holds on locked keys and jump holds are not read back`() {
        val sneaky = RemoteButtonMap.decode("${KeyEvent.KEYCODE_BACK}h=stats,${KeyEvent.KEYCODE_DPAD_LEFT}h=sources,${KeyEvent.KEYCODE_5}h=jump_percent,${KeyEvent.KEYCODE_MEDIA_PLAY}h=speed")
        assertEquals(default, sneaky)
        assertFalse(RemoteButtonJob.JUMP_TO_PERCENT in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_5, RemotePressKind.HOLD))
        assertTrue(RemoteButtonJob.JUMP_TO_PERCENT in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_5, RemotePressKind.PRESS))
    }

    @Test
    fun `up and down take a job only while the controls are hidden`() {
        assertEquals(RemoteKeyKind.HIDDEN_CONTROLS, RemoteButtonMap.kindOf(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RemoteKeyKind.HIDDEN_CONTROLS, RemoteButtonMap.kindOf(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(RemoteKeyKind.LOCKED, RemoteButtonMap.kindOf(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RemoteKeyKind.LOCKED, RemoteButtonMap.kindOf(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(RemoteButtonMap.canLearn(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(RemoteButtonMap.canLearn(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(default.takeOver(KeyEvent.KEYCODE_DPAD_UP, controlsHidden = true))
        assertFalse(KeyEvent.KEYCODE_DPAD_UP in default.rowKeys)
        val up = default.with(KeyEvent.KEYCODE_DPAD_UP, RemoteButtonBinding(RemoteButtonJob.AUDIO))
        assertEquals(RemoteButtonJob.AUDIO, up.takeOver(KeyEvent.KEYCODE_DPAD_UP, controlsHidden = true)?.press?.job)
        assertNull(up.takeOver(KeyEvent.KEYCODE_DPAD_UP, controlsHidden = false))
        assertTrue(up.isCustomKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, up.rowKeys.last())
        val down = default.withHold(KeyEvent.KEYCODE_DPAD_DOWN, RemoteButtonBinding(RemoteButtonJob.SOURCES))
        val jobs = down.takeOver(KeyEvent.KEYCODE_DPAD_DOWN, controlsHidden = true)
        assertNull(jobs?.press)
        assertEquals(RemoteButtonJob.SOURCES, jobs?.hold?.job)
        assertNull(down.takeOver(KeyEvent.KEYCODE_DPAD_DOWN, controlsHidden = false))
        assertEquals(up, RemoteButtonMap.decode(up.encode()))
        assertEquals(down, RemoteButtonMap.decode(down.encode()))
    }

    @Test
    fun `up and down set to do nothing on a press keep opening the controls and keep the hold job`() {
        val down = KeyEvent.KEYCODE_DPAD_DOWN
        val sources = RemoteButtonBinding(RemoteButtonJob.SOURCES)
        val picked = default.withHold(down, sources).with(down, RemoteButtonBinding.NOTHING)
        assertEquals(default.withHold(down, sources), picked)
        assertNull(picked.bindingFor(down))
        val jobs = picked.takeOver(down, controlsHidden = true)
        assertNull(jobs?.press)
        assertEquals(sources, jobs?.hold)
        assertTrue(down in picked.rowKeys)

        val saved = RemoteButtonMap.decode("digits=1,$down=nothing,${down}h=sources")
        assertEquals(picked, saved)
        assertNull(saved.takeOver(down, controlsHidden = true)?.press)
        assertEquals(sources, saved.holdFor(down))
        assertNull(RemoteButtonMap.decode("digits=1,${KeyEvent.KEYCODE_DPAD_UP}=nothing").takeOver(KeyEvent.KEYCODE_DPAD_UP))

        val audio = picked.with(down, RemoteButtonBinding(RemoteButtonJob.AUDIO))
        assertEquals(RemoteButtonJob.AUDIO, audio.takeOver(down)?.press?.job)
        assertEquals(picked, audio.with(down, RemoteButtonBinding.NOTHING))
        assertEquals(default, picked.without(down))
    }

    @Test
    fun `do nothing stays a real choice on keys without a built in job`() {
        val menu = default.with(KeyEvent.KEYCODE_MENU, RemoteButtonBinding.NOTHING)
        assertEquals(RemoteButtonBinding.NOTHING, menu.overrideFor(KeyEvent.KEYCODE_MENU))
        val playPause = default.with(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteButtonBinding.NOTHING)
        assertEquals(RemoteButtonBinding.NOTHING, playPause.overrideFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        val red = RemoteButtonMap.decode("digits=1,${KeyEvent.KEYCODE_PROG_RED}=nothing")
        assertEquals(RemoteButtonBinding.NOTHING, red.overrideFor(KeyEvent.KEYCODE_PROG_RED))
        assertEquals(red, RemoteButtonMap.decode(red.encode()))
    }

    @Test
    fun `settings page starts with the starting set and the number keys row`() {
        val rows = default.rowKeys
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, rows.first())
        assertTrue(RemoteButtonMap.NUMBER_KEYS_ROW in rows)
        assertTrue(rows.indexOf(RemoteButtonMap.NUMBER_KEYS_ROW) == rows.indexOf(KeyEvent.KEYCODE_PROG_BLUE) + 1)
        assertFalse(rows.any { RemoteButtonMap.isNumberKey(it) })
    }

    @Test
    fun `subtitles up and down are offered and kept, and no key has them out of the box`() {
        for (job in listOf(RemoteButtonJob.SUBTITLES_UP, RemoteButtonJob.SUBTITLES_DOWN)) {
            assertTrue(job in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_PROG_RED))
            assertTrue(job in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_PROG_RED, RemotePressKind.HOLD))
            val read = RemoteButtonMap.decode(default.with(KeyEvent.KEYCODE_PROG_RED, RemoteButtonBinding(job)).encode())
            assertEquals(job, read.overrideFor(KeyEvent.KEYCODE_PROG_RED)?.job)
            assertTrue(RemoteButtonMap.DEFAULTS.values.none { it.job == job })
        }
    }

    @Test
    fun `show details is offered as a press and a hold job and Info keeps stream info`() {
        assertTrue(RemoteButtonJob.SHOW_DETAILS in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_INFO))
        assertTrue(RemoteButtonJob.SHOW_DETAILS in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_INFO, RemotePressKind.HOLD))
        assertTrue(RemoteButtonJob.SHOW_DETAILS in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_5))
        assertEquals(RemoteButtonJob.INFO, default.overrideFor(KeyEvent.KEYCODE_INFO)?.job)
        assertTrue(RemoteButtonMap.DEFAULTS.values.none { it.job == RemoteButtonJob.SHOW_DETAILS })
    }

    @Test
    fun `show details on Info runs instead of stream info`() {
        val map = default.with(KeyEvent.KEYCODE_INFO, RemoteButtonBinding(RemoteButtonJob.SHOW_DETAILS))
        assertEquals(RemoteButtonJob.SHOW_DETAILS, map.overrideFor(KeyEvent.KEYCODE_INFO)?.job)
        val jobs = map.takeOver(KeyEvent.KEYCODE_INFO)
        assertEquals(RemoteButtonJob.SHOW_DETAILS, jobs?.press?.job)
        assertNull(jobs?.hold)
    }

    @Test
    fun `show details as a hold keeps the press job`() {
        val map = default.withHold(KeyEvent.KEYCODE_INFO, RemoteButtonBinding(RemoteButtonJob.SHOW_DETAILS))
        val jobs = map.takeOver(KeyEvent.KEYCODE_INFO)
        assertEquals(RemoteButtonJob.INFO, jobs?.press?.job)
        assertEquals(RemoteButtonJob.SHOW_DETAILS, jobs?.hold?.job)
    }

    @Test
    fun `show details survives saving as a press and as a hold`() {
        val map = default
            .with(KeyEvent.KEYCODE_PROG_GREEN, RemoteButtonBinding(RemoteButtonJob.SHOW_DETAILS))
            .withHold(KeyEvent.KEYCODE_MENU, RemoteButtonBinding(RemoteButtonJob.SHOW_DETAILS))
        val encoded = map.encode()
        assertTrue(encoded.contains("${KeyEvent.KEYCODE_PROG_GREEN}=show_details"))
        assertTrue(encoded.contains("${KeyEvent.KEYCODE_MENU}h=show_details"))
        val decoded = RemoteButtonMap.decode(encoded)
        assertEquals(map, decoded)
        assertEquals(RemoteButtonJob.SHOW_DETAILS, decoded.overrideFor(KeyEvent.KEYCODE_PROG_GREEN)?.job)
        assertEquals(RemoteButtonJob.SHOW_DETAILS, decoded.holdFor(KeyEvent.KEYCODE_MENU)?.job)
        assertEquals(RemoteButtonJob.SHOW_DETAILS, RemoteButtonJob.fromId("show_details"))
    }

    @Test
    fun `show details deals with the pause overlay itself`() {
        assertTrue(RemoteButtonJob.SHOW_DETAILS.keepsPauseOverlay)
        assertTrue(RemoteButtonJob.PLAY_PAUSE.keepsPauseOverlay)
        assertTrue(RemoteButtonJob.NOTHING.keepsPauseOverlay)
        assertTrue(RemoteButtonJob.LEAVE.keepsPauseOverlay)
        assertFalse(RemoteButtonJob.INFO.keepsPauseOverlay)
        assertFalse(RemoteButtonJob.SOURCES.keepsPauseOverlay)
    }

    @Test
    fun `chapters list is offered next to the chapter jobs and survives saving`() {
        val jobs = RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_PROG_BLUE)
        assertEquals(jobs.indexOf(RemoteButtonJob.NEXT_CHAPTER) - 1, jobs.indexOf(RemoteButtonJob.CHAPTERS_LIST))
        assertTrue(RemoteButtonJob.CHAPTERS_LIST in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_MENU, RemotePressKind.HOLD))
        assertTrue(RemoteButtonJob.CHAPTERS_LIST in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_DPAD_DOWN))
        val map = RemoteButtonMap.DEFAULT
            .with(KeyEvent.KEYCODE_PROG_BLUE, RemoteButtonBinding(RemoteButtonJob.CHAPTERS_LIST))
            .withHold(KeyEvent.KEYCODE_MENU, RemoteButtonBinding(RemoteButtonJob.CHAPTERS_LIST))
        val decoded = RemoteButtonMap.decode(map.encode())
        assertEquals(map, decoded)
        assertEquals(RemoteButtonJob.CHAPTERS_LIST, decoded.overrideFor(KeyEvent.KEYCODE_PROG_BLUE)?.job)
        assertEquals(RemoteButtonJob.CHAPTERS_LIST, decoded.holdFor(KeyEvent.KEYCODE_MENU)?.job)
        assertEquals(RemoteButtonJob.CHAPTERS_LIST, RemoteButtonJob.fromId("chapters_list"))
        assertFalse(RemoteButtonJob.CHAPTERS_LIST.keepsPauseOverlay)
        assertFalse(RemoteButtonJob.CHAPTERS_LIST.needsSkipSize)
    }
}
