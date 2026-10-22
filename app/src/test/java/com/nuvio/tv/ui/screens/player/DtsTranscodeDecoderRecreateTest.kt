package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.AacToDtsRouting.Target
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtsTranscodeDecoderRecreateTest {

    private fun recreate(current: Target, wanted: Target, ffmpegFirst: Boolean, active: Boolean = true) =
        dtsTranscodeNeedsDecoderRecreate(active, current, wanted, ffmpegFirst)

    @Test
    fun ffmpegFirst_followsEveryTargetChange() {
        assertTrue(recreate(Target.CORE, Target.NONE, ffmpegFirst = true))
        assertTrue(recreate(Target.NONE, Target.CORE, ffmpegFirst = true))
        assertTrue(recreate(Target.NONE, Target.HD, ffmpegFirst = true))
        assertTrue(recreate(Target.HD, Target.NONE, ffmpegFirst = true))
        assertTrue(recreate(Target.HD, Target.CORE, ffmpegFirst = true))
    }

    @Test
    fun mediaCodecFirst_onlyBetweenHdAndCore() {
        // MediaCodec still declines the track, so FFmpeg keeps it and its old decoder.
        assertTrue(recreate(Target.HD, Target.CORE, ffmpegFirst = false))
        assertTrue(recreate(Target.CORE, Target.HD, ffmpegFirst = false))
        // To or from PCM MediaCodec takes the track over itself.
        assertFalse(recreate(Target.CORE, Target.NONE, ffmpegFirst = false))
        assertFalse(recreate(Target.HD, Target.NONE, ffmpegFirst = false))
        assertFalse(recreate(Target.NONE, Target.HD, ffmpegFirst = false))
    }

    @Test
    fun sameTarget_orAnotherRenderer_leavesItAlone() {
        for (target in Target.values()) {
            assertFalse(recreate(target, target, ffmpegFirst = true))
            assertFalse(recreate(target, target, ffmpegFirst = false))
        }
        assertFalse(recreate(Target.NONE, Target.HD, ffmpegFirst = true, active = false))
        assertFalse(recreate(Target.HD, Target.CORE, ffmpegFirst = false, active = false))
    }
}
