package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateMatchingDefaultTest {

    @Test
    fun `nvidia starts with start and stop, other boxes stay off`() {
        assertEquals(FrameRateMatchingMode.START_STOP, defaultFrameRateMatchingMode("NVIDIA"))
        assertEquals(FrameRateMatchingMode.START_STOP, defaultFrameRateMatchingMode("nvidia"))
        assertEquals(FrameRateMatchingMode.OFF, defaultFrameRateMatchingMode("Amlogic"))
        assertEquals(FrameRateMatchingMode.OFF, defaultFrameRateMatchingMode(null))
    }

    @Test
    fun `a saved mode wins over the device default`() {
        assertEquals(
            FrameRateMatchingMode.OFF,
            storedFrameRateMatchingMode(storedMode = "OFF", legacyEnabled = null, manufacturer = "NVIDIA")
        )
        assertEquals(
            FrameRateMatchingMode.START,
            storedFrameRateMatchingMode(storedMode = "START", legacyEnabled = false, manufacturer = "Amlogic")
        )
    }

    @Test
    fun `the old on off switch still counts as a saved choice`() {
        assertEquals(
            FrameRateMatchingMode.OFF,
            storedFrameRateMatchingMode(storedMode = null, legacyEnabled = false, manufacturer = "NVIDIA")
        )
        assertEquals(
            FrameRateMatchingMode.START_STOP,
            storedFrameRateMatchingMode(storedMode = null, legacyEnabled = true, manufacturer = "Amlogic")
        )
    }

    @Test
    fun `nothing saved or an unknown value falls back to the device default`() {
        assertEquals(
            FrameRateMatchingMode.START_STOP,
            storedFrameRateMatchingMode(storedMode = null, legacyEnabled = null, manufacturer = "NVIDIA")
        )
        assertEquals(
            FrameRateMatchingMode.OFF,
            storedFrameRateMatchingMode(storedMode = "BOGUS", legacyEnabled = null, manufacturer = "Amlogic")
        )
    }
}
