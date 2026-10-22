package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D5: a refused transcode AudioTrack steps the stream down one target; real DTS is learned as before. */
class AacToDtsStepDownTest {

    @Test
    fun refusedDtsHdTranscode_fallsToTheCore() {
        assertEquals(AacToDtsStepDown.HD_OFF, aacToDtsStepDown(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun refusedCoreTranscode_fallsToPcm() {
        assertEquals(AacToDtsStepDown.ALL_OFF, aacToDtsStepDown(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_DTS))
    }

    @Test
    fun realDtsTracks_areNotATranscodeStep() {
        assertNull(aacToDtsStepDown(MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_HD))
        assertNull(aacToDtsStepDown(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS))
        assertNull(aacToDtsStepDown(MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun pcmOrUnknown_isNotATranscodeStep() {
        assertNull(aacToDtsStepDown(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_RAW))
        assertNull(aacToDtsStepDown(null, MimeTypes.AUDIO_DTS_HD))
        assertNull(aacToDtsStepDown(MimeTypes.AUDIO_AAC, null))
    }

    @Test
    fun aScheduledSameConfigurationRetry_defersTheStep() {
        assertNull(aacToDtsStepDown(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_DTS_HD, retryScheduled = true))
        assertNull(aacToDtsStepDown(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_DTS, retryScheduled = true))
    }
}
