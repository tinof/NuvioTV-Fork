package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.HevcColourFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ColorInfo the vendored extractor publishes for an HEVC track, for every combination of the
 * Matroska Colour elements. The expected values are those of the extractor before DV2.4 (74d26d0a0): only
 * Primaries, BitsPerChannel and MasteringMetadata create a ColorInfo, so Transfer, Range or
 * MatrixCoefficients on their own publish none.
 */
class MatroskaColourFormatTest {

    private data class Row(
        val primaries: Long?,
        val transfer: Long?,
        val matrix: Long?,
        val range: Long?,
        val expected: ColorInfo?
    )

    @Test
    fun `HLG values publish the same ColorInfo as before`() {
        val nv = Format.NO_VALUE
        val bt2020 = C.COLOR_SPACE_BT2020
        val limited = C.COLOR_RANGE_LIMITED
        val hlg = C.COLOR_TRANSFER_HLG
        assertRows(
            Row(null, null, null, null, null),
            Row(9, null, null, null, ci(bt2020, nv, nv)),
            Row(null, 18, null, null, null),
            Row(null, null, 9, null, null),
            Row(null, null, null, 1, null),
            Row(9, 18, null, null, ci(bt2020, nv, hlg)),
            Row(9, null, 9, null, ci(bt2020, nv, nv)),
            Row(9, null, null, 1, ci(bt2020, limited, nv)),
            Row(null, 18, 9, null, null),
            Row(null, 18, null, 1, null),
            Row(null, null, 9, 1, null),
            Row(null, 18, 9, 1, null),
            Row(9, 18, 9, null, ci(bt2020, nv, hlg)),
            Row(9, 18, null, 1, ci(bt2020, limited, hlg)),
            Row(9, null, 9, 1, ci(bt2020, limited, nv)),
            Row(9, 18, 9, 1, ci(bt2020, limited, hlg))
        )
    }

    @Test
    fun `BT 709, PQ and full range values publish the same ColorInfo as before`() {
        val nv = Format.NO_VALUE
        val bt709 = C.COLOR_SPACE_BT709
        val full = C.COLOR_RANGE_FULL
        val pq = C.COLOR_TRANSFER_ST2084
        assertRows(
            Row(1, null, null, null, ci(bt709, nv, nv)),
            Row(null, 16, null, null, null),
            Row(null, null, 1, null, null),
            Row(null, null, null, 2, null),
            Row(1, 16, null, null, ci(bt709, nv, pq)),
            Row(1, null, 1, null, ci(bt709, nv, nv)),
            Row(1, null, null, 2, ci(bt709, full, nv)),
            Row(null, 16, 1, 2, null),
            Row(1, 16, 1, null, ci(bt709, nv, pq)),
            Row(1, 16, null, 2, ci(bt709, full, pq)),
            Row(1, null, 1, 2, ci(bt709, full, nv)),
            Row(1, 16, 1, 2, ci(bt709, full, pq))
        )
    }

    @Test
    fun `unspecified and reserved values publish the same ColorInfo as before`() {
        val nv = Format.NO_VALUE
        assertRows(
            Row(2, null, null, null, ci(nv, nv, nv)),
            Row(null, 2, null, null, null),
            Row(null, null, 2, null, null),
            Row(null, null, null, 0, null),
            Row(null, null, null, 3, null),
            Row(2, 2, 2, 0, ci(nv, nv, nv)),
            Row(2, 2, 2, 3, ci(nv, nv, nv)),
            Row(9, 14, 10, 3, ci(C.COLOR_SPACE_BT2020, nv, nv)),
            Row(9, 1, 0, 1, ci(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_SDR))
        )
    }

    @Test
    fun `bits per channel and mastering metadata publish the same ColorInfo as before`() {
        val nv = Format.NO_VALUE
        val bits = colour(uint(0x55B2, 10))
        assertEquals(
            ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build(),
            colorInfoOf(bits)
        )
        val hdr = HDR_STATIC_INFO_HEX.hexToBytes()
        assertEquals(
            ci(nv, nv, C.COLOR_TRANSFER_HLG, hdr = hdr),
            colorInfoOf(colour(uint(0x55BA, 18), uint(0x55BC, 1000), uint(0x55BD, 400), masteringMetadata()))
        )
        assertEquals(
            ci(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_ST2084, bits = 10, hdr = hdr),
            colorInfoOf(
                colour(
                    uint(0x55B2, 10),
                    uint(0x55B1, 9),
                    uint(0x55B9, 1),
                    uint(0x55BA, 16),
                    uint(0x55BB, 9),
                    uint(0x55BC, 1000),
                    uint(0x55BD, 400),
                    masteringMetadata()
                )
            )
        )
    }

    @Test
    fun `the hvcC parameter sets do not change the published ColorInfo`() {
        // The SPS VUI of every fixture is ignored for the Format: the container decides it today.
        for (case in HevcColourFixtures.cases) {
            val format = formatOf(HevcColourFixtures.hvcCOf(case), video())
            assertEquals(case, null, format.colorInfo)
            assertEquals(case, MimeTypes.VIDEO_H265, format.sampleMimeType)
        }
    }

    private fun assertRows(vararg rows: Row) {
        for (row in rows) {
            val elements = mutableListOf<ByteArray>()
            row.matrix?.let { elements += uint(0x55B1, it) }
            row.range?.let { elements += uint(0x55B9, it) }
            row.transfer?.let { elements += uint(0x55BA, it) }
            row.primaries?.let { elements += uint(0x55BB, it) }
            val colour = if (elements.isEmpty()) null else colour(*elements.toTypedArray())
            assertEquals(row.toString(), row.expected, colorInfoOf(colour))
        }
    }

    private fun ci(space: Int, range: Int, transfer: Int, bits: Int = Format.NO_VALUE, hdr: ByteArray? = null) =
        ColorInfo.Builder()
            .setColorSpace(space)
            .setColorRange(range)
            .setColorTransfer(transfer)
            .setHdrStaticInfo(hdr)
            .setLumaBitdepth(bits)
            .setChromaBitdepth(bits)
            .build()

    private fun colorInfoOf(colour: ByteArray?): ColorInfo? {
        val format = formatOf(HevcColourFixtures.hvcCOf("hlg_tagged"), video(colour))
        assertEquals(MimeTypes.VIDEO_H265, format.sampleMimeType)
        return format.colorInfo
    }

    private fun video(colour: ByteArray? = null): ByteArray {
        val parts = mutableListOf(uint(0xB0, 64), uint(0xBA, 64))
        if (colour != null) parts += colour
        return element(0xE0, *parts.toTypedArray())
    }

    private fun colour(vararg children: ByteArray) = element(0x55B0, *children)

    private fun masteringMetadata() = element(
        0x55D0,
        float(0x55D1, 0.75f), float(0x55D2, 0.25f),
        float(0x55D3, 0.25f), float(0x55D4, 0.75f),
        float(0x55D5, 0.125f), float(0x55D6, 0.0625f),
        float(0x55D7, 0.3125f), float(0x55D8, 0.3125f),
        float(0x55D9, 1000f), float(0x55DA, 0.5f)
    )

    private fun formatOf(hvcC: ByteArray, video: ByteArray): Format {
        val entry = element(
            0xAE,
            uint(0xD7, 1),
            uint(0x83, 1),
            element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
            element(0x63A2, hvcC),
            video
        )
        return extractFormats(mkv(entry)).getValue(1)
    }

    private fun extractFormats(file: ByteArray): Map<Int, Format> {
        val formats = mutableMapOf<Int, Format>()
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } answers {
            val id = firstArg<Int>()
            val trackOutput = mockk<TrackOutput>(relaxed = true)
            val captured = slot<Format>()
            every { trackOutput.format(capture(captured)) } answers { formats[id] = captured.captured }
            trackOutput
        }
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, null)
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
        try {
            repeat(50) {
                if (formats.isNotEmpty()) return formats
                if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return formats
            }
        } catch (e: ParserException) {
            // android.util.SparseArray is a no-op stub in JVM tests, so the extractor finds no tracks when Tracks
            // ends; the format has been published by then.
            if (formats.isEmpty()) throw e
        }
        assertEquals(1, formats.size)
        return formats
    }

    private fun mkv(vararg trackEntries: ByteArray): ByteArray {
        val header = element(
            0x1A45DFA3,
            element(0x4282, "matroska".toByteArray()),
            uint(0x4287, 4),
            uint(0x4285, 2)
        )
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = element(0x1654AE6B, *trackEntries)
        return header + element(0x18538067, info, tracks)
    }

    private fun uint(id: Int, value: Long): ByteArray {
        val bytes = ByteArray(8) { i -> (value ushr (8 * (7 - i))).toByte() }
        return element(id, bytes.copyOfRange(bytes.indexOfFirst { it != 0.toByte() }.coerceIn(0, 7), 8))
    }

    private fun uint(id: Int, value: Int) = uint(id, value.toLong())

    private fun float(id: Int, value: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(value)
        return element(id, ByteArray(4) { i -> (bits ushr (8 * (3 - i))).toByte() })
    }

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

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    companion object {
        // CTA-861.3 static metadata of masteringMetadata() with MaxCLL 1000 and MaxFALL 400, little endian.
        private const val HDR_STATIC_INFO_HEX = "007c92d430d4307c926a18350c093d093de8030100e8039001"
    }
}
