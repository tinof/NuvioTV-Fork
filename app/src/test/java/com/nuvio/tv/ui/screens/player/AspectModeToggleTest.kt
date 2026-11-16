package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectModeToggleTest {

    @Test
    fun hdrLockedToFit_keepsModeAndShowsHdrFitLabel() {
        val toggle = aspectModeToggle(
            current = AspectMode.CINEMA_ZOOM,
            hdrVideo = true,
            hdrScaleAllowed = hdrViewScaleAllowed(switch = null, manufacturer = "NVIDIA")
        )

        assertFalse(toggle.cycles)
        assertEquals(AspectMode.CINEMA_ZOOM, toggle.mode)
        assertEquals(R.string.player_aspect_fit_hdr, toggle.labelResId)
    }

    @Test
    fun hdrWithZoomSwitchOnNvidia_cycles() {
        assertCycles(
            hdrVideo = true,
            hdrScaleAllowed = hdrViewScaleAllowed(switch = true, manufacturer = "NVIDIA")
        )
    }

    @Test
    fun hdrOnOtherBoxes_cycles() {
        assertCycles(
            hdrVideo = true,
            hdrScaleAllowed = hdrViewScaleAllowed(switch = null, manufacturer = "Amlogic")
        )
    }

    @Test
    fun sdrOnNvidia_cycles() {
        assertCycles(
            hdrVideo = false,
            hdrScaleAllowed = hdrViewScaleAllowed(switch = null, manufacturer = "NVIDIA")
        )
    }

    @Test
    fun lockMatchesTheSurfaceFallback() {
        for (hdrVideo in listOf(true, false)) {
            for (allowed in listOf(true, false)) {
                val surfaceFit = aspectModeAppliedToExoSurface(
                    tunnelingEnabled = false,
                    aspectMode = AspectMode.STRETCH,
                    hdrVideo = hdrVideo,
                    hdrScaleAllowed = allowed
                ) == AspectMode.ORIGINAL
                assertEquals(surfaceFit, !aspectModeToggle(AspectMode.STRETCH, hdrVideo, allowed).cycles)
            }
        }
    }

    private fun assertCycles(hdrVideo: Boolean, hdrScaleAllowed: Boolean) {
        val toggle = aspectModeToggle(AspectMode.ORIGINAL, hdrVideo, hdrScaleAllowed)

        assertTrue(toggle.cycles)
        assertEquals(nextAspectMode(AspectMode.ORIGINAL), toggle.mode)
        assertEquals(toggle.mode.labelResId, toggle.labelResId)
    }
}
