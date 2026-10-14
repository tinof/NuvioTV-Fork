package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunneledSurfaceResizeModeTest {

    @Test
    fun exoSurfaceResizeMode_staysFitUnlessTunnelingOptsIntoFill() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = false,
                tunneledSurfaceFill = false
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = false,
                tunneledSurfaceFill = true
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = true,
                tunneledSurfaceFill = false
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = true,
                tunneledSurfaceFill = true
            )
        )
    }

    @Test
    fun aspectModeAppliedToExoSurface_dropsScaleModesWhileTunneling() {
        assertEquals(
            AspectMode.CINEMA_ZOOM,
            aspectModeAppliedToExoSurface(
                tunnelingEnabled = false,
                aspectMode = AspectMode.CINEMA_ZOOM
            )
        )
        assertEquals(
            AspectMode.ORIGINAL,
            aspectModeAppliedToExoSurface(
                tunnelingEnabled = true,
                aspectMode = AspectMode.CINEMA_ZOOM
            )
        )
    }

    @Test
    fun aspectModeAppliedToExoSurface_hdrFallsBackToFitWhenScalingIsBlocked() {
        for (mode in AspectMode.entries) {
            assertEquals(
                AspectMode.ORIGINAL,
                aspectModeAppliedToExoSurface(
                    tunnelingEnabled = false,
                    aspectMode = mode,
                    hdrVideo = true,
                    hdrViewScaleAllowed = false
                )
            )
        }
        assertEquals(
            AspectMode.SLIGHT_ZOOM,
            aspectModeAppliedToExoSurface(
                tunnelingEnabled = false,
                aspectMode = AspectMode.SLIGHT_ZOOM,
                hdrVideo = false,
                hdrViewScaleAllowed = false
            )
        )
        assertEquals(
            AspectMode.FULL_SCREEN,
            aspectModeAppliedToExoSurface(
                tunnelingEnabled = false,
                aspectMode = AspectMode.FULL_SCREEN,
                hdrVideo = true,
                hdrViewScaleAllowed = true
            )
        )
    }

    @Test
    fun hdrViewScale_blockedByDefaultOnNvidiaOnly() {
        assertFalse(hdrViewScaleAllowed(switch = null, manufacturer = "NVIDIA"))
        assertTrue(hdrViewScaleAllowed(switch = null, manufacturer = "Amlogic"))
        assertTrue(hdrViewScaleAllowed(switch = null, manufacturer = null))
    }

    @Test
    fun hdrViewScale_debugSwitchWinsEitherWay() {
        assertTrue(hdrViewScaleAllowed(switch = true, manufacturer = "NVIDIA"))
        assertFalse(hdrViewScaleAllowed(switch = false, manufacturer = "Amlogic"))
    }
}
