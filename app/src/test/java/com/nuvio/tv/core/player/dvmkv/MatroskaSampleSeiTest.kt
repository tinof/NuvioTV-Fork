package com.nuvio.tv.core.player.dvmkv

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.HevcColourFixtures
import com.nuvio.tv.core.player.HlgDecision
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The alternative-transfer SEI that an HEVC track carries in its first sample instead of its hvcC
 * (ROADMAP DV2.4): recorded and logged, never acted on.
 */
class MatroskaSampleSeiTest {

    /** The JVM stub SparseArray keeps nothing; the extractor needs one that does to reach a Cluster. */
    private class MapSparseArray<E> : SparseArray<E>() {
        private val map = sortedMapOf<Int, E>()
        override fun get(key: Int): E? = map[key]
        override fun put(key: Int, value: E) {
            map[key] = value
        }
        override fun size(): Int = map.size
        override fun valueAt(index: Int): E = map.values.elementAt(index)
    }

    private class LiveExtractor :
        MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA, null) {
        var track: Track? = null
        var decisionAtInit: HlgDecision? = null

        init {
            MatroskaExtractor::class.java.getDeclaredField("tracks")
                .apply { isAccessible = true }
                .set(this, MapSparseArray<Any>())
        }

        override fun endMasterElement(id: Int) {
            val current = if (id == TRACK_ENTRY) getCurrentTrack(id) else null
            super.endMasterElement(id)
            if (current != null) {
                track = current
                decisionAtInit = current.hlgDecision
            }
        }
    }

    private class CapturingTrackOutput : TrackOutput {
        val data = ByteArrayOutputStream()
        var samples = 0
        var format: Format? = null

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            data.write(buffer, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            this.data.write(buffer)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples++
        }
    }

    private val sei = HevcColourFixtures.sei("atc_sei")!!

    // An IDR_W_RADL slice header (NAL type 19) with a few payload bytes; the extractor does not decode it.
    private val slice = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x00, 0x10)

    @Test
    fun `an alternative-transfer SEI in the first sample is recorded without changing the decision`() {
        for (case in listOf("atc_sei", "hlg_tagged", "untagged10")) {
            val (extractor, output) = extract(HevcColourFixtures.hvcCOf(case, withSei = false), lengthPrefixed(sei, slice))
            val track = checkNotNull(extractor.track) { case }
            assertEquals(case, 1, output.samples)
            assertEquals(case, 18, track.hevcPreferredTransfer)
            assertEquals(case, extractor.decisionAtInit, track.hlgDecision)
            assertArrayEquals(case, annexB(sei, slice), output.data.toByteArray())
        }
    }

    @Test
    fun `a first sample without the SEI records none`() {
        val (extractor, output) = extract(HevcColourFixtures.hvcCOf("hlg_tagged", withSei = false), lengthPrefixed(slice))
        assertEquals(1, output.samples)
        assertNull(extractor.track!!.hevcPreferredTransfer)
        assertArrayEquals(annexB(slice), output.data.toByteArray())
    }

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            for (i in 3 downTo 0) out.write((nal.size ushr (8 * i)) and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun extract(hvcC: ByteArray, frame: ByteArray): Pair<LiveExtractor, CapturingTrackOutput> {
        val entry = element(
            TRACK_ENTRY,
            uint(0xD7, 1),
            uint(0x83, 1),
            element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
            element(0x63A2, hvcC),
            element(0xE0, uint(0xB0, 64), uint(0xBA, 64))
        )
        // SimpleBlock: track 1, relative timecode 0, keyframe.
        val block = element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()), frame)
        val cluster = element(0x1F43B675, uint(0xE7, 0), block)
        val file = mkv(entry, cluster)

        val extractor = LiveExtractor()
        val trackOutput = CapturingTrackOutput()
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } returns trackOutput
        extractor.init(output)
        var position = 0
        val reader = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= file.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, file.size - position)
                System.arraycopy(file, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val input = DefaultExtractorInput(reader, 0, file.size.toLong())
        val holder = PositionHolder()
        repeat(200) {
            if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return extractor to trackOutput
        }
        return extractor to trackOutput
    }

    private fun mkv(entry: ByteArray, cluster: ByteArray): ByteArray {
        val header = element(
            0x1A45DFA3,
            element(0x4282, "matroska".toByteArray()),
            uint(0x4287, 4),
            uint(0x4285, 2)
        )
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = element(0x1654AE6B, entry)
        return header + element(0x18538067, info, tracks, cluster)
    }

    private fun uint(id: Int, value: Long): ByteArray {
        val bytes = ByteArray(8) { i -> (value ushr (8 * (7 - i))).toByte() }
        return element(id, bytes.copyOfRange(bytes.indexOfFirst { it != 0.toByte() }.coerceIn(0, 7), 8))
    }

    private fun uint(id: Int, value: Int) = uint(id, value.toLong())

    private fun element(id: Int, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        val out = ByteArrayOutputStream()
        val idBytes = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        for (i in idBytes - 1 downTo 0) out.write((id ushr (8 * i)) and 0xFF)
        out.write(0x01)
        val size = body.size.toLong()
        for (i in 6 downTo 0) out.write(((size ushr (8 * i)) and 0xFF).toInt())
        out.write(body)
        return out.toByteArray()
    }

    companion object {
        private const val TRACK_ENTRY = 0xAE
    }
}
