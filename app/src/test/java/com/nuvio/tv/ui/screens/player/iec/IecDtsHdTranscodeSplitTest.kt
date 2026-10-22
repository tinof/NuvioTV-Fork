package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The AAC-to-DTS-HD MA transcode hands the own IEC engine 2 to 4 access units (core + ExSS) per
 * buffer; each goes out in its own burst. Other DTS-HD buffers keep the one-burst path.
 */
class IecDtsHdTranscodeSplitTest {

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    private val units: ByteArray =
        javaClass.getResourceAsStream("/dtshd/transcode_4units.dts")!!.use { it.readBytes() }
    private val unitSizes = intArrayOf(4188, 4216, 4200, 4200)

    @Test
    fun twoUnits_giveTwoStereoBursts() {
        val track = RecordingTrack()
        val sink = openSink(track)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(units, 0, unitSizes[0] + unitSizes[1]).slice(), 0L, 2))
        assertBursts(track, 2)
    }

    @Test
    fun fourUnits_giveFourBursts_andEachCarriesItsUnit() {
        val track = RecordingTrack()
        val sink = openSink(track)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(units), 0L, 4))
        assertBursts(track, 4)
        var offset = 0
        val written = track.bytes()
        for ((i, size) in unitSizes.withIndex()) {
            val burst = written.copyOfRange(i * BURST, (i + 1) * BURST)
            val payload = unswap(burst.copyOfRange(8, 8 + 12 + size + (size and 1)))
            assertEquals("unit $i size field", size, ((payload[10].toInt() and 0xFF) shl 8) or (payload[11].toInt() and 0xFF))
            assertArrayEquals("unit $i", units.copyOfRange(offset, offset + size), payload.copyOfRange(12, 12 + size))
            offset += size
        }
        assertEquals(0L, sink.dtsHdOverflowCount)
    }

    @Test
    fun sourceStaysUncommittedUntilItsLastBurst() {
        // The track takes the first burst and then fails: the whole source is replayed once to the
        // platform sink, with the caller's unit count.
        val inner = RecordingSink()
        val track = RecordingTrack(failAfterBursts = 1)
        val sink = openSink(track, inner)
        sink.handleBuffer(ByteBuffer.wrap(units, 0, unitSizes[0] + unitSizes[1]).slice(), 0L, 2)
        assertEquals(1, inner.buffers.size)
        assertEquals(unitSizes[0] + unitSizes[1], inner.buffers[0].first)
        assertEquals(2, inner.buffers[0].second)
    }

    @Test
    fun singleUnit_keepsTheOneBurstPath() {
        val track = RecordingTrack()
        val sink = openSink(track)
        sink.handleBuffer(ByteBuffer.wrap(units, 0, unitSizes[0]).slice(), 0L, 1)
        assertBursts(track, 1)
    }

    @Test
    fun corelessDtsUhdFrame_keepsTheOneBurstPath() {
        // DTS-UHD sync word 40 41 1B F2: not a core, so never split, whatever follows.
        val frame = ByteArray(6000)
        frame[0] = 0x40; frame[1] = 0x41; frame[2] = 0x1B; frame[3] = 0xF2.toByte()
        System.arraycopy(units, 0, frame, 100, 4188)
        val track = RecordingTrack()
        val sink = openSink(track)
        sink.handleBuffer(ByteBuffer.wrap(frame), 0L, 1)
        assertBursts(track, 1)
    }

    @Test
    fun coreOnlyFrames_keepTheOneBurstPath() {
        // Two plain cores and no ExSS: not the transcode's units.
        val cores = ByteArray(2 * 2012)
        System.arraycopy(units, 0, cores, 0, 2012)
        System.arraycopy(units, 4188, cores, 2012, 2012)
        val track = RecordingTrack()
        val sink = openSink(track)
        sink.handleBuffer(ByteBuffer.wrap(cores), 0L, 1)
        assertBursts(track, 1)
    }

    private fun openSink(track: IecAudioTrack, inner: RecordingSink = RecordingSink()): IecPassthroughAudioSink {
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = Factory(track))
        sink.configure(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(2).setSampleRate(48_000).build(),
            0,
            null
        )
        assertTrue(sink.isIecActive)
        sink.play()
        return sink
    }

    private fun assertBursts(track: RecordingTrack, count: Int) {
        val written = track.bytes()
        assertEquals(count * BURST, written.size)
        for (i in 0 until count) {
            val b = i * BURST
            assertEquals(0x72, written[b].toInt() and 0xFF)
            assertEquals(0xF8, written[b + 1].toInt() and 0xFF)
            assertEquals("type DTS-HD", 0x11, written[b + 4].toInt() and 0xFF)
            assertEquals("subtype 2 (period 2048)", 2, written[b + 5].toInt() and 0xFF)
        }
    }

    private fun unswap(data: ByteArray): ByteArray {
        val out = data.copyOf()
        var i = 0
        while (i + 1 < out.size) {
            val t = out[i]; out[i] = out[i + 1]; out[i + 1] = t
            i += 2
        }
        return out
    }

    private class RecordingTrack(private val failAfterBursts: Int = Int.MAX_VALUE) : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 4
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = 1 shl 20
        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()
        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            if (out.size().toLong() >= failAfterBursts.toLong() * BURST) return -2
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

    private class Factory(private val track: IecAudioTrack) : IecAudioTrackFactory {
        override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack = track
        override fun openHbr(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int,
            trueHd: Boolean
        ): IecAudioTrack = track
        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = true
        override fun iec61937Ready(): Boolean = true
        override fun iec61937ReadyAt(sampleRate: Int): Boolean = sampleRate == 192_000
    }

    private class RecordingSink : AudioSink {
        val buffers = mutableListOf<Pair<Int, Int>>()
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            buffers.add(buffer.remaining() to encodedAccessUnitCount)
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
        const val BURST = 8192
    }
}
