package com.nuvio.tv.ui.v2.components

import com.nuvio.tv.ui.screens.detail.episodeOverlayBackdropMemoryCacheKey
import com.nuvio.tv.ui.v2.quality.VisualQualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreblurredGlassTest {
    @Test fun `only boxes without live blur outside playback in cinematic glass want the picture`() {
        assertTrue(preblurredGlassWanted(VisualQualityTier.ENHANCED, supportsLiveBlur = false, playback = false, cinematic = true))
        assertTrue(preblurredGlassWanted(VisualQualityTier.MAXIMUM, supportsLiveBlur = false, playback = false, cinematic = true))
        assertFalse(preblurredGlassWanted(VisualQualityTier.PERFORMANCE, supportsLiveBlur = false, playback = false, cinematic = true))
        assertFalse(preblurredGlassWanted(VisualQualityTier.ENHANCED, supportsLiveBlur = true, playback = false, cinematic = true))
        assertFalse(preblurredGlassWanted(VisualQualityTier.ENHANCED, supportsLiveBlur = false, playback = true, cinematic = true))
        assertFalse(preblurredGlassWanted(VisualQualityTier.ENHANCED, supportsLiveBlur = false, playback = false, cinematic = false))
    }

    @Test fun `surfaces follow the live source checks and skip dialogs and focused cards`() {
        assertTrue(preblurredGlassAllowed(true, GlassRole.NAVIGATION, dark = false, neutralBackdrop = false, smoked = false))
        assertTrue(preblurredGlassAllowed(true, GlassRole.PANEL, dark = false, neutralBackdrop = false, smoked = false))
        assertFalse(preblurredGlassAllowed(false, GlassRole.PANEL, dark = false, neutralBackdrop = false, smoked = false))
        assertFalse(preblurredGlassAllowed(true, GlassRole.PANEL, dark = true, neutralBackdrop = false, smoked = false))
        assertFalse(preblurredGlassAllowed(true, GlassRole.PANEL, dark = false, neutralBackdrop = true, smoked = false))
        assertFalse(preblurredGlassAllowed(true, GlassRole.PANEL, dark = false, neutralBackdrop = false, smoked = true))
        assertFalse(preblurredGlassAllowed(true, GlassRole.MODAL, dark = false, neutralBackdrop = false, smoked = false))
        assertFalse(preblurredGlassAllowed(true, GlassRole.CARD_FOCUS, dark = false, neutralBackdrop = false, smoked = false))
    }

    @Test fun `picture is window sized centre crop shifted by the surface position`() {
        // 480x270 picture on a 1920x1080 window, surface at (100, 200).
        assertEquals(
            PreblurredGlassPlacement(left = -100, top = -200, width = 1920, height = 1080),
            preblurredGlassPlacement(480, 270, 1920f, 1080f, 100f, 200f)
        )
        // A 4:3 picture overhangs top and bottom equally.
        assertEquals(
            PreblurredGlassPlacement(left = 0, top = -180, width = 1920, height = 1440),
            preblurredGlassPlacement(400, 300, 1920f, 1080f, 0f, 0f)
        )
        assertNull(preblurredGlassPlacement(0, 270, 1920f, 1080f, 0f, 0f))
        assertNull(preblurredGlassPlacement(480, 270, 0f, 1080f, 0f, 0f))
    }

    @Test fun `glass blur radius gets its own memory key and the overlay key is unchanged`() {
        val url = "https://image.tmdb.org/t/p/w1280/a.jpg"
        assertEquals("${url}_480x270_blurtrue", episodeOverlayBackdropMemoryCacheKey(url, 480, 270, true))
        assertEquals("${url}_480x270_blurtrue", episodeOverlayBackdropMemoryCacheKey(url, 480, 270, true, 25))
        assertNotEquals(
            episodeOverlayBackdropMemoryCacheKey(url, 480, 270, true),
            episodeOverlayBackdropMemoryCacheKey(url, 480, 270, true, 8)
        )
        assertEquals("${url}_480x270_blurfalse", episodeOverlayBackdropMemoryCacheKey(url, 480, 270, false, 8))
    }
}
