package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.ParserException
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.common.ColorInfo
import com.nuvio.tv.core.player.HevcColourFixtures
import com.nuvio.tv.core.player.HlgDecision
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the vendored extractor records on an HEVC track for the HLG contract (ROADMAP DV2.4). */
class MatroskaHlgContractTest {

    private class Recorded(
        val primaries: Int?,
        val transfer: Int?,
        val matrix: Int?,
        val range: Int?,
        val firstSps: ByteArray?,
        val preferredTransfer: Int?,
        val decision: HlgDecision?,
    )

    /** Captures each Track as its TrackEntry ends: the JVM stub SparseArray keeps none. */
    private class RecordingExtractor :
        MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA, null) {
        val recorded = mutableListOf<Recorded>()

        override fun endMasterElement(id: Int) {
            val track = if (id == TRACK_ENTRY) getCurrentTrack(id) else null
            super.endMasterElement(id)
            if (track != null) {
                recorded += Recorded(
                    track.containerColourPrimaries,
                    track.containerColourTransfer,
                    track.containerColourMatrix,
                    track.containerColourRange,
                    track.hevcFirstSps,
                    track.hevcPreferredTransfer,
                    track.hlgDecision,
                )
            }
        }
    }

    @Test
    fun `the raw Colour values are recorded as coded`() {
        val track = record(
            HevcColourFixtures.hvcCOf("hlg_tagged"),
            colour(uint(0x55B1, 9), uint(0x55B9, 1), uint(0x55BA, 18), uint(0x55BB, 9))
        )
        assertEquals(9, track.primaries)
        assertEquals(18, track.transfer)
        assertEquals(9, track.matrix)
        assertEquals(1, track.range)
    }

    @Test
    fun `unspecified and reserved codes are kept, absent elements are null`() {
        val coded = record(
            HevcColourFixtures.hvcCOf("hlg_tagged"),
            colour(uint(0x55B1, 2), uint(0x55B9, 3), uint(0x55BA, 2), uint(0x55BB, 0))
        )
        assertEquals(0, coded.primaries)
        assertEquals(2, coded.transfer)
        assertEquals(2, coded.matrix)
        assertEquals(3, coded.range)
        val transferOnly = record(HevcColourFixtures.hvcCOf("hlg_tagged"), colour(uint(0x55BA, 18)))
        assertNull(transferOnly.primaries)
        assertEquals(18, transferOnly.transfer)
        assertNull(transferOnly.matrix)
        assertNull(transferOnly.range)
        val none = record(HevcColourFixtures.hvcCOf("hlg_tagged"), null)
        assertNull(none.primaries)
        assertNull(none.transfer)
        assertNull(none.matrix)
        assertNull(none.range)
    }

    @Test
    fun `the first SPS of the hvcC is kept`() {
        for (case in HevcColourFixtures.cases) {
            assertArrayEquals(case, HevcColourFixtures.sps(case), record(HevcColourFixtures.hvcCOf(case), null).firstSps)
        }
        val noSps = HevcColourFixtures.hvcC(32 to listOf(HevcColourFixtures.vps("pq")), 34 to listOf(HevcColourFixtures.pps("pq")))
        assertNull(record(noSps, null).firstSps)
    }

    private val hlgBase = ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(C.COLOR_TRANSFER_HLG)
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .build()

    @Test
    fun `HLG tagged only in the SPS VUI is decided at track init`() {
        val track = record(HevcColourFixtures.hvcCOf("hlg_tagged"), null)
        assertEquals(HlgDecision.Hlg84(emptyList(), hlgBase), track.decision)
    }

    @Test
    fun `HLG tagged only in the container, with an untagged VUI, names the untagged fields`() {
        val track = record(HevcColourFixtures.hvcCOf("untagged10"), colour(uint(0x55BA, 18)))
        assertEquals(HlgDecision.Hlg84(listOf("range", "matrix", "primaries"), hlgBase), track.decision)
        val tagged = record(
            HevcColourFixtures.hvcCOf("untagged10"),
            colour(uint(0x55B1, 9), uint(0x55B9, 1), uint(0x55BA, 18), uint(0x55BB, 9))
        )
        assertEquals(HlgDecision.Hlg84(emptyList(), hlgBase), tagged.decision)
    }

    @Test
    fun `contradicting tags are refused at track init`() {
        assertEquals(
            HlgDecision.Refused("transfer 16 in the container, HLG in the SPS"),
            record(HevcColourFixtures.hvcCOf("hlg_tagged"), colour(uint(0x55BA, 16))).decision
        )
        assertEquals(
            HlgDecision.Refused("matrix 1 in the container, 9 in the SPS"),
            record(HevcColourFixtures.hvcCOf("hlg_tagged"), colour(uint(0x55B1, 1))).decision
        )
        assertEquals(
            HlgDecision.Refused("matrix 1 in the container"),
            record(HevcColourFixtures.hvcCOf("untagged10"), colour(uint(0x55BA, 18), uint(0x55B1, 1))).decision
        )
        assertEquals(
            HlgDecision.Refused("range full in the container, limited in the SPS"),
            record(HevcColourFixtures.hvcCOf("hlg_tagged"), colour(uint(0x55B9, 2))).decision
        )
        assertEquals(
            HlgDecision.Refused("range 4 in the container, not a Matroska Range code"),
            record(HevcColourFixtures.hvcCOf("hlg_tagged"), colour(uint(0x55B9, 4))).decision
        )
        assertEquals(
            HlgDecision.Refused("bit depth 8/8, not 10"),
            record(HevcColourFixtures.hvcCOf("hlg_8bit"), null).decision
        )
    }

    @Test
    fun `untagged and PQ tracks are not HLG`() {
        assertEquals(HlgDecision.TransferUnknown, record(HevcColourFixtures.hvcCOf("untagged10"), null).decision)
        assertEquals(HlgDecision.NotHlg, record(HevcColourFixtures.hvcCOf("pq"), null).decision)
        assertEquals(
            HlgDecision.NotHlg,
            record(HevcColourFixtures.hvcCOf("untagged10"), colour(uint(0x55BA, 16), uint(0x55BB, 9))).decision
        )
    }

    @Test
    fun `HLG in the container without an SPS is refused`() {
        val noSps = HevcColourFixtures.hvcC(32 to listOf(HevcColourFixtures.vps("pq")), 34 to listOf(HevcColourFixtures.pps("pq")))
        assertEquals(HlgDecision.Refused("no readable SPS"), record(noSps, colour(uint(0x55BA, 18))).decision)
    }

    @Test
    fun `the container's mastering metadata rides on the HLG base layer colour`() {
        val track = record(
            HevcColourFixtures.hvcCOf("hlg_tagged"),
            colour(
                uint(0x55BA, 18),
                uint(0x55BC, 1000),
                uint(0x55BD, 400),
                element(
                    0x55D0,
                    float(0x55D1, 0.75f), float(0x55D2, 0.25f),
                    float(0x55D3, 0.25f), float(0x55D4, 0.75f),
                    float(0x55D5, 0.125f), float(0x55D6, 0.0625f),
                    float(0x55D7, 0.3125f), float(0x55D8, 0.3125f),
                    float(0x55D9, 1000f), float(0x55DA, 0.5f)
                )
            )
        )
        val colorInfo = (track.decision as HlgDecision.Hlg84).baseLayerColorInfo
        assertEquals(C.COLOR_TRANSFER_HLG, colorInfo.colorTransfer)
        assertEquals(25, colorInfo.hdrStaticInfo!!.size)
    }

    @Test
    fun `the alternative-transfer SEI is recorded but never changes the decision`() {
        val cases = listOf(
            "atc_sei" to null,
            "atc_sei" to colour(uint(0x55BA, 18)),
            "hlg_tagged" to null,
            "untagged10" to null
        )
        for ((case, colour) in cases) {
            val sei = HevcColourFixtures.sei("atc_sei")!!
            val arrays = listOf(
                32 to listOf(HevcColourFixtures.vps(case)),
                33 to listOf(HevcColourFixtures.sps(case)),
                34 to listOf(HevcColourFixtures.pps(case))
            )
            val with = record(HevcColourFixtures.hvcC(*(arrays + (39 to listOf(sei))).toTypedArray()), colour)
            val without = record(HevcColourFixtures.hvcC(*arrays.toTypedArray()), colour)
            assertEquals(case, 18, with.preferredTransfer)
            assertNull(case, without.preferredTransfer)
            assertEquals(case, without.decision, with.decision)
        }
        // The VUI of atc_sei states BT.2020 10-bit (14); HLG only in the SEI stays not HLG.
        assertEquals(HlgDecision.NotHlg, record(HevcColourFixtures.hvcCOf("atc_sei"), null).decision)
    }

    @Test
    fun `a Dolby Vision track gets no HLG decision`() {
        // dvcC: version 1.0, profile 8, level 6, RPU and BL present, compatibility id 4.
        val dvcC = byteArrayOf(1, 0, 0x10, 0x35, 0x40) + ByteArray(19)
        val mapping = element(0x41E4, uint(0x41E7, 0x64766343L), element(0x41ED, dvcC))
        val track = record(HevcColourFixtures.hvcCOf("hlg_tagged"), colour(uint(0x55BA, 18)), mapping)
        assertNull(track.decision)
        assertTrue(track.firstSps != null)
    }

    private fun record(hvcC: ByteArray, colour: ByteArray?, vararg extra: ByteArray): Recorded {
        val video = mutableListOf(uint(0xB0, 64), uint(0xBA, 64))
        if (colour != null) video += colour
        val entry = element(
            TRACK_ENTRY,
            uint(0xD7, 1),
            uint(0x83, 1),
            element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
            element(0x63A2, hvcC),
            element(0xE0, *video.toTypedArray()),
            *extra
        )
        val extractor = RecordingExtractor()
        run(extractor, mkv(entry))
        return extractor.recorded.single()
    }

    private fun run(extractor: RecordingExtractor, file: ByteArray) {
        extractor.init(mockk<ExtractorOutput>(relaxed = true))
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
                if (extractor.recorded.isNotEmpty()) return
                if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return
            }
        } catch (e: ParserException) {
            // android.util.SparseArray is a no-op stub in JVM tests, so the extractor finds no tracks when Tracks
            // ends; the track has been recorded by then.
            if (extractor.recorded.isEmpty()) throw e
        }
    }

    private fun colour(vararg children: ByteArray) = element(0x55B0, *children)

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

    companion object {
        private const val TRACK_ENTRY = 0xAE
    }
}
