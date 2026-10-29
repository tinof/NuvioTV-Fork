package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
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
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionMatroskaTransformer
import com.nuvio.tv.core.player.DolbyVisionStatsSession
import com.nuvio.tv.core.player.DoviRpuBuilder
import com.nuvio.tv.core.player.DvRpuSource
import com.nuvio.tv.core.player.DvTrackFacts
import com.nuvio.tv.core.player.StaticTrackRpus
import com.nuvio.tv.core.player.DvSignallingDecision
import com.nuvio.tv.core.player.HdrColorSignalingExtractor
import com.nuvio.tv.core.player.HevcColourFixtures
import com.nuvio.tv.core.player.HlgDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signalling decision table of `docs/dv-conversion/DESIGN.md` 2.5 (ROADMAP DV2.3) through the vendored
 * Matroska extractor: the Format a Matroska HEVC track publishes, one test per row.
 */
class MatroskaDvSignallingTest {

    private class Extracted(
        val format: Format,
        val signalling: DvSignallingDecision?,
        val hlg: HlgDecision?,
        val requiresTransform: Boolean,
        val stats: DolbyVisionStatsSession,
    )

    private class Recorded(
        val signalling: DvSignallingDecision?,
        val hlg: HlgDecision?,
        val requiresTransform: Boolean,
    )

    /** Captures each Track as its TrackEntry ends: the JVM stub SparseArray keeps none. */
    private class RecordingExtractor(transformer: MatroskaExtractor.DolbyVisionSampleTransformer?) :
        MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA, transformer) {
        var recorded: Recorded? = null

        override fun endMasterElement(id: Int) {
            val track = if (id == TRACK_ENTRY) getCurrentTrack(id) else null
            super.endMasterElement(id)
            if (track != null) {
                recorded = Recorded(track.dolbyVisionSignalling, track.hlgDecision, track.requiresDolbyVisionTransform)
            }
        }
    }

    private val pqColour = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1))
    private val hlgColour = colour(uint(0x55BB, 9), uint(0x55BA, 18), uint(0x55B1, 9), uint(0x55B9, 1))

    @Test
    fun `PQ in the container and the SPS is Dolby Vision 8_1 with a PQ base layer`() {
        val out = extract("pq", pqColour, width = 3840, height = 2160, durationNs = 41_708_333)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format.sampleMimeType)
        assertEquals("dvhe.08.06", out.format.codecs)
        val colour = out.format.colorInfo!!
        assertEquals(C.COLOR_SPACE_BT2020, colour.colorSpace)
        assertEquals(C.COLOR_RANGE_LIMITED, colour.colorRange)
        assertEquals(C.COLOR_TRANSFER_ST2084, colour.colorTransfer)
        assertEquals(10, colour.lumaBitdepth)
        val convert = out.signalling as DvSignallingDecision.Convert
        assertEquals(DvSignallingDecision.Target.PROFILE_8_1, convert.target)
        assertEquals(emptyList<String>(), convert.untaggedAccepted)
        // The HLG decision is unchanged by the PQ row (DV2.4).
        assertEquals(HlgDecision.NotHlg, out.hlg)
        assertEquals(DvSignallingDecision.Target.PROFILE_8_1, out.stats.getConvertedTrackTarget())
        // The hvcC parameter sets stay the initialization data, as for the existing 8.1 output.
        val native = extract("pq", pqColour, transformer = null)
        assertEquals(native.format.initializationData.size, out.format.initializationData.size)
        for (i in native.format.initializationData.indices) {
            assertArrayEquals(native.format.initializationData[i], out.format.initializationData[i])
        }
    }

    @Test
    fun `PQ tagged in the SPS only is 8_1 and keeps the container's static HDR block`() {
        val out = extract("pq", colour(uint(0x55B2, 10), masteringMetadata()), width = 1920, height = 1080)
        assertEquals("dvhe.08.03", out.format.codecs)
        assertEquals(C.COLOR_TRANSFER_ST2084, out.format.colorInfo!!.colorTransfer)
        assertArrayEquals(HDR_STATIC_INFO_HEX.hexToBytes(), out.format.colorInfo!!.hdrStaticInfo)
    }

    @Test
    fun `PQ in the container over an untagged SPS is 8_1 with the untagged fields named`() {
        val out = extract("untagged10", colour(uint(0x55BA, 16)), width = 3840, height = 2160, durationNs = 16_683_333)
        assertEquals("dvhe.08.09", out.format.codecs)
        assertEquals(
            listOf("range", "matrix", "primaries"),
            (out.signalling as DvSignallingDecision.Convert).untaggedAccepted
        )
    }

    @Test
    fun `HLG is Dolby Vision 8_4 with the HLG base layer of the contract`() {
        val out = extract("hlg_tagged", hlgColour, width = 3840, height = 2160, durationNs = 20_000_000)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format.sampleMimeType)
        assertEquals("dvhe.08.09", out.format.codecs)
        val hlg = out.hlg as HlgDecision.Hlg84
        assertEquals(hlg.baseLayerColorInfo, out.format.colorInfo)
        assertEquals(C.COLOR_TRANSFER_HLG, out.format.colorInfo!!.colorTransfer)
        assertEquals(DvSignallingDecision.Target.PROFILE_8_4, (out.signalling as DvSignallingDecision.Convert).target)
        assertEquals(DvSignallingDecision.Target.PROFILE_8_4, out.stats.getConvertedTrackTarget())
    }

    @Test
    fun `a converted 8_4 Format keeps the container's static HDR block`() {
        val out = extract("hlg_tagged", colour(uint(0x55BA, 18), masteringMetadata()), width = 1920, height = 1080)
        assertEquals("dvhe.08.03", out.format.codecs)
        assertEquals(C.COLOR_TRANSFER_HLG, out.format.colorInfo!!.colorTransfer)
        assertArrayEquals(HDR_STATIC_INFO_HEX.hexToBytes(), out.format.colorInfo!!.hdrStaticInfo)
    }

    @Test
    fun `HLG over an untagged SPS is 8_4, its untagged fields accepted`() {
        val out = extract("untagged10", colour(uint(0x55BA, 18)), width = 1920, height = 1080, durationNs = 40_000_000)
        assertEquals("dvhe.08.04", out.format.codecs)
        assertEquals(C.COLOR_TRANSFER_HLG, out.format.colorInfo!!.colorTransfer)
        assertEquals(
            listOf("range", "matrix", "primaries"),
            (out.signalling as DvSignallingDecision.Convert).untaggedAccepted
        )
    }

    @Test
    fun `HLG with a contradicting range, matrix or primaries tag plays native`() {
        for (case in listOf("hlg_full_range", "hlg_matrix709", "hlg_prim709")) {
            val out = extract(case, null)
            assertNative(case, out)
            assertTrue(case, (out.signalling as DvSignallingDecision.Native).reason.startsWith("HLG refused"))
        }
        assertNative("container", extract("hlg_tagged", colour(uint(0x55BA, 18), uint(0x55B1, 1))))
    }

    @Test
    fun `PQ with a contradicting tag plays native`() {
        val out = extract("pq", colour(uint(0x55BA, 16), uint(0x55B9, 2)))
        assertNative("full range", out)
        assertEquals(
            DvSignallingDecision.Native("PQ refused: range full in the container, limited in the SPS"),
            out.signalling
        )
        assertNative("HLG in the SPS", extract("hlg_tagged", colour(uint(0x55BA, 16))))
    }

    @Test
    fun `a transfer unknown at track init plays native`() {
        val untagged = extract("untagged10", null)
        assertNative("untagged", untagged)
        assertEquals(DvSignallingDecision.Native("transfer unknown at track init"), untagged.signalling)
    }

    @Test
    fun `HLG in an alternative-transfer SEI of the hvcC is Dolby Vision 8_4 from track init`() {
        // DV2.10: the VUI of atc_sei states BT.2020 10-bit (14), its hvcC SEI HLG (18).
        val atc = extract("atc_sei", null)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, atc.format.sampleMimeType)
        assertEquals(C.COLOR_TRANSFER_HLG, atc.format.colorInfo!!.colorTransfer)
        assertEquals(DvSignallingDecision.Target.PROFILE_8_4, (atc.signalling as DvSignallingDecision.Convert).target)
        assertTrue(atc.requiresTransform)
    }

    @Test
    fun `an SDR transfer plays native`() {
        assertNative("bt709", extract("untagged10", colour(uint(0x55BA, 1))))
    }

    @Test
    fun `protected content plays native`() {
        val encryption = element(0x6D80, element(0x6240, element(0x5035, element(0x47E2, byteArrayOf(1, 2, 3, 4)))))
        val out = extract("pq", pqColour, extra = arrayOf(encryption))
        assertNative("encrypted", out)
        assertEquals(DvSignallingDecision.Native("protected content"), out.signalling)
    }

    @Test
    fun `with conversion off the track plays native and gets no decision`() {
        for (transformer in listOf(null, transformer(convert = false))) {
            val out = extract("pq", pqColour, transformer = transformer)
            assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
            assertTrue(out.format.codecs!!.startsWith("hvc1"))
            assertNull(out.signalling)
        }
        // Strip mode removes every RPU: it never converts, even with the gate set.
        val strip = DolbyVisionMatroskaTransformer(
            DolbyVisionConversionConfig(active = false, convertNonDolbyVision = true, stats = DolbyVisionStatsSession()),
            stripRpuOnly = true
        )
        assertNull(extract("pq", pqColour, transformer = strip).signalling)
    }

    @Test
    fun `a track that carries Dolby Vision keeps the existing path`() {
        // dvcC: version 1.0, profile 8, level 6, RPU and BL present, compatibility id 1.
        val dvcC = byteArrayOf(1, 0, 0x10, 0x35, 0x10) + ByteArray(19)
        val mapping = element(0x41E4, uint(0x41E7, 0x64766343L), element(0x41ED, dvcC))
        val out = extract("pq", pqColour, extra = arrayOf(mapping))
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format.sampleMimeType)
        assertEquals("dvhe.08.06", out.format.codecs)
        assertNull(out.signalling)
        assertNull(out.hlg)
        assertNull(out.stats.getConvertedTrackTarget())
    }

    @Test
    fun `without DefaultDuration the level is the one for 60 fps`() {
        assertEquals("dvhe.08.09", extract("pq", pqColour, width = 3840, height = 2160, durationNs = null).format.codecs)
        assertEquals("dvhe.08.06", extract("pq", pqColour, width = 3840, height = 2160, durationNs = 41_666_666).format.codecs)
    }

    @Test
    fun `a converted track takes the transform path, where its AUs get their RPUs`() {
        assertTrue(extract("pq", pqColour).requiresTransform)
        assertTrue(extract("hlg_tagged", hlgColour).requiresTransform)
        // A native track does not, unless the HDR10+ strip mode wants it.
        assertFalse(extract("pq", pqColour, transformer = transformer(convert = false)).requiresTransform)
        val hdr10Plus = DolbyVisionMatroskaTransformer(
            DolbyVisionConversionConfig(active = false, convertNonDolbyVision = true, stats = DolbyVisionStatsSession()),
            stripHdr10PlusSei = true,
            rpuSource = readySource,
        )
        val out = extract("pq", pqColour, transformer = hdr10Plus)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format.sampleMimeType)
        assertTrue(out.requiresTransform)
    }

    @Test
    fun `a track whose RPU cannot be written plays native`() {
        // DV2.7: the transformer is asked before the Format is built and can keep the track native.
        val noRpu = DolbyVisionMatroskaTransformer(
            DolbyVisionConversionConfig(active = false, convertNonDolbyVision = true, stats = DolbyVisionStatsSession()),
            rpuSource = { _, _ -> DvRpuSource.Prepared.Unavailable("test") },
        )
        for ((case, colour) in listOf("pq" to pqColour, "hlg_tagged" to hlgColour)) {
            val out = extract(case, colour, transformer = noRpu)
            assertNative(case, out)
            assertEquals(case, DvSignallingDecision.Native("no RPU: test"), out.signalling)
            assertFalse(case, out.requiresTransform)
        }
    }

    @Test
    fun `the HDR10 wrapper leaves a converted 8_4 Format HLG and an 8_1 Format PQ`() {
        val hlg = formatAfterHdrWrapper("hlg_tagged", hlgColour)
        assertEquals("dvhe.08.09", hlg.codecs)
        assertEquals(C.COLOR_TRANSFER_HLG, hlg.colorInfo!!.colorTransfer)
        val untaggedHlg = formatAfterHdrWrapper("untagged10", colour(uint(0x55BA, 18)))
        assertEquals(C.COLOR_TRANSFER_HLG, untaggedHlg.colorInfo!!.colorTransfer)
        val pq = formatAfterHdrWrapper("pq", pqColour)
        assertEquals(C.COLOR_TRANSFER_ST2084, pq.colorInfo!!.colorTransfer)
    }

    private fun assertNative(case: String, out: Extracted) {
        assertEquals(case, MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertTrue(case, out.format.codecs!!.startsWith("hvc1"))
        assertTrue(case, out.signalling is DvSignallingDecision.Native)
        assertNull(case, out.stats.getConvertedTrackTarget())
    }

    // The table is tested with a source that has an RPU for both targets; MatroskaRpuInjectionTest covers the real one.
    private val readySource = DvRpuSource { _, _ ->
        DvRpuSource.Prepared.Ready(
            StaticTrackRpus(DoviRpuBuilder.ShotRpu(byteArrayOf(0x7C, 0x01, 1), byteArrayOf(0x7C, 0x01, 2))),
            "test",
        )
    }

    private fun transformer(convert: Boolean = true) = DolbyVisionMatroskaTransformer(
        DolbyVisionConversionConfig(active = false, convertNonDolbyVision = convert, stats = DolbyVisionStatsSession()),
        rpuSource = readySource,
    )

    private fun trackEntry(case: String, colour: ByteArray?, width: Int, height: Int, durationNs: Long?, extra: Array<ByteArray>): ByteArray {
        val video = mutableListOf(uint(0xB0, width), uint(0xBA, height))
        if (colour != null) video += colour
        val children = mutableListOf(
            uint(0xD7, 1),
            uint(0x83, 1),
            element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
            element(0x63A2, HevcColourFixtures.hvcCOf(case)),
            element(0xE0, *video.toTypedArray()),
        )
        if (durationNs != null) children += uint(0x23E383, durationNs)
        children += extra
        return element(TRACK_ENTRY, *children.toTypedArray())
    }

    private fun extract(
        case: String,
        colour: ByteArray?,
        width: Int = 3840,
        height: Int = 2160,
        durationNs: Long? = 41_708_333,
        extra: Array<ByteArray> = emptyArray(),
        transformer: MatroskaExtractor.DolbyVisionSampleTransformer? = transformer(),
    ): Extracted {
        val stats = ((transformer as? DolbyVisionMatroskaTransformer)?.let { configOf(it).stats }) ?: DolbyVisionStatsSession()
        val extractor = RecordingExtractor(transformer)
        val formats = run(extractor, mkv(trackEntry(case, colour, width, height, durationNs, extra))) { extractor.recorded != null }
        val recorded = checkNotNull(extractor.recorded)
        return Extracted(formats.getValue(1), recorded.signalling, recorded.hlg, recorded.requiresTransform, stats)
    }

    private fun formatAfterHdrWrapper(case: String, colour: ByteArray): Format {
        val extractor = HdrColorSignalingExtractor(MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, transformer()))
        return run(extractor, mkv(trackEntry(case, colour, 3840, 2160, 20_000_000, emptyArray()))) { it.isNotEmpty() }.getValue(1)
    }

    private fun configOf(transformer: DolbyVisionMatroskaTransformer): DolbyVisionConversionConfig {
        val field = DolbyVisionMatroskaTransformer::class.java.getDeclaredField("config")
        field.isAccessible = true
        return field.get(transformer) as DolbyVisionConversionConfig
    }

    private fun run(extractor: Extractor, file: ByteArray, done: (Map<Int, Format>) -> Boolean): Map<Int, Format> {
        val formats = mutableMapOf<Int, Format>()
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } answers {
            val id = firstArg<Int>()
            val trackOutput = mockk<TrackOutput>(relaxed = true)
            val captured = slot<Format>()
            every { trackOutput.format(capture(captured)) } answers { formats[id] = captured.captured }
            trackOutput
        }
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
                if (formats.isNotEmpty() && done(formats)) return formats
                if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return formats
            }
        } catch (e: ParserException) {
            // android.util.SparseArray is a no-op stub in JVM tests, so the extractor finds no tracks when Tracks
            // ends; the format has been published by then.
            if (formats.isEmpty()) throw e
        }
        return formats
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
        private const val TRACK_ENTRY = 0xAE
        // CTA-861.3 static metadata of masteringMetadata() with the extractor's default MaxCLL 1000 and MaxFALL 200.
        private const val HDR_STATIC_INFO_HEX = "007c92d430d4307c926a18350c093d093de8030100e803c800"
    }
}
