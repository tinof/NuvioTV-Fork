package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtsTranscodeDecoderRecreateTest {

    @Test
    fun dtsThatIsNoLongerAllowed_recreatesTheDecoder() {
        assertTrue(dtsTranscodeNeedsDecoderRecreate(ffmpegActive = true, transcodingDts = true, wantsDts = false))
    }

    @Test
    fun pcmWhenDtsIsAllowedAgain_recreatesTheDecoder() {
        assertTrue(dtsTranscodeNeedsDecoderRecreate(ffmpegActive = true, transcodingDts = false, wantsDts = true))
    }

    @Test
    fun matchingOutput_orAnotherRenderer_leavesItAlone() {
        assertFalse(dtsTranscodeNeedsDecoderRecreate(ffmpegActive = true, transcodingDts = true, wantsDts = true))
        assertFalse(dtsTranscodeNeedsDecoderRecreate(ffmpegActive = true, transcodingDts = false, wantsDts = false))
        assertFalse(dtsTranscodeNeedsDecoderRecreate(ffmpegActive = false, transcodingDts = false, wantsDts = true))
    }
}
