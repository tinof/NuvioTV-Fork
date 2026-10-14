package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxRenderPolicyTest {
    @Test fun `Amazon retains opaque bars while other manufacturers use transparent bars`() {
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox(" Amazon "))
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox("amazon"))
        assertTrue(LetterboxRenderPolicy.defaultTransparentLetterbox("Ugoos"))
    }

    @Test fun `Nvidia gets an opaque window outside playback, the switch forces either side`() {
        assertTrue(LetterboxRenderPolicy.opaqueWindowOutsidePlayback(null, "NVIDIA"))
        assertFalse(LetterboxRenderPolicy.opaqueWindowOutsidePlayback(null, "Amlogic"))
        assertFalse(LetterboxRenderPolicy.opaqueWindowOutsidePlayback(null, null))
        assertTrue(LetterboxRenderPolicy.opaqueWindowOutsidePlayback(true, "Amazon"))
        assertFalse(LetterboxRenderPolicy.opaqueWindowOutsidePlayback(false, "NVIDIA"))
    }

    @Test fun `the window turns transparent while the player asks for a transparent letterbox`() {
        assertTrue(LetterboxRenderPolicy.opaqueWindowNow(opaqueOutsidePlayback = true, transparentRequested = false))
        assertFalse(LetterboxRenderPolicy.opaqueWindowNow(opaqueOutsidePlayback = true, transparentRequested = true))
        assertFalse(LetterboxRenderPolicy.opaqueWindowNow(opaqueOutsidePlayback = false, transparentRequested = false))
        assertFalse(LetterboxRenderPolicy.opaqueWindowNow(opaqueOutsidePlayback = false, transparentRequested = true))
    }

    @Test fun `overlapping player destinations keep transparency until both leave`() {
        assertFalse(PlayerWindowBackdrop.isTransparentRequested)
        PlayerWindowBackdrop.acquireTransparent()
        try {
            PlayerWindowBackdrop.acquireTransparent()
            PlayerWindowBackdrop.releaseTransparent()
            assertTrue(PlayerWindowBackdrop.isTransparentRequested)
        } finally {
            PlayerWindowBackdrop.releaseTransparent()
        }
        assertFalse(PlayerWindowBackdrop.isTransparentRequested)
        PlayerWindowBackdrop.releaseTransparent()
        PlayerWindowBackdrop.acquireTransparent()
        try {
            assertTrue(PlayerWindowBackdrop.isTransparentRequested)
        } finally {
            PlayerWindowBackdrop.releaseTransparent()
        }
    }
}
