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
        // DTS-HD is no longer "other": a core-led buffer is counted.
        assertEquals(2, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, buffer))
    }

    @Test
    fun dtsHdTranscodeUnits_fromTheHostTest_areCountedOneByOne() {
        val bytes = javaClass.getResourceAsStream("/dtshd/transcode_4units.dts")!!.use { it.readBytes() }
        assertEquals(16804, bytes.size)
        val walk = com.nuvio.tv.ui.screens.player.iec.DtsHdAccessUnits.walk(ByteBuffer.wrap(bytes))
        assertEquals(listOf(4188, 4216, 4200, 4200), walk.sizes.toList())
        assertEquals(4, walk.withExtension)
        assertEquals(4, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, ByteBuffer.wrap(bytes)))
        // 2 units, as an AAC-LC packet gives.
        val two = ByteBuffer.wrap(bytes, 0, 4188 + 4216).slice()
        assertEquals(2, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, two))
    }

    @Test
    fun dtsHdUnits_shortAndLongExssSizeForms() {
        val buffer = units(HdUnit(2012, 2204, longHeader = false), HdUnit(2012, 2300, longHeader = true))
        assertEquals(2, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, buffer))
        assertEquals(
            listOf(2012 + 2204, 2012 + 2300),
            com.nuvio.tv.ui.screens.player.iec.DtsHdAccessUnits.walk(buffer).sizes.toList()
        )
    }

    @Test
    fun dtsHdUnits_coreOnlyThenFull() {
        val buffer = units(HdUnit(2012, 0), HdUnit(2012, 2210))
        assertEquals(2, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, buffer))
    }

    @Test
    fun dtsHdUnits_truncatedExss_countsTheWholeUnitsOnly() {
        val whole = units(HdUnit(2012, 2204), HdUnit(2012, 2204))
        val cut = ByteBuffer.allocate(whole.limit() - 1000)
        for (i in 0 until cut.limit()) cut.put(i, whole.get(i))
        assertEquals(1, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, cut))
        // Cut inside the ExSS header itself.
        val inHeader = ByteBuffer.allocate(2012 + 6)
        for (i in 0 until inHeader.limit()) inHeader.put(i, whole.get(i))
        assertNull(EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, inHeader))
    }

    @Test
    fun dtsHdUnits_partlyConsumedBuffer_keepsItsCount() {
        val buffer = units(HdUnit(2012, 2204), HdUnit(2012, 2212), HdUnit(2012, 2216))
        buffer.position(5000)
        assertEquals(3, EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, buffer))
        assertEquals(5000, buffer.position())
    }

    @Test
    fun dtsHdBufferWithoutCore_isNotCounted() {
        // A coreless DTS-HD frame starts with the ExSS sync: the caller's count stays.
        val exssOnly = ByteBuffer.allocate(2204)
        writeExss(exssOnly, 0, 2204, longHeader = false)
        assertNull(EncodedFrameCountingAudioSink.countEncodedFrames(MimeTypes.AUDIO_DTS_HD, exssOnly))
    }

    @Test
    fun sink_passesTheUnitCountForDtsHd() {
        val inner = RecordingSink()
        val sink = EncodedFrameCountingAudioSink(inner)
        sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build(), 0, null)
        sink.handleBuffer(units(HdUnit(2012, 2204), HdUnit(2012, 2204), HdUnit(2012, 2204), HdUnit(2012, 2204)), 0L, 1)
        assertEquals(4, inner.lastCount)
    }

    private class HdUnit(val core: Int, val exss: Int, val longHeader: Boolean = false)

    private fun units(vararg list: HdUnit): ByteBuffer {
        val buffer = ByteBuffer.allocate(list.sumOf { ((it.core + 3) and 3.inv()) + it.exss })
        var offset = 0
        for (unit in list) {
            val core = dtsFrames(1, unit.core)
            for (i in 0 until 16) buffer.put(offset + i, core.get(i))
            offset += (unit.core + 3) and 3.inv()
            if (unit.exss > 0) {
                writeExss(buffer, offset, unit.exss, unit.longHeader)
                offset += unit.exss
            }
        }
        return buffer
    }

    /** ExSS sync, UserDefinedBits 0, nExtSSIndex 0, bHeaderSizeType, header size - 1, size - 1. */
    private fun writeExss(buffer: ByteBuffer, offset: Int, size: Int, longHeader: Boolean) {
        val bits = StringBuilder()
        fun put(count: Int, value: Int) {
            for (i in count - 1 downTo 0) bits.append(if ((value shr i) and 1 == 1) '1' else '0')
        }
        put(32, 0x64582025)
        put(8, 0)
        put(2, 0)
        put(1, if (longHeader) 1 else 0)
        if (longHeader) {
            put(12, 35)
            put(20, size - 1)
        } else {
            put(8, 35)
            put(16, size - 1)
        }
        while (bits.length % 8 != 0) bits.append('0')
        for (i in 0 until bits.length / 8) {
            buffer.put(offset + i, bits.substring(i * 8, i * 8 + 8).toInt(2).toByte())
        }
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
