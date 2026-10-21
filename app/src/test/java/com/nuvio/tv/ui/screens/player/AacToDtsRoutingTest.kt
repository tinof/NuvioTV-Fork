package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.AacToDtsRouting
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import org.junit.Assert.assertFalse
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

    private fun sink(
        transcodeAacToDts: Boolean = true,
        platformSupportsDts: Boolean = true,
        bluetooth: Boolean = false,
        policy: AudioPassthroughPolicy = AudioPassthroughPolicy.ALLOW_ALL
    ) = PlaybackSpeedAwareAudioSink(
        sink = DtsCapableSink(platformSupportsDts),
        forcePcmForBluetooth = bluetooth,
        passthroughPolicy = policy,
        transcodeAacToDts = transcodeAacToDts
    )

    private fun aac(channels: Int, codecs: String? = null): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setCodecs(codecs)
            .setChannelCount(channels)
            .setSampleRate(48_000)
            .build()

    private class DtsCapableSink(private val supportsDts: Boolean) : AudioSink {
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean =
            getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format: Format): Int =
            if (format.sampleMimeType == MimeTypes.AUDIO_DTS && !supportsDts) {
                AudioSink.SINK_FORMAT_UNSUPPORTED
            } else {
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
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
