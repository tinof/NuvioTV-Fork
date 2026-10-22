package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.ui.screens.player.iec.IecAudioTrackFactory
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/** F7: the tunnel guard judges an AAC track on the FFmpeg renderer by what the transcode writes. */
class AacToDtsTunnelGuardTest {

    private val aac: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build()
    private val both = setOf(C.ENCODING_DTS, C.ENCODING_DTS_HD)

    private fun reason(
        sink: PlaybackSpeedAwareAudioSink?,
        ledger: Set<String> = emptySet(),
        hbrTunnelAllowed: Boolean = false
    ) = audioTunnelClearReason(aac, isFfmpegRenderer = true, sink, ledger, null, hbrTunnelAllowed)

    @Test
    fun hdTranscodeThroughTheOwnIec_dropsTunnelling() {
        val iec = IecPassthroughAudioSink(sink = FakeSink(), trackFactory = ReadyIec)
        val sink = PlaybackSpeedAwareAudioSink(
            sink = iec,
            transcodeAacToDts = true,
            transcodeAacToDtsHd = true,
            pinnedOutputEncodings = both
        )
        assertEquals("iec-hbr", reason(sink))
    }

    @Test
    fun hdTranscodeOnThePlatform_isAnHbrFormat_unlessHbrTunnellingIsAllowed() {
        val sink = speedSink(hdAllowed = true)
        assertEquals("hbr-format", reason(sink))
        assertNull(reason(sink, hbrTunnelAllowed = true))
    }

    @Test
    fun hdTranscode_deadClockLedgerUsesTheDtsHdClass() {
        val sink = speedSink(hdAllowed = true)
        assertEquals("dead-tunnel-clock", reason(sink, ledger = setOf(MimeTypes.AUDIO_DTS_HD), hbrTunnelAllowed = true))
        assertNull(reason(sink, ledger = setOf(PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM), hbrTunnelAllowed = true))
    }

    @Test
    fun coreTranscode_usesTheDtsClass() {
        val sink = speedSink(hdAllowed = false)
        assertNull(reason(sink))
        assertEquals("dead-tunnel-clock", reason(sink, ledger = setOf(MimeTypes.AUDIO_DTS)))
    }

    @Test
    fun noTranscode_staysPcm() {
        val sink = speedSink(transcode = false)
        assertNull(reason(sink, ledger = setOf(MimeTypes.AUDIO_DTS_HD)))
        assertEquals("dead-tunnel-clock", reason(sink, ledger = setOf(PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM)))
        assertEquals("dead-tunnel-clock", reason(null, ledger = setOf(PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM)))
    }

    private fun speedSink(transcode: Boolean = true, hdAllowed: Boolean = true) = PlaybackSpeedAwareAudioSink(
        sink = FakeSink(),
        transcodeAacToDts = transcode,
        transcodeAacToDtsHd = hdAllowed,
        pinnedOutputEncodings = both
    )

    private object ReadyIec : IecAudioTrackFactory {
        override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int) = null
        override fun iec61937Ready(): Boolean = true
    }

    private class FakeSink : AudioSink {
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean = true
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
