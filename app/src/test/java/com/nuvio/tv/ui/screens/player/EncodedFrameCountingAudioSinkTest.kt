package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class EncodedFrameCountingAudioSinkTest {

    @Test
    fun dtsBuffers_countEveryCoreFrame() {
        for (frames in 1..4) {
            val buffer = dtsFrames(frames, DTS_1509K_FRAME_BYTES)
            assertEquals(frames, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS, buffer))
        }
    }

    @Test
    fun partlyConsumedBuffer_keepsItsCount() {
        val buffer = dtsFrames(3, DTS_1509K_FRAME_BYTES)
        buffer.position(DTS_1509K_FRAME_BYTES + 100)
        assertEquals(3, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS, buffer))
        assertEquals(DTS_1509K_FRAME_BYTES + 100, buffer.position())
    }

    @Test
    fun ac3At44kHz_countsFramesOfAlternatingSize() {
        // fscod 1 (44.1 kHz), frmsizecod 0x24/0x25 (640 kbps): 1393 and 1394 words.
        val sizes = listOf(2786, 2788, 2786)
        val buffer = ByteBuffer.allocate(sizes.sum())
        var offset = 0
        for ((i, size) in sizes.withIndex()) {
            buffer.put(offset, 0x0B)
            buffer.put(offset + 1, 0x77)
            buffer.put(offset + 4, ((1 shl 6) or (0x24 + i % 2)).toByte())
            buffer.put(offset + 5, (8 shl 3).toByte())
            offset += size
        }
        assertEquals(3, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_AC3, buffer))
    }

    @Test
    fun bufferWithoutSyncWord_isNotCounted() {
        val buffer = ByteBuffer.allocate(4096)
        assertNull(EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS, buffer))
        assertNull(EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_AC3, buffer))
    }

    @Test
    fun otherFormats_areNotCounted() {
        val buffer = dtsFrames(2, DTS_1509K_FRAME_BYTES)
        assertNull(EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_RAW, buffer))
        assertNull(EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_E_AC3, buffer))
        assertNull(EncodedFrameCountingAudioSink.countEncodedFrames(null, buffer))
    }

    @Test
    fun sink_passesTheCountForDtsAndTheCallersCountOtherwise() {
        val inner = RecordingSink()
        val sink = EncodedFrameCountingAudioSink(inner)

        sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build(), 0, null)
        sink.handleBuffer(dtsFrames(2, DTS_1509K_FRAME_BYTES), 0L, 1)
        assertEquals(2, inner.lastCount)

        sink.configure(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build(),
            0,
            null
        )
        sink.handleBuffer(dtsFrames(2, DTS_1509K_FRAME_BYTES), 0L, 1)
        assertEquals(1, inner.lastCount)
    }

    private fun dtsFrames(count: Int, frameBytes: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(count * frameBytes)
        val fsize = frameBytes - 1
        for (frame in 0 until count) {
            val offset = frame * frameBytes
            val header = intArrayOf(
                0x7F, 0xFE, 0x80, 0x01,
                // FTYPE 1, SHORT 31, CPF 0, then NBLKS 15 across bytes 4 and 5.
                0xFC,
                (15 and 0x3F shl 2) or ((fsize shr 12) and 0x03),
                (fsize shr 4) and 0xFF,
                (fsize and 0x0F) shl 4
            )
            for ((i, value) in header.withIndex()) buffer.put(offset + i, value.toByte())
        }
        return buffer
    }

    private class RecordingSink : AudioSink {
        var lastCount = -1
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
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
            lastCount = encodedAccessUnitCount
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
        // FFmpeg dca at 1509 kbit/s and 48 kHz writes 2012-byte frames.
        const val DTS_1509K_FRAME_BYTES = 2012
    }
}
