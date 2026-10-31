package com.nuvio.tv.core.player.dvmkv

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.DoviRpuBuilder
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionMatroskaTransformer
import com.nuvio.tv.core.player.DolbyVisionStatsSession
import com.nuvio.tv.core.player.DvRpuSource
import com.nuvio.tv.core.player.DvRpuSources
import com.nuvio.tv.core.player.DvSignallingDecision
import com.nuvio.tv.core.player.FakeHdr10PlusNative
import com.nuvio.tv.core.player.Hdr10PlusDvRpuSource
import com.nuvio.tv.core.player.HevcColourFixtures
import com.nuvio.tv.core.player.fakeAvgOfNal
import com.nuvio.tv.core.player.fakeHdr10PlusPayload
import com.nuvio.tv.core.player.fakeHdr10PlusSei
import com.nuvio.tv.core.player.fakeIsRefresh
import com.nuvio.tv.core.player.fakeLightLevelPayload
import com.nuvio.tv.core.player.fakeMasteringPayload
import com.nuvio.tv.core.player.fakeSeiNal
import com.nuvio.tv.core.player.fakeStaticSei
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HDR10+ → Dolby Vision 8.1 through the vendored Matroska extractor (ROADMAP DV3.1,
 * `docs/dv-conversion/DESIGN.md` 2.4), on a fake library ([FakeHdr10PlusNative]): which PQ tracks the early
 * scan converts, that each AU leaves as the native AU plus one RPU with the L1 of its HDR10+ payload,
 * and the scene refresh in presentation order across B-frames and seeks.
 */
class MatroskaHdr10PlusTest {

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

    private class LiveExtractor(transformer: MatroskaExtractor.DolbyVisionSampleTransformer?) :
        MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA, transformer) {
        var track: Track? = null

        init {
            MatroskaExtractor::class.java.getDeclaredField("tracks")
                .apply { isAccessible = true }
                .set(this, MapSparseArray<Any>())
        }

        override fun endMasterElement(id: Int) {
            val current = if (id == TRACK_ENTRY) getCurrentTrack(id) else null
            super.endMasterElement(id)
            if (current != null) track = current
        }
    }

    /** Collects each sample's bytes as the extractor commits it, and the Formats in order. */
    private class SampleCollector : TrackOutput {
        private val pending = ByteArrayOutputStream()
        val samples = mutableListOf<ByteArray>()
        val timesUs = mutableListOf<Long>()
        val formats = mutableListOf<Format>()
        val format: Format? get() = formats.lastOrNull()

        override fun format(format: Format) {
            formats += format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            pending.write(buffer, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            pending.write(buffer)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray()
            samples += bytes.copyOfRange(0, size)
            pending.reset()
            pending.write(bytes, size, bytes.size - size)
            timesUs += timeUs
        }
    }

    private val fake = FakeHdr10PlusNative()

    private val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x10)
    private val trail = byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x21)

    private val pqColour = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1))

    private fun sei(scene: Int, l1: Int = scene) = fakeHdr10PlusSei(fakeHdr10PlusPayload(scene, l1))

    /** Two Clusters, B-frame free, scenes 1 1 2 | 2 3 3; AU i at 40 ms * i. */
    private val clusters = listOf(
        cluster(0, block(0, true, sei(1), idr), block(40, false, sei(1), trail), block(80, false, sei(2), trail)),
        cluster(120, block(0, true, sei(2), idr), block(40, false, sei(3), trail), block(80, false, sei(3), trail)),
    )

    @Test
    fun `a PQ track with HDR10+ in its first AU converts as 8_1 with an RPU per AU from its own payload`() {
        val native = readFile(file(), transformer = null)
        val out = readFile(file(), transformer())
        assertEquals(MimeTypes.VIDEO_H265, native.format!!.sampleMimeType)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format!!.sampleMimeType)
        // 1920x1080 at 25 fps is above level 3 (24 fps): level 4.
        assertEquals("dvhe.08.04", out.format!!.codecs)
        assertEquals(C.COLOR_TRANSFER_ST2084, out.format!!.colorInfo!!.colorTransfer)
        // The provisional HEVC Format, then only the Dolby Vision one (the extractor sends it again later).
        val mimes = out.formats.map { it.sampleMimeType }
        assertEquals(MimeTypes.VIDEO_H265, mimes.first())
        assertTrue(mimes.drop(1).all { it == MimeTypes.VIDEO_DOLBY_VISION })
        assertEquals(DvSignallingDecision.Target.PROFILE_8_1, (out.track!!.dolbyVisionSignalling as DvSignallingDecision.Convert).target)
        assertEquals(native.timesUs, out.timesUs)
        for (i in native.samples.indices) {
            assertArrayEquals("AU $i", native.samples[i], out.samples[i].copyOfRange(0, native.samples[i].size))
        }
        // Frame 0 (stream start) and the two scene changes carry the refresh.
        assertEquals(listOf(true, false, true, false, true, false), out.rpus.map { fakeIsRefresh(it) })
        assertEquals(listOf(1001, 1001, 1002, 1002, 1003, 1003), out.rpus.map { fakeAvgOfNal(it) })
    }

    @Test
    fun `the 8_1 params come from the container's MasteringMetadata`() {
        // A P3 mastering display (MasteringMetadata chromaticities) in the BT.2020 container.
        val p3 = listOf(0x55D1 to 0.680f, 0x55D2 to 0.320f, 0x55D3 to 0.265f, 0x55D4 to 0.690f,
            0x55D5 to 0.150f, 0x55D6 to 0.060f, 0x55D7 to 0.3127f, 0x55D8 to 0.3290f)
            .map { (id, value) -> float(id, value) }
        val mastering = element(0x55D0, float(0x55D9, 4000f), float(0x55DA, 0.005f), *p3.toTypedArray())
        val colour = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1), uint(0x55BC, 1532), mastering)
        readFile(mkv(trackEntry(colour), *clusters.toTypedArray()), transformer())
        val params = fake.created.single()
        // MaxFALL is not stated: the default 400 stays below the stated MaxCLL 1532.
        assertEquals(DoviRpuBuilder.Level6(4000, 50, 1532, 400), params.level6)
        assertEquals(DoviRpuBuilder.Profile.P81(DoviRpuBuilder.SourceRange(50, 4000)), params.profile)
        assertEquals(DoviRpuBuilder.PRIMARIES_P3_D65, params.sourcePrimaryIndex)
    }

    @Test
    fun `a PQ track without HDR10+ in its first AU plays native, its samples untouched`() {
        val plain = mkv(
            trackEntry(pqColour),
            cluster(0, block(0, true, idr), block(40, false, sei(1), trail)),
        )
        val native = readFile(plain, transformer = null)
        val out = readFile(plain, transformer())
        assertEquals(MimeTypes.VIDEO_H265, out.format!!.sampleMimeType)
        assertEquals(
            DvSignallingDecision.Native("no RPU: no HDR10+ SEI in the hvcC or the first AU (static HDR10 is DV3.3)"),
            out.track!!.dolbyVisionSignalling
        )
        assertFalse(out.track!!.isDolbyVisionConverted)
        assertSamplesEqual(native.samples, out.samples)
        assertTrue(fake.created.isEmpty())
    }

    @Test
    fun `with the switch off or conversion off nothing is scanned and the track is untouched`() {
        val native = readFile(file(), transformer = null)
        for (transformer in listOf(transformer(hdr10Plus = false), transformer(convert = false))) {
            val out = readFile(file(), transformer)
            assertEquals(MimeTypes.VIDEO_H265, out.format!!.sampleMimeType)
            assertSamplesEqual(native.samples, out.samples)
        }
        assertEquals(0, fake.frames)
    }

    @Test
    fun `HDR10+ in the hvcC converts at track init`() {
        val hvcC = HevcColourFixtures.hvcC(
            32 to listOf(HevcColourFixtures.vps("pq")),
            33 to listOf(HevcColourFixtures.sps("pq")),
            34 to listOf(HevcColourFixtures.pps("pq")),
            39 to listOf(sei(5)),
        )
        val out = readFile(mkv(trackEntry(pqColour, hvcC = hvcC), cluster(0, block(0, true, idr))), transformer())
        // Published at track init as Dolby Vision (the extractor sends each Format again later).
        assertTrue(out.formats.all { it.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION })
        // The AU has no payload of its own: the hvcC's L1 carries it.
        assertEquals(listOf(1005), out.rpus.map { fakeAvgOfNal(it) })
    }

    @Test
    fun `a T35 message in the hvcC that is not HDR10+ still lets the first AU's HDR10+ convert the track`() {
        // The HDR10+ T.35 header with application identifier 5: a candidate the library refuses.
        val other = fakeHdr10PlusSei(byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x05, 0x01, 0x09, 0x09))
        val hvcC = HevcColourFixtures.hvcC(
            32 to listOf(HevcColourFixtures.vps("pq")),
            33 to listOf(HevcColourFixtures.sps("pq")),
            34 to listOf(HevcColourFixtures.pps("pq")),
            39 to listOf(other),
        )
        val out = readFile(mkv(trackEntry(pqColour, hvcC = hvcC), *clusters.toTypedArray()), transformer())
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format!!.sampleMimeType)
        assertEquals(listOf(1001, 1001, 1002, 1002, 1003, 1003), out.rpus.map { fakeAvgOfNal(it) })
    }

    @Test
    fun `B-frames take the refresh at the scene's first frame in presentation order`() {
        // Presentation 0 to 4 with scenes 1 1 2 2 2, read in decode order 0 4 2 1 3.
        val file = mkv(
            trackEntry(pqColour),
            cluster(
                0,
                block(0, true, sei(1), idr),
                block(160, false, sei(2), trail),
                block(80, false, sei(2), trail),
                block(40, false, sei(1), trail),
                block(120, false, sei(2), trail),
            ),
        )
        val out = readFile(file, transformer())
        val byTime = out.timesUs.zip(out.rpus).toMap()
        assertTrue("frame 0", fakeIsRefresh(byTime.getValue(0)))
        assertTrue("frame 2 starts scene 2", fakeIsRefresh(byTime.getValue(80_000)))
        assertFalse(fakeIsRefresh(byTime.getValue(40_000)))
        assertFalse(fakeIsRefresh(byTime.getValue(120_000)))
        assertEquals(listOf(1001, 1001, 1002, 1002, 1002), byTime.toSortedMap().values.map { fakeAvgOfNal(it) })
    }

    @Test
    fun `a seek gives the first AU no refresh of its own and keeps L1 per payload`() {
        // Cluster 1 opens with scene 4, after scene 2: without the reset reaching the provider, the
        // keys of the first pass would flag its first AU.
        val file = mkv(
            trackEntry(pqColour),
            cluster(0, block(0, true, sei(1), idr), block(40, false, sei(1), trail), block(80, false, sei(2), trail)),
            cluster(120, block(0, true, sei(4), idr), block(40, false, sei(3), trail), block(80, false, sei(3), trail)),
        )
        val extractor = LiveExtractor(transformer())
        val collector = SampleCollector()
        init(extractor, collector)
        readFrom(extractor, file, 0)
        collector.samples.clear()
        extractor.seek(clusterPosition(file, 1).toLong(), 120_000)
        readFrom(extractor, file, clusterPosition(file, 1))
        val rpus = collector.samples.map(::lastNal)
        assertEquals(listOf(false, true, false), rpus.map { fakeIsRefresh(it) })
        assertEquals(listOf(1004, 1003, 1003), rpus.map { fakeAvgOfNal(it) })
    }

    @Test
    fun `releasing the extractor frees the track's builder`() {
        val file = file()
        val extractor = LiveExtractor(transformer())
        init(extractor, SampleCollector())
        readFrom(extractor, file, 0)
        assertTrue(fake.released.isEmpty())
        extractor.release()
        assertEquals(1, fake.released.size)
    }

    private class Read(
        val formats: List<Format>,
        val samples: List<ByteArray>,
        val timesUs: List<Long>,
        val track: MatroskaExtractor.Track?,
    ) {
        val format: Format? get() = formats.lastOrNull()
        val rpus: List<ByteArray> get() = samples.map(::lastNal)
    }

    // ROADMAP DV3.4: the static metadata of the SEI, through the extractor (DESIGN.md 2.4).

    private val titleLevel6 = DoviRpuBuilder.Level6(1000, 1, 108, 53)
    private val defaultLevel6 = DoviRpuBuilder.Level6(1000, 50, 1000, 400)

    private fun hvcCWith(vararg seis: ByteArray) = HevcColourFixtures.hvcC(
        32 to listOf(HevcColourFixtures.vps("pq")),
        33 to listOf(HevcColourFixtures.sps("pq")),
        34 to listOf(HevcColourFixtures.pps("pq")),
        39 to seis.toList(),
    )

    /** One Cluster of two AUs, the first with [first] before its slice, both with HDR10+ scene 1. */
    private fun staticFile(hvcC: ByteArray, vararg first: ByteArray, colour: ByteArray = pqColour) = mkv(
        trackEntry(colour, hvcC = hvcC),
        cluster(0, block(0, true, *first, sei(1), idr), block(40, false, sei(1), trail)),
    )

    private fun assertEveryAuHasAnRpu(read: Read) {
        assertEquals(read.samples.size, read.rpus.size)
        assertTrue(read.rpus.all { fakeAvgOfNal(it) == 1001 })
    }

    @Test
    fun `a track the first-AU scan converts takes the SEI of that AU`() {
        val out = readFile(staticFile(HevcColourFixtures.hvcCOf("pq"), fakeStaticSei()), transformer())
        val params = fake.created.single()
        assertEquals(titleLevel6, params.level6)
        assertEquals(DoviRpuBuilder.Profile.P81(DoviRpuBuilder.SourceRange(1, 1000)), params.profile)
        assertEquals(DoviRpuBuilder.PRIMARIES_P3_D65, params.sourcePrimaryIndex)
        assertEveryAuHasAnRpu(out)
    }

    @Test
    fun `the first-AU scan also takes the SEI of the hvcC, the hvcC's mastering display first`() {
        val hvcC = hvcCWith(fakeSeiNal(137 to fakeMasteringPayload()))
        val au = fakeSeiNal(137 to fakeMasteringPayload(maxUnits = 40_000_000), 144 to fakeLightLevelPayload(108, 53))
        readFile(staticFile(hvcC, au), transformer())
        assertEquals(titleLevel6, fake.created.single().level6)
    }

    @Test
    fun `the SEI stays in the AU`() {
        val sei = fakeStaticSei()
        val file = staticFile(HevcColourFixtures.hvcCOf("pq"), sei)
        val native = readFile(file, transformer = null)
        val out = readFile(file, transformer())
        for (i in native.samples.indices) {
            assertArrayEquals(native.samples[i], out.samples[i].copyOfRange(0, native.samples[i].size))
        }
    }

    @Test
    fun `the container's values win, one MediaInfo field at a time`() {
        val mastering = element(0x55D0, float(0x55D9, 4000f), float(0x55DA, 0.005f))
        val colour = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1), uint(0x55BC, 500), mastering)
        readFile(staticFile(HevcColourFixtures.hvcCOf("pq"), fakeStaticSei(), colour = colour), transformer())
        assertEquals(DoviRpuBuilder.Level6(4000, 50, 500, 53), fake.created.single().level6)
        assertEquals(DoviRpuBuilder.PRIMARIES_P3_D65, fake.created.single().sourcePrimaryIndex)
    }

    @Test
    fun `a lone container LuminanceMax loses to the SEI's pair`() {
        val colour = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1), element(0x55D0, float(0x55D9, 4000f)))
        readFile(staticFile(HevcColourFixtures.hvcCOf("pq"), fakeStaticSei(), colour = colour), transformer())
        assertEquals(titleLevel6, fake.created.single().level6)
    }

    @Test
    fun `a track that converts at init takes the hvcC's SEI without a scan`() {
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1), fakeStaticSei())),
            cluster(0, block(0, true, idr), block(40, false, trail)),
        )
        val out = readFile(file, transformer())
        assertEquals(titleLevel6, fake.created.single().level6)
        assertTrue(out.formats.all { it.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION })
        assertEveryAuHasAnRpu(out)
    }

    @Test
    fun `a track that converts at init reads the first AU for what the hvcC leaves open`() {
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1))),
            cluster(0, block(0, true, fakeStaticSei(), idr), block(40, false, trail)),
        )
        val out = readFile(file, transformer())
        assertEquals(listOf(defaultLevel6, titleLevel6), fake.created.map { it.level6 })
        // The source of track init is released; no sample went through it.
        assertEquals(listOf(DoviRpuBuilder.Handle(1)), fake.released.filter { it.value == 1L })
        assertTrue(out.formats.all { it.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION })
        assertEquals(listOf(true, false), out.rpus.map { fakeIsRefresh(it) })
        assertEveryAuHasAnRpu(out)
    }

    @Test
    fun `a track that converts at init keeps its source when the first AU has no static SEI`() {
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1))),
            cluster(0, block(0, true, idr), block(40, false, trail)),
        )
        val out = readFile(file, transformer())
        assertEquals(listOf(defaultLevel6), fake.created.map { it.level6 })
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format!!.sampleMimeType)
        assertEveryAuHasAnRpu(out)
    }

    @Test
    fun `a track that converts at init stays converted when the scan cannot read the prefix`() {
        // A prefix past the 64 KiB the scan reads, with the static SEI behind it.
        val filler = fakeSeiNal(5 to ByteArray(200) { 7 }) + ByteArray(70_000) { 7 }
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1))),
            cluster(0, block(0, true, filler, fakeStaticSei(), idr), block(40, false, trail)),
        )
        val out = readFile(file, transformer())
        assertEquals(listOf(defaultLevel6), fake.created.map { it.level6 })
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format!!.sampleMimeType)
        assertEveryAuHasAnRpu(out)
    }

    @Test
    fun `a static SEI before a prefix the scan cannot finish still counts on a track converted at init`() {
        val filler = fakeSeiNal(5 to ByteArray(200) { 7 }) + ByteArray(70_000) { 7 }
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1))),
            cluster(0, block(0, true, fakeStaticSei(), filler, idr), block(40, false, trail)),
        )
        readFile(file, transformer())
        assertEquals(listOf(defaultLevel6, titleLevel6), fake.created.map { it.level6 })
    }

    @Test
    fun `a track that converts at init keeps its source when the new one fails`() {
        fake.failCreateFrom = 2
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1))),
            cluster(0, block(0, true, fakeStaticSei(), idr), block(40, false, trail)),
        )
        val out = readFile(file, transformer())
        assertEquals(listOf(defaultLevel6), fake.created.map { it.level6 })
        assertTrue(fake.released.isEmpty())
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format!!.sampleMimeType)
        assertEveryAuHasAnRpu(out)
    }

    @Test
    fun `a track that converts at init stays on one path when its first AU has HDR10+ and static SEI`() {
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1))),
            cluster(0, block(0, true, fakeStaticSei(), sei(1), idr), block(40, false, sei(1), trail)),
        )
        val out = readFile(file, transformer())
        assertEquals(listOf(defaultLevel6, titleLevel6), fake.created.map { it.level6 })
        assertEveryAuHasAnRpu(out)
    }

    @Test
    fun `each field left open sends a track converted at init to its first AU`() {
        val mastering = element(0x55D0, float(0x55D9, 4000f), float(0x55DA, 0.005f))
        fun pq(vararg more: ByteArray) = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1), *more)
        val mdcvNal = fakeSeiNal(137 to fakeMasteringPayload())
        val bothLevels = arrayOf(uint(0x55BC, 500), uint(0x55BD, 300))
        val auSei = fakeStaticSei()
        // (the colour, the hvcC's static SEI, the Level6 of the second source)
        val cases = listOf(
            Triple(pq(uint(0x55BC, 500), mastering), mdcvNal, DoviRpuBuilder.Level6(4000, 50, 500, 53)),
            Triple(pq(uint(0x55BD, 90), mastering), mdcvNal, DoviRpuBuilder.Level6(4000, 50, 108, 90)),
            Triple(pq(*bothLevels), fakeSeiNal(137 to fakeMasteringPayload(maxUnits = 0xFFFF_FFFFL)), DoviRpuBuilder.Level6(1000, 50, 500, 300)),
        )
        for ((colour, hvcCSei, expected) in cases) {
            fake.created.clear()
            val file = mkv(
                trackEntry(colour, hvcC = hvcCWith(sei(1), hvcCSei)),
                cluster(0, block(0, true, auSei, idr), block(40, false, trail)),
            )
            readFile(file, transformer())
            assertEquals(2, fake.created.size)
            // The first AU's mastering display cannot replace the hvcC's, which has primaries.
            assertEquals(expected, fake.created.last().level6)
        }
        // Luminance stated, primaries open.
        fake.created.clear()
        val noPrimaries = mkv(
            trackEntry(pq(*bothLevels, mastering), hvcC = hvcCWith(sei(1))),
            cluster(0, block(0, true, auSei, idr), block(40, false, trail)),
        )
        readFile(noPrimaries, transformer())
        assertEquals(listOf(DoviRpuBuilder.PRIMARIES_BT2020, DoviRpuBuilder.PRIMARIES_P3_D65), fake.created.map { it.sourcePrimaryIndex })
    }

    @Test
    fun `nothing is read again when the container and the hvcC state everything`() {
        val mastering = element(0x55D0, float(0x55D9, 4000f), float(0x55DA, 0.005f))
        val colour = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1), uint(0x55BC, 500), uint(0x55BD, 300), mastering)
        val file = mkv(
            trackEntry(colour, hvcC = hvcCWith(sei(1), fakeSeiNal(137 to fakeMasteringPayload()))),
            cluster(0, block(0, true, fakeStaticSei(maxCll = 900), idr), block(40, false, trail)),
        )
        readFile(file, transformer())
        assertEquals(listOf(DoviRpuBuilder.Level6(4000, 50, 500, 300)), fake.created.map { it.level6 })
    }

    @Test
    fun `with the switch off the RPU params and samples are today's, on both paths`() {
        val scanned = staticFile(HevcColourFixtures.hvcCOf("pq"), fakeStaticSei())
        val atInit = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1), fakeSeiNal(144 to fakeLightLevelPayload(108, 53)))),
            cluster(0, block(0, true, fakeStaticSei(), idr), block(40, false, trail)),
        )
        for (file in listOf(scanned, atInit)) {
            fake.created.clear()
            val native = readFile(file, transformer = null)
            val out = readFile(file, transformer(seiStatic = false))
            assertEquals(listOf(defaultLevel6), fake.created.map { it.level6 })
            assertEquals(DoviRpuBuilder.Profile.P81(DoviRpuBuilder.SourceRange(50, 1000)), fake.created.single().profile)
            assertEquals(DoviRpuBuilder.PRIMARIES_BT2020, fake.created.single().sourcePrimaryIndex)
            assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format!!.sampleMimeType)
            assertEveryAuHasAnRpu(out)
            for (i in native.samples.indices) {
                assertArrayEquals(native.samples[i], out.samples[i].copyOfRange(0, native.samples[i].size))
            }
        }
    }

    @Test
    fun `conversion off stays byte-identical with static SEI in the hvcC and the first AU`() {
        val file = mkv(
            trackEntry(pqColour, hvcC = hvcCWith(sei(1), fakeStaticSei())),
            cluster(0, block(0, true, fakeStaticSei(), idr), block(40, false, trail)),
        )
        val native = readFile(file, transformer = null)
        for (transformer in listOf(transformer(convert = false), transformer(hdr10Plus = false))) {
            val out = readFile(file, transformer)
            assertEquals(MimeTypes.VIDEO_H265, out.format!!.sampleMimeType)
            assertEquals(native.formats, out.formats)
            assertSamplesEqual(native.samples, out.samples)
        }
        assertTrue(fake.created.isEmpty())
    }

    @Test
    fun `a malformed static SEI costs nothing`() {
        val cut = fakeStaticSei().let { it.copyOf(it.size - 8) }
        val out = readFile(staticFile(HevcColourFixtures.hvcCOf("pq"), cut), transformer())
        assertEquals(defaultLevel6, fake.created.single().level6)
        assertEveryAuHasAnRpu(out)
    }

    private fun transformer(convert: Boolean = true, hdr10Plus: Boolean = true, seiStatic: Boolean = true) = DolbyVisionMatroskaTransformer(
        DolbyVisionConversionConfig(active = false, convertNonDolbyVision = convert, stats = DolbyVisionStatsSession()),
        hdr10PlusEnabled = hdr10Plus,
        seiStaticEnabled = seiStatic,
        rpuSource = DvRpuSources(
            { _, _ -> DvRpuSource.Prepared.Unavailable("no 8.4 in this test") },
            Hdr10PlusDvRpuSource(enabled = hdr10Plus, native = fake, seiStatic = seiStatic),
        ),
        refreshEveryStart = false,
    )

    private fun readFile(file: ByteArray, transformer: DolbyVisionMatroskaTransformer?): Read {
        val extractor = LiveExtractor(transformer)
        val collector = SampleCollector()
        init(extractor, collector)
        readFrom(extractor, file, 0)
        return Read(collector.formats, collector.samples, collector.timesUs, extractor.track)
    }

    /** Track 1 (the video) goes to [collector]. */
    private fun init(extractor: Extractor, collector: SampleCollector) {
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } answers { if (firstArg<Int>() == 1) collector else SampleCollector() }
        extractor.init(output)
    }

    /** Reads [file] from [start] to the end as one load. */
    private fun readFrom(extractor: Extractor, file: ByteArray, start: Int) {
        var position = start
        val reader = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= file.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, file.size - position)
                System.arraycopy(file, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val input = DefaultExtractorInput(reader, start.toLong(), file.size.toLong())
        val holder = PositionHolder()
        repeat(500) {
            if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return
        }
        error("the extractor did not reach the end")
    }

    private fun assertSamplesEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertArrayEquals("AU $i", expected[i], actual[i])
    }

    private fun file() = mkv(trackEntry(pqColour), *clusters.toTypedArray())

    /** The byte offset of Cluster [index] in [file], the position a Media3 seek hands the extractor. */
    private fun clusterPosition(file: ByteArray, index: Int): Int {
        val id = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        var found = -1
        for (i in 0..file.size - id.size) {
            if (file.copyOfRange(i, i + id.size).contentEquals(id) && ++found == index) return i
        }
        error("no Cluster $index")
    }

    private fun trackEntry(colour: ByteArray, hvcC: ByteArray = HevcColourFixtures.hvcCOf("pq")): ByteArray = element(
        TRACK_ENTRY,
        uint(0xD7, 1),
        uint(0x83, 1),
        element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
        element(0x63A2, hvcC),
        element(0xE0, uint(0xB0, 1920), uint(0xBA, 1080), colour),
        uint(0x23E383, 40_000_000L),
    )

    private fun cluster(timecodeMs: Int, vararg blocks: ByteArray) = element(0x1F43B675, uint(0xE7, timecodeMs), *blocks)

    /** A SimpleBlock of track 1 at [relativeMs] from its Cluster. */
    private fun block(relativeMs: Int, keyframe: Boolean, vararg nals: ByteArray) = element(
        0xA3,
        byteArrayOf(0x81.toByte(), (relativeMs ushr 8).toByte(), relativeMs.toByte(), if (keyframe) 0x80.toByte() else 0),
        lengthPrefixed(*nals)
    )

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            for (i in 3 downTo 0) out.write((nal.size ushr (8 * i)) and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun mkv(entry: ByteArray, vararg clusters: ByteArray): ByteArray {
        val header = element(
            0x1A45DFA3,
            element(0x4282, "matroska".toByteArray()),
            uint(0x4287, 4),
            uint(0x4285, 2)
        )
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = element(0x1654AE6B, entry)
        return header + element(0x18538067, info, tracks, *clusters)
    }

    private fun float(id: Int, value: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(value)
        return element(id, ByteArray(4) { i -> (bits ushr (8 * (3 - i))).toByte() })
    }

    private fun colour(vararg children: ByteArray) = element(0x55B0, *children)

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

    private companion object {
        const val TRACK_ENTRY = 0xAE

        /** The last NAL of an Annex B sample: the injected RPU. */
        fun lastNal(sample: ByteArray): ByteArray {
            for (i in sample.size - 4 downTo 0) {
                if (sample[i] == 0.toByte() && sample[i + 1] == 0.toByte() && sample[i + 2] == 0.toByte() && sample[i + 3] == 1.toByte()) {
                    return sample.copyOfRange(i + 4, sample.size)
                }
            }
            error("no start code")
        }
    }
}
