package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.FrameRateUtils.DisplayModeSpec
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfrExactFirstTest {

    private val ntsc30 = 30000f / 1001f

    // 3840x2160 modes as a Shield lists them on a TV without QMS; mode 1 is the UI mode.
    private val modes = listOf(
        mode(1, 59.94f),
        mode(2, 60f),
        mode(3, 50f),
        mode(4, 30f),
        mode(5, 29.97f),
        mode(6, 25f),
        mode(7, 24f),
        mode(8, 23.976f)
    )

    @After
    fun tearDown() {
        unmockkObject(AfrExactFirst)
    }

    @Test
    fun `nvidia takes the exact rate first and other boxes keep upstream's order`() {
        assertTrue(AfrExactFirst.enabled(isNvidia = true, switch = null))
        assertFalse(AfrExactFirst.enabled(isNvidia = false, switch = null))
    }

    @Test
    fun `the switch wins over the device`() {
        assertFalse(AfrExactFirst.enabled(isNvidia = true, switch = false))
        assertTrue(AfrExactFirst.enabled(isNvidia = false, switch = true))
    }

    @Test
    fun `exact first sends 25 and 30 fps to their own rates`() {
        exactFirst(true)
        assertEquals(25f, pick(modes, 25f), 0.01f)
        assertEquals(29.97f, pick(modes, ntsc30), 0.01f)
        assertEquals(30f, pick(modes, 30f), 0.01f)
    }

    @Test
    fun `upstream's order sends 25 and 30 fps to the doubled rates`() {
        exactFirst(false)
        assertEquals(50f, pick(modes, 25f), 0.01f)
        assertEquals(59.94f, pick(modes, ntsc30), 0.01f)
        assertEquals(60f, pick(modes, 30f), 0.01f)
    }

    @Test
    fun `exact first falls back to the doubled rate when the display lacks the exact one`() {
        exactFirst(true)
        val without25 = modes.filterNot { it.refreshRate == 25f }
        assertEquals(50f, pick(without25, 25f), 0.01f)
    }

    @Test
    fun `exact first leaves film and 50 fps alone`() {
        exactFirst(true)
        assertEquals(24f, pick(modes, 24f), 0.01f)
        assertEquals(50f, pick(modes, 50f), 0.01f)
    }

    private fun exactFirst(on: Boolean) {
        mockkObject(AfrExactFirst)
        every { AfrExactFirst.enabled() } returns on
    }

    private fun pick(table: List<DisplayModeSpec>, frameRate: Float): Float =
        FrameRateUtils.selectDisplayMode(table, table[0], frameRate, null, null, false)!!.refreshRate

    private fun mode(id: Int, refreshRate: Float) = DisplayModeSpec(id, 3840, 2160, refreshRate)
}
