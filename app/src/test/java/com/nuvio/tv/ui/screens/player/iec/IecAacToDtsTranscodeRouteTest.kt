package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.AacToDtsRouting
import com.nuvio.tv.ui.screens.player.EncodedFrameCountingAudioSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The AAC-to-DTS transcode keeps the routes it was proven on: the core goes to Android's own DTS
 * output, not to the own IEC track that plain DTS takes since the sync of 2026-11-11, and DTS-HD MA
 * keeps the 2-channel carrier that a real lossless DTS-HD track leaves for 8 channels.
 */
class IecAacToDtsTranscodeRouteTest {

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun coreTranscode_staysOnAndroidsOutput() {
        val factory = Tracks()
        val inner = RecordingSink()
        val sink = sink(factory, inner, AacToDtsRouting.DTS_OUTPUT_FORMAT)
        assertFalse(sink.isIecActive)
        assertTrue(factory.opened.isEmpty())
        assertEquals(listOf(AacToDtsRouting.DTS_OUTPUT_FORMAT), inner.configured)
    }

    @Test
    fun plainDtsWithoutTheMarker_stillTakesTheOwnTrack() {
        val factory = Tracks()
        val plain = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        assertTrue(sink(factory, RecordingSink(), plain).isIecActive)
        assertEquals(listOf(48_000 to 2), factory.opened)
    }

    @Test
    fun ffmpegRenderersDtsFormat_isMarkedAsTheCoreTranscode() {
        val inner = RecordingSink()
        val counting = EncodedFrameCountingAudioSink(inner)
        val fromRenderer = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        counting.configure(fromRenderer, 0, null)
        assertTrue(AacToDtsRouting.isDtsCoreTranscodeOutput(inner.configured.single()))
        assertEquals(48_000, inner.configured.single().sampleRate)

        val pcm = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        counting.configure(pcm, 0, null)
        assertNull(inner.configured.last().id)
        counting.configure(AacToDtsRouting.DTS_HD_OUTPUT_FORMAT, 0, null)
        assertEquals(AacToDtsRouting.DTS_HD_OUTPUT_FORMAT, inner.configured.last())
    }

    @Test
    fun markerTellsTheTranscodeFromRealDts() {
        assertTrue(AacToDtsRouting.isDtsCoreTranscodeOutput(AacToDtsRouting.DTS_OUTPUT_FORMAT))
        assertFalse(AacToDtsRouting.isDtsCoreTranscodeOutput(AacToDtsRouting.DTS_HD_OUTPUT_FORMAT))
        val realDts = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).setChannelCount(6).build()
        assertFalse(AacToDtsRouting.isDtsCoreTranscodeOutput(realDts))
        val otherId = realDts.buildUpon().setId("1").build()
        assertEquals(otherId, AacToDtsRouting.markTranscodeOutput(otherId))
    }

    @Test
    fun hdTranscode_keepsTheStereoCarrier() {
        val factory = Tracks()
        val sink = sink(factory, RecordingSink(), AacToDtsRouting.DTS_HD_OUTPUT_FORMAT)
        assertTrue(sink.isIecActive)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(units, 0, UNIT_0 + UNIT_1).slice(), 0L, 2))
        assertEquals(listOf(192_000 to 2), factory.opened)
        val written = factory.written()
        assertEquals(2 * STEREO_BURST, written.size)
        for (i in 0 until 2) {
            assertEquals("type DTS-HD", 0x11, written[i * STEREO_BURST + 4].toInt() and 0xFF)
            assertEquals("subtype 2 (period 2048)", 2, written[i * STEREO_BURST + 5].toInt() and 0xFF)
        }
    }

    @Test
    fun realLosslessDtsHd_stillMovesToTheEightChannelCarrier() {
        val factory = Tracks()
        val realDtsHd = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        val sink = sink(factory, RecordingSink(), realDtsHd)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(units, 0, UNIT_0).slice(), 0L, 1))
        assertEquals(listOf(192_000 to 2, 192_000 to 8), factory.opened)
    }

    private val units: ByteArray =
        javaClass.getResourceAsStream("/dtshd/transcode_4units.dts")!!.use { it.readBytes() }

    private fun sink(factory: Tracks, inner: RecordingSink, format: Format): IecPassthroughAudioSink {
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(format, 0, null)
        sink.play()
        return sink
    }

    private class Tracks : IecAudioTrackFactory {
        val opened = mutableListOf<Pair<Int, Int>>()
        private val tracks = mutableListOf<RecordingTrack>()

        fun written(): ByteArray = tracks.last().out.toByteArray()

        override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack {
            opened.add(sampleRate to channelCount)
            return RecordingTrack(sampleRate, channelCount).also { tracks.add(it) }
        }

        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = true
        override fun iec61937Ready(): Boolean = true
        override fun iec61937ReadyAt(sampleRate: Int): Boolean = true
    }

    private class RecordingTrack(override val sampleRate: Int, channels: Int) : IecAudioTrack {
        override val frameSizeBytes: Int = channels * 2
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = 1 shl 20
        val out = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            out.write(data, offset, size)
            return size
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = 0L
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
    }

    private class RecordingSink : AudioSink {
        val configured = mutableListOf<Format>()
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            configured.add(inputFormat)
        }
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
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

    private companion object {
        const val UNIT_0 = 4188
        const val UNIT_1 = 4216
        const val STEREO_BURST = 8192
    }
}
