package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvHwdecCodecsTest {

    @Test
    fun `AV1 goes to MediaCodec only with a hardware decoder`() {
        assertTrue("av1" in mpvHwdecCodecs(hardwareAv1 = true).split(','))
        assertFalse("av1" in mpvHwdecCodecs(hardwareAv1 = false).split(','))
        assertTrue("hevc" in mpvHwdecCodecs(hardwareAv1 = false).split(','))
    }

    @Test
    fun `VC-1 is never handed to MediaCodec`() {
        assertFalse("vc1" in mpvHwdecCodecs(hardwareAv1 = true).split(','))
        assertFalse("vc1" in mpvHwdecCodecs(hardwareAv1 = false).split(','))
    }
}
