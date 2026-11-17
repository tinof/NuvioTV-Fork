package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.ui.screens.player.AfrUiModeRestore.OnClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfrUiModeRestoreTest {

    @Test
    fun `a new player does not restore the UI mode before its settings load`() {
        assertFalse(AfrUiModeRestore.restoreWhileOpen(FrameRateMatchingMode.OFF, settingsLoaded = false))
    }

    @Test
    fun `a loaded OFF setting restores the UI mode, also when switched off mid-session`() {
        assertTrue(AfrUiModeRestore.restoreWhileOpen(FrameRateMatchingMode.OFF, settingsLoaded = true))
    }

    @Test
    fun `a loaded matching setting keeps the mode while open`() {
        assertFalse(AfrUiModeRestore.restoreWhileOpen(FrameRateMatchingMode.START, settingsLoaded = true))
        assertFalse(AfrUiModeRestore.restoreWhileOpen(FrameRateMatchingMode.START_STOP, settingsLoaded = true))
    }

    @Test
    fun `start-stop switches back on close`() {
        assertEquals(OnClose.SCHEDULE_RESTORE, AfrUiModeRestore.onClose(FrameRateMatchingMode.START_STOP, settingsLoaded = true))
    }

    @Test
    fun `off and start clear the original mode on close`() {
        assertEquals(OnClose.CLEAR, AfrUiModeRestore.onClose(FrameRateMatchingMode.OFF, settingsLoaded = true))
        assertEquals(OnClose.CLEAR, AfrUiModeRestore.onClose(FrameRateMatchingMode.START, settingsLoaded = true))
    }

    @Test
    fun `a player closed before its settings load still switches back the mode the last player left`() {
        assertEquals(OnClose.SCHEDULE_RESTORE, AfrUiModeRestore.onClose(FrameRateMatchingMode.OFF, settingsLoaded = false))
    }
}
