package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import com.nuvio.tv.core.player.DvConversionGate
import com.nuvio.tv.core.player.DvSignallingDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DV2.3: a Dolby Vision decoder failure of a playback that signalled a converted track replays the
 * stream native; the player adds it to dvConvertDisabledStreamUrls, which turns the gate off.
 */
class DvConvertFallbackTest {

    private fun initFailure(format: Format) = ExoPlaybackException.createForRenderer(
        IllegalStateException("configure failed"),
        "MediaCodecVideoRenderer",
        0,
        format,
        C.FORMAT_HANDLED,
        false,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    )

    private val converted = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs("dvhe.08.06")
        .build()

    @Test
    fun `an init failure of a converted Format is a Dolby Vision decoder failure`() {
        assertTrue(initFailure(converted).isDolbyVisionDecoderFailure())
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()
        assertFalse(initFailure(hevc).isDolbyVisionDecoderFailure())
    }

    @Test
    fun `only a playback that signalled a converted track retries native`() {
        val target = DvSignallingDecision.Target.PROFILE_8_4
        assertTrue(DvConversionGate.retriesNative(armed = true, convertedTarget = target, dolbyVisionDecoderFailure = true))
        // Native Dolby Vision content in an armed playback keeps the existing Dolby Vision ladder.
        assertFalse(DvConversionGate.retriesNative(armed = true, convertedTarget = null, dolbyVisionDecoderFailure = true))
        assertFalse(DvConversionGate.retriesNative(armed = false, convertedTarget = target, dolbyVisionDecoderFailure = true))
        assertFalse(DvConversionGate.retriesNative(armed = true, convertedTarget = target, dolbyVisionDecoderFailure = false))
    }

    @Test
    fun `the retry resolves the gate off for that stream`() {
        val retry = DvConversionGate.resolve(
            setting = true, revertSwitch = null, isNvidia = true, dolbyVisionDecoderPresent = true,
            displayDolbyVision = true, rpuBuilderAvailable = true, streamDisabled = true,
            stripOrBaseLayerMode = false, mapToHevcMode = false,
        )
        assertFalse(retry.enabled)
    }
}
