package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.AacToDtsRouting
import com.nuvio.tv.core.player.AacToDtsRouting.Target
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AacToDtsRoutingTest {

    @Test
    fun monoAndStereoAac_areEligible() {
        assertTrue(AacToDtsRouting.isEligibleSource(aac(1)))
        assertTrue(AacToDtsRouting.isEligibleSource(aac(2)))
        assertTrue(AacToDtsRouting.isEligibleSource(aac(2, codecs = "mp4a.40.2")))
        assertTrue(AacToDtsRouting.isEligibleSource(aac(1, codecs = "mp4a.40.29")))
    }

    @Test
    fun multichannelUnknownEncryptedAndXheAac_areNotEligible() {
        assertFalse(AacToDtsRouting.isEligibleSource(aac(6)))
        assertFalse(AacToDtsRouting.isEligibleSource(aac(Format.NO_VALUE)))
        assertFalse(AacToDtsRouting.isEligibleSource(aac(2, codecs = "mp4a.40.42")))
        assertFalse(
            AacToDtsRouting.isEligibleSource(aac(2).buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build())
        )
    }

    @Test
    fun otherCodecs_areNotEligible() {
        for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_DTS)) {
            val format = Format.Builder().setSampleMimeType(mime).setChannelCount(2).setSampleRate(48_000).build()
            assertFalse(mime, AacToDtsRouting.isEligibleSource(format))
        }
    }

    @Test
    fun sinkPrefersDts_onlyWhenTheSettingIsOnAndDtsCanPlay() {
        assertTrue(sink().prefersDtsTranscode(aac(2)))
        assertFalse(sink(transcodeAacToDts = false).prefersDtsTranscode(aac(2)))
        assertFalse(sink().prefersDtsTranscode(aac(6)))
        assertFalse(sink(platformSupportsDts = false).prefersDtsTranscode(aac(2)))
    }

    @Test
    fun bluetoothSpeedAndPolicy_turnTheTranscodeOff() {
        assertFalse(sink(bluetooth = true).prefersDtsTranscode(aac(2)))

        val faster = sink()
        faster.setPlaybackParameters(PlaybackParameters(1.25f))
        assertFalse(faster.prefersDtsTranscode(aac(2)))
        faster.setPlaybackParameters(PlaybackParameters.DEFAULT)
        assertTrue(faster.prefersDtsTranscode(aac(2)))

        assertFalse(sink(policy = AudioPassthroughPolicy(allowDts = false)).prefersDtsTranscode(aac(2)))
        assertFalse(
            sink(
                policy = AudioPassthroughPolicy(learnedDeniedGroups = setOf(AudioPassthroughPolicy.Group.DTS))
            ).prefersDtsTranscode(aac(2))
        )
    }

    // --- target choice (D1, D6) ---

    private fun choose(hdAllowed: Boolean = true, forced: Target? = null, hd: Boolean, core: Boolean) =
        AacToDtsRouting.chooseTarget(true, true, hdAllowed, forced, { hd }, { core })

    @Test
    fun chooseTarget_followsTheSinkAndTheSwitches() {
        assertEquals(Target.HD, choose(hd = true, core = true))
        assertEquals(Target.CORE, choose(hd = false, core = true))
        assertEquals(Target.NONE, choose(hd = false, core = false))
        assertEquals(Target.CORE, choose(hdAllowed = false, hd = true, core = true))
        assertEquals(Target.CORE, choose(forced = Target.CORE, hd = true, core = true))
        assertEquals(Target.HD, choose(forced = Target.HD, hd = false, core = true))
        assertEquals(Target.NONE, choose(forced = Target.HD, hd = false, core = false))
        assertEquals(Target.CORE, choose(hdAllowed = false, forced = Target.HD, hd = true, core = true))
        assertEquals(Target.NONE, AacToDtsRouting.chooseTarget(false, true, true, null, { true }, { true }))
        assertEquals(Target.NONE, AacToDtsRouting.chooseTarget(true, false, true, null, { true }, { true }))
    }

    @Test
    fun targetOverride_parsesHdAndCoreOnly() {
        assertEquals(Target.HD, AacToDtsRouting.parseTargetOverride("hd"))
        assertEquals(Target.CORE, AacToDtsRouting.parseTargetOverride(" CORE "))
        assertNull(AacToDtsRouting.parseTargetOverride(null))
        assertNull(AacToDtsRouting.parseTargetOverride("auto"))
        assertNull(AacToDtsRouting.parseTargetOverride(""))
    }

    @Test
    fun hdAllowed_needsNoStepDownNoForcedCoreAndARendererThatWritesIt() {
        assertTrue(AacToDtsRouting.hdAllowed(streamSteppedDown = false, override = null, rendererWritesHd = true))
        assertTrue(AacToDtsRouting.hdAllowed(streamSteppedDown = false, override = Target.HD, rendererWritesHd = true))
        assertFalse(AacToDtsRouting.hdAllowed(streamSteppedDown = true, override = null, rendererWritesHd = true))
        assertFalse(AacToDtsRouting.hdAllowed(streamSteppedDown = false, override = Target.CORE, rendererWritesHd = true))
        // An AAR from before the DTS-HD port: the app never asks for DTS-HD MA.
        assertFalse(AacToDtsRouting.hdAllowed(streamSteppedDown = false, override = null, rendererWritesHd = false))
    }

    @Test
    fun rendererApiMapping_matchesTheModuleConstants() {
        assertEquals(-1, FfmpegRendererDtsHd.overrideValue(null))
        assertEquals(1, FfmpegRendererDtsHd.overrideValue(Target.CORE))
        assertEquals(2, FfmpegRendererDtsHd.overrideValue(Target.HD))
        assertEquals(Target.NONE, FfmpegRendererDtsHd.targetOf(0))
        assertEquals(Target.CORE, FfmpegRendererDtsHd.targetOf(1))
        assertEquals(Target.HD, FfmpegRendererDtsHd.targetOf(2))
        assertEquals(Target.NONE, FfmpegRendererDtsHd.targetOf(7))
        // writesHd follows the AAR (or module) the app is built with.
        val hasApi = runCatching {
            androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer::class.java
                .getMethod("setTranscodeAacToDtsHd", Boolean::class.javaPrimitiveType)
        }.isSuccess
        assertEquals(hasApi, FfmpegRendererDtsHd.writesHd)
    }

    @Test
    fun theTranscodeFormatIsMarked_realDtsHdIsNot() {
        assertTrue(AacToDtsRouting.isDtsHdTranscodeOutput(AacToDtsRouting.DTS_HD_OUTPUT_FORMAT))
        val realDtsHd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(2).build()
        assertFalse(AacToDtsRouting.isDtsHdTranscodeOutput(realDtsHd))
        assertFalse(AacToDtsRouting.isDtsHdTranscodeOutput(AacToDtsRouting.DTS_OUTPUT_FORMAT))
    }

    // --- the sink's answer (Codex 1: Media3 opens the core for DTS-HD on a DTS-only output) ---

    @Test
    fun sinkTarget_hdOnlyWhenDtsHdOpensAsItself() {
        val both = setOf(C.ENCODING_DTS, C.ENCODING_DTS_HD)
        assertEquals(Target.HD, sink(hdAllowed = true, pinned = both).dtsTranscodeTarget(aac(2)))
        assertEquals(Target.HD, sink(hdAllowed = true, pinned = both).dtsTranscodeTarget(aac(1)))
        // A DTS-only output: Media3 says "directly" for DTS-HD and would open the core.
        assertEquals(Target.CORE, sink(hdAllowed = true, pinned = setOf(C.ENCODING_DTS)).dtsTranscodeTarget(aac(2)))
        // Live capabilities (no pinned set): not known, so the core.
        assertEquals(Target.CORE, sink(hdAllowed = true, pinned = null).dtsTranscodeTarget(aac(2)))
        assertEquals(Target.CORE, sink(hdAllowed = false, pinned = both).dtsTranscodeTarget(aac(2)))
        assertEquals(Target.NONE, sink(hdAllowed = true, pinned = both, platformSupportsDts = false, platformSupportsDtsHd = false)
            .dtsTranscodeTarget(aac(2)))
    }

    @Test
    fun sinkTarget_ownIecRoute_isDtsHdItself() {
        val iec = com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink(
            sink = DtsCapableSink(true),
            trackFactory = ReadyIecFactory
        )
        val sink = PlaybackSpeedAwareAudioSink(
            sink = iec,
            transcodeAacToDts = true,
            transcodeAacToDtsHd = true,
            pinnedOutputEncodings = setOf(C.ENCODING_DTS)
        )
        assertEquals(Target.HD, sink.dtsTranscodeTarget(aac(2)))
    }

    @Test
    fun sinkTarget_realDtsHdTracksKeepMedia3sCoreFallback() {
        // The check applies to the transcode's marked query only.
        val realDtsHd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(6).setSampleRate(48_000).build()
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            sink(hdAllowed = true, pinned = setOf(C.ENCODING_DTS)).getFormatSupport(realDtsHd)
        )
    }

    @Test
    fun sinkTarget_policySpeedBluetoothAndTheSwitch() {
        val both = setOf(C.ENCODING_DTS, C.ENCODING_DTS_HD)
        val dtsHdDenied = AudioPassthroughPolicy(learnedDeniedGroups = setOf(AudioPassthroughPolicy.Group.DTS_HD))
        assertEquals(Target.CORE, sink(hdAllowed = true, pinned = both, policy = dtsHdDenied).dtsTranscodeTarget(aac(2)))
        assertEquals(Target.NONE, sink(hdAllowed = true, pinned = both, bluetooth = true).dtsTranscodeTarget(aac(2)))
        val faster = sink(hdAllowed = true, pinned = both)
        faster.setPlaybackParameters(PlaybackParameters(1.5f))
        assertEquals(Target.NONE, faster.dtsTranscodeTarget(aac(2)))
        assertEquals(Target.HD, sink(hdAllowed = true, pinned = setOf(C.ENCODING_DTS), forced = Target.HD).dtsTranscodeTarget(aac(2)))
        assertEquals(Target.CORE, sink(hdAllowed = true, pinned = both, forced = Target.CORE).dtsTranscodeTarget(aac(2)))
        assertEquals(AacToDtsRouting.DTS_HD_OUTPUT_FORMAT, sink(hdAllowed = true, pinned = both).dtsTranscodeOutputFormat(aac(2)))
        assertEquals(AacToDtsRouting.DTS_OUTPUT_FORMAT, sink(hdAllowed = false).dtsTranscodeOutputFormat(aac(2)))
        assertNull(sink(transcodeAacToDts = false).dtsTranscodeOutputFormat(aac(2)))
    }

    private object ReadyIecFactory : com.nuvio.tv.ui.screens.player.iec.IecAudioTrackFactory {
        override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int) = null
        override fun iec61937Ready(): Boolean = true
    }

    private fun sink(
        transcodeAacToDts: Boolean = true,
        platformSupportsDts: Boolean = true,
        platformSupportsDtsHd: Boolean = true,
        bluetooth: Boolean = false,
        policy: AudioPassthroughPolicy = AudioPassthroughPolicy.ALLOW_ALL,
        hdAllowed: Boolean = false,
        pinned: Set<Int>? = null,
        forced: Target? = null
    ) = PlaybackSpeedAwareAudioSink(
        sink = DtsCapableSink(platformSupportsDts, platformSupportsDtsHd),
        forcePcmForBluetooth = bluetooth,
        passthroughPolicy = policy,
        transcodeAacToDts = transcodeAacToDts,
        transcodeAacToDtsHd = hdAllowed,
        dtsTargetOverride = forced,
        pinnedOutputEncodings = pinned
    )

    private fun aac(channels: Int, codecs: String? = null): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setCodecs(codecs)
            .setChannelCount(channels)
            .setSampleRate(48_000)
            .build()

    // Media3-like: DTS-HD plays "directly" whenever DTS does (it would open the core).
    private class DtsCapableSink(
        private val supportsDts: Boolean,
        private val supportsDtsHd: Boolean = true
    ) : AudioSink {
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean =
            getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format: Format): Int = when {
            format.sampleMimeType == MimeTypes.AUDIO_DTS && !supportsDts -> AudioSink.SINK_FORMAT_UNSUPPORTED
            format.sampleMimeType == MimeTypes.AUDIO_DTS_HD && !supportsDts && !supportsDtsHd ->
                AudioSink.SINK_FORMAT_UNSUPPORTED
            else -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            buffer.position(buffer.limit())
            return true
        }
        override fun playToEndOfStream() = Unit
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) = Unit
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
        override fun enableTunnelingV21() = Unit
        override fun disableTunneling() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }
}
