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
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionMatroskaTransformer
import com.nuvio.tv.core.player.DolbyVisionStatsSession
import com.nuvio.tv.core.player.DoviRpuBuilder
import com.nuvio.tv.core.player.DvRpuSource
import com.nuvio.tv.core.player.DvTrackFacts
import com.nuvio.tv.core.player.StaticTrackRpus
import com.nuvio.tv.core.player.DvSignallingDecision
import com.nuvio.tv.core.player.HevcColourFixtures
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RPU injection of ROADMAP DV2.7 through the vendored Matroska extractor: a converted track's AUs
 * come out as the native track's AUs with one RPU NAL appended, and `scene_refresh_flag` follows
 * `docs/dv-conversion/DESIGN.md` 2.2 across starts, seeks and laced blocks.
 */
class MatroskaRpuInjectionTest {

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

    /** Collects each sample's bytes as the extractor commits it. */
    private class SampleCollector : TrackOutput {
        private val pending = ByteArrayOutputStream()
        val samples = mutableListOf<ByteArray>()
        val timesUs = mutableListOf<Long>()
        var format: Format? = null

        override fun format(format: Format) {
            this.format = format
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

    private val refreshRpu = byteArrayOf(0x7C, 0x01, 0x11, 0x22, 0x33, 0x44)
    private val plainRpu = byteArrayOf(0x7C, 0x01, 0x55, 0x66, 0x77)
    private val readySource = DvRpuSource { _, _ -> DvRpuSource.Prepared.Ready(StaticTrackRpus(DoviRpuBuilder.ShotRpu(refreshRpu, plainRpu)), "test") }

    private val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x10)
    private val trail = byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x21)
    private val sei = byteArrayOf(0x4E, 0x01, 0x89.toByte(), 0x08, 0x7C, 0x01)

    private val hlgColour = colour(uint(0x55BB, 9), uint(0x55BA, 18), uint(0x55B1, 9), uint(0x55B9, 1))
    private val pqColour = colour(uint(0x55BB, 9), uint(0x55BA, 16), uint(0x55B1, 9), uint(0x55B9, 1))

    /** Two Clusters of three AUs each, B-frame free: AU i is at 40 ms * i. */
    private val clusters = listOf(
        cluster(0, block(0, keyframe = true, sei, idr), block(40, keyframe = false, trail), block(80, keyframe = false, trail)),
        cluster(120, block(0, keyframe = true, idr), block(40, keyframe = false, trail), block(80, keyframe = false, sei, trail)),
    )

    @Test
    fun `a converted HLG track is the native track with one RPU per AU and scene refresh on frame 0 only`() {
        val native = readFile(file(hlgColour), transformer = null)
        val converted = readFile(file(hlgColour), transformer())
        assertEquals(MimeTypes.VIDEO_H265, native.format!!.sampleMimeType)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, converted.format!!.sampleMimeType)
        assertEquals(6, native.samples.size)
        assertEquals(native.timesUs, converted.timesUs)
        for (i in native.samples.indices) {
            val rpu = if (i == 0) refreshRpu else plainRpu
            assertArrayEquals("AU $i", native.samples[i] + annexB(rpu), converted.samples[i])
        }
    }

    @Test
    fun `with the switch the first AU of every start carries scene refresh too`() {
        val converted = readFile(file(hlgColour), transformer(refreshEveryStart = true))
        // From the start the first AU of the generation is frame 0; nothing else changes.
        assertEquals(listOf(true, false, false, false, false, false), refreshFlags(converted.samples))
    }

    @Test
    fun `a seek to a later Cluster carries 0 by default and 1 with the switch`() {
        for ((switch, expected) in listOf(false to listOf(false, false, false), true to listOf(true, false, false))) {
            val file = file(hlgColour)
            val extractor = LiveExtractor(transformer(refreshEveryStart = switch))
            val collector = SampleCollector()
            init(extractor, collector)
            readFrom(extractor, file, 0)
            assertEquals(6, collector.samples.size)
            collector.samples.clear()
            extractor.seek(clusterPosition(file, 1).toLong(), 120_000)
            readFrom(extractor, file, clusterPosition(file, 1))
            assertEquals("switch $switch", expected, refreshFlags(collector.samples))
        }
    }

    @Test
    fun `a seek back to the first Cluster reads frame 0 with scene refresh again`() {
        val file = file(hlgColour)
        val extractor = LiveExtractor(transformer())
        val collector = SampleCollector()
        init(extractor, collector)
        readFrom(extractor, file, 0)
        collector.samples.clear()
        extractor.seek(clusterPosition(file, 1).toLong(), 120_000)
        readFrom(extractor, file, clusterPosition(file, 1))
        collector.samples.clear()
        extractor.seek(clusterPosition(file, 0).toLong(), 0)
        readFrom(extractor, file, clusterPosition(file, 0))
        assertEquals(listOf(true, false, false, false, false, false), refreshFlags(collector.samples))
    }

    @Test
    fun `a start at a later Cluster carries no scene refresh, and frame 0 is unknown after it`() {
        val file = file(hlgColour)
        val extractor = LiveExtractor(transformer())
        val collector = SampleCollector()
        init(extractor, collector)
        // The header up to the first Cluster, then a resume position, as Media3 seeks after the SeekMap.
        readFrom(extractor, file, 0, end = clusterPosition(file, 0))
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, collector.format!!.sampleMimeType)
        assertTrue(collector.samples.isEmpty())
        extractor.seek(clusterPosition(file, 1).toLong(), 120_000)
        readFrom(extractor, file, clusterPosition(file, 1))
        assertEquals(listOf(false, false, false), refreshFlags(collector.samples))
        // The extractor never read from the start up to a Cluster, so it cannot tell the first one: the
        // flag is not guessed. The DV2.8 dump shows this; DESIGN.md 2.2 records it.
        collector.samples.clear()
        extractor.seek(clusterPosition(file, 0).toLong(), 0)
        readFrom(extractor, file, clusterPosition(file, 0))
        assertEquals(List(6) { false }, refreshFlags(collector.samples))
    }

    @Test
    fun `Media3's opening seek to 0 still finds frame 0`() {
        val file = file(hlgColour)
        val extractor = LiveExtractor(transformer())
        val collector = SampleCollector()
        init(extractor, collector)
        extractor.seek(0, 0)
        readFrom(extractor, file, 0)
        assertEquals(listOf(true, false, false, false, false, false), refreshFlags(collector.samples))
    }

    @Test
    fun `in a laced first block only the first frame carries scene refresh`() {
        // Fixed-size lacing (flags bit 0x04): two frames of equal size in one SimpleBlock.
        val laced = element(
            0xA3,
            byteArrayOf(0x81.toByte(), 0, 0, (0x80 or 0x04).toByte(), 1),
            lengthPrefixed(idr),
            lengthPrefixed(trail)
        )
        val file = mkv(trackEntry("hlg_tagged", hlgColour), cluster(0, laced, block(80, keyframe = false, trail)))
        val native = readFile(file, transformer = null)
        val converted = readFile(file, transformer())
        assertEquals(3, converted.samples.size)
        assertEquals(listOf(true, false, false), refreshFlags(converted.samples))
        for (i in native.samples.indices) {
            assertArrayEquals("AU $i", native.samples[i] + annexB(if (i == 0) refreshRpu else plainRpu), converted.samples[i])
        }
    }

    @Test
    fun `frame 0 carries scene refresh when the first Cluster holds no AU of the track`() {
        // A TS capture remuxed to MKV: the first Cluster holds only audio, the first IDR opens the second.
        val audio = element(
            TRACK_ENTRY,
            uint(0xD7, 2),
            uint(0x83, 2),
            element(0x86, "A_PCM/INT/LIT".toByteArray()),
            element(0xE1, uint(0x9F, 2), float(0xB5, 48_000f), uint(0x6264, 16))
        )
        val audioBlock = element(0xA3, byteArrayOf(0x82.toByte(), 0, 0, 0x80.toByte()), ByteArray(16) { 0x11 })
        val file = mkvTracks(
            listOf(trackEntry("hlg_tagged", hlgColour), audio),
            cluster(0, audioBlock),
            cluster(10, block(0, keyframe = true, sei, idr), block(40, keyframe = false, trail)),
            cluster(90, block(0, keyframe = true, idr)),
        )
        val extractor = LiveExtractor(transformer())
        val collector = SampleCollector()
        init(extractor, collector)
        readFrom(extractor, file, 0)
        assertEquals(listOf(true, false, false), refreshFlags(collector.samples))
        // A seek back to the start: the audio-only Cluster again, then frame 0.
        collector.samples.clear()
        extractor.seek(clusterPosition(file, 0).toLong(), 0)
        readFrom(extractor, file, clusterPosition(file, 0))
        assertEquals(listOf(true, false, false), refreshFlags(collector.samples))
    }

    @Test
    fun `an RPU that does not fit the track's NAL length field keeps the track native`() {
        val long = DvRpuSource { _, _ -> DvRpuSource.Prepared.Ready(StaticTrackRpus(DoviRpuBuilder.ShotRpu(ByteArray(416), ByteArray(417))), "test") }
        val transformer = transformer(source = long)
        val convert = DvSignallingDecision.Convert(
            DvSignallingDecision.Target.PROFILE_8_4,
            9,
            "dvhe.08.09",
            androidx.media3.common.ColorInfo.Builder().build(),
            emptyList(),
        )
        assertEquals(
            DvSignallingDecision.Native("no RPU: 416 bytes do not fit a 1-byte NAL length"),
            transformer.prepareDolbyVisionConversion(1, convert, 1, DvTrackFacts.NONE)
        )
        assertEquals(convert, transformer.prepareDolbyVisionConversion(1, convert, 2, DvTrackFacts.NONE))
        assertEquals(convert, transformer.prepareDolbyVisionConversion(1, convert, 4, DvTrackFacts.NONE))
    }

    @Test
    fun `an AU that already carries an RPU leaves with exactly one`() {
        val stray = byteArrayOf(0x7C, 0x01, 0x99.toByte())
        val file = mkv(trackEntry("hlg_tagged", hlgColour), cluster(0, block(0, keyframe = true, idr, stray)))
        val converted = readFile(file, transformer())
        assertArrayEquals(annexB(idr) + annexB(refreshRpu), converted.samples.single())
    }

    @Test
    fun `a PQ track without HDR10+ plays native with the real sources, its samples untouched`() {
        // DV3.1: 8.1 comes only from HDR10+ (MatroskaHdr10PlusTest); static HDR10 is DV3.3.
        val sources = com.nuvio.tv.core.player.DvRpuSources(
            com.nuvio.tv.core.player.StaticDvRpuSource(),
            com.nuvio.tv.core.player.Hdr10PlusDvRpuSource(),
        )
        val native = readFile(file(pqColour), transformer = null)
        val out = readFile(file(pqColour), transformer(source = sources))
        assertEquals(MimeTypes.VIDEO_H265, out.format!!.sampleMimeType)
        assertEquals(
            DvSignallingDecision.Native("no RPU: no HDR10+ SEI in the hvcC or the first AU (static HDR10 is DV3.3)"),
            out.track!!.dolbyVisionSignalling
        )
        assertFalse(out.track!!.isDolbyVisionConverted)
        assertSamplesEqual(native.samples, out.samples)
    }

    @Test
    fun `without the RPU library an HLG track plays native`() {
        // The JVM has no libnuvio_rpu.so: the real static source cannot write the 8.4 RPU.
        val out = readFile(file(hlgColour), transformer(source = com.nuvio.tv.core.player.StaticDvRpuSource()))
        assertEquals(MimeTypes.VIDEO_H265, out.format!!.sampleMimeType)
        assertEquals(
            DvSignallingDecision.Native("no RPU: static84L1 failed: UNAVAILABLE"),
            out.track!!.dolbyVisionSignalling
        )
        assertNull(out.stats.getConvertedTrackTarget())
        assertSamplesEqual(readFile(file(hlgColour), transformer = null).samples, out.samples)
    }

    @Test
    fun `with conversion off an HLG track is untouched`() {
        val native = readFile(file(hlgColour), transformer = null)
        val off = readFile(file(hlgColour), transformer(convert = false))
        assertEquals(MimeTypes.VIDEO_H265, off.format!!.sampleMimeType)
        assertFalse(off.track!!.requiresDolbyVisionTransform)
        assertSamplesEqual(native.samples, off.samples)
    }

    @Test
    fun `a track that carries Dolby Vision gets no injected RPU`() {
        // dvcC: version 1.0, profile 8, level 6, RPU and BL present, compatibility id 1.
        val dvcC = byteArrayOf(1, 0, 0x10, 0x35, 0x10) + ByteArray(19)
        val mapping = element(0x41E4, uint(0x41E7, 0x64766343L), element(0x41ED, dvcC))
        val file = mkv(trackEntry("pq", pqColour, mapping), *clusters.toTypedArray())
        val native = readFile(file, transformer = null)
        val out = readFile(file, transformer())
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format!!.sampleMimeType)
        assertNull(out.track!!.dolbyVisionSignalling)
        assertSamplesEqual(native.samples, out.samples)
    }

    @Test
    fun `the HDR10+ strip mode keeps the injected RPU`() {
        val transformer = DolbyVisionMatroskaTransformer(
            DolbyVisionConversionConfig(active = false, convertNonDolbyVision = true, stats = DolbyVisionStatsSession()),
            stripHdr10PlusSei = true,
            rpuSource = readySource,
            refreshEveryStart = false,
        )
        val native = readFile(file(hlgColour), transformer = null)
        val out = readFile(file(hlgColour), transformer)
        for (i in native.samples.indices) {
            assertArrayEquals("AU $i", native.samples[i] + annexB(if (i == 0) refreshRpu else plainRpu), out.samples[i])
        }
    }

    private class Read(val format: Format?, val samples: List<ByteArray>, val timesUs: List<Long>, val track: MatroskaExtractor.Track?, val stats: DolbyVisionStatsSession)

    private fun transformer(
        convert: Boolean = true,
        refreshEveryStart: Boolean = false,
        source: DvRpuSource = readySource,
    ) = DolbyVisionMatroskaTransformer(
        DolbyVisionConversionConfig(active = false, convertNonDolbyVision = convert, stats = DolbyVisionStatsSession()),
        rpuSource = source,
        refreshEveryStart = refreshEveryStart,
    )

    private fun readFile(file: ByteArray, transformer: DolbyVisionMatroskaTransformer?): Read {
        val extractor = LiveExtractor(transformer)
        val collector = SampleCollector()
        init(extractor, collector)
        readFrom(extractor, file, 0)
        val stats = transformer?.let { statsOf(it) } ?: DolbyVisionStatsSession()
        return Read(collector.format, collector.samples, collector.timesUs, extractor.track, stats)
    }

    private fun statsOf(transformer: DolbyVisionMatroskaTransformer): DolbyVisionStatsSession {
        val field = DolbyVisionMatroskaTransformer::class.java.getDeclaredField("config")
        field.isAccessible = true
        return (field.get(transformer) as DolbyVisionConversionConfig).stats
    }

    /** Track 1 (the video) goes to [collector], any other track to a sink. */
    private fun init(extractor: Extractor, collector: SampleCollector) {
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } answers { if (firstArg<Int>() == 1) collector else SampleCollector() }
        extractor.init(output)
    }

    /** Reads [file] from [start] to [end] (the end of the file by default) as one load. */
    private fun readFrom(extractor: Extractor, file: ByteArray, start: Int, end: Int = file.size) {
        var position = start
        val reader = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= end) return C.RESULT_END_OF_INPUT
                val count = minOf(length, end - position)
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

    /** Whether each sample's last NAL is the scene-refresh RPU (the injected NAL is always last). */
    private fun refreshFlags(samples: List<ByteArray>): List<Boolean> = samples.map { sample ->
        when {
            sample.endsWith(annexB(refreshRpu)) -> true
            sample.endsWith(annexB(plainRpu)) -> false
            else -> error("no injected RPU at the end of ${sample.toList()}")
        }
    }

    private fun assertSamplesEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertArrayEquals("AU $i", expected[i], actual[i])
    }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

    private fun file(colour: ByteArray) = mkv(trackEntry("hlg_tagged".takeIf { colour === hlgColour } ?: "pq", colour), *clusters.toTypedArray())

    /** The byte offset of Cluster [index] in [file], the position a Media3 seek hands the extractor. */
    private fun clusterPosition(file: ByteArray, index: Int): Int {
        val id = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        var found = -1
        for (i in 0..file.size - id.size) {
            if (file.copyOfRange(i, i + id.size).contentEquals(id) && ++found == index) return i
        }
        error("no Cluster $index")
    }

    private fun trackEntry(case: String, colour: ByteArray, vararg extra: ByteArray): ByteArray = element(
        TRACK_ENTRY,
        uint(0xD7, 1),
        uint(0x83, 1),
        element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
        element(0x63A2, HevcColourFixtures.hvcCOf(case)),
        element(0xE0, uint(0xB0, 1920), uint(0xBA, 1080), colour),
        uint(0x23E383, 40_000_000L),
        *extra
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

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun mkv(entry: ByteArray, vararg clusters: ByteArray): ByteArray = mkvTracks(listOf(entry), *clusters)

    private fun mkvTracks(entries: List<ByteArray>, vararg clusters: ByteArray): ByteArray {
        val header = element(
            0x1A45DFA3,
            element(0x4282, "matroska".toByteArray()),
            uint(0x4287, 4),
            uint(0x4285, 2)
        )
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = element(0x1654AE6B, *entries.toTypedArray())
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

    companion object {
        private const val TRACK_ENTRY = 0xAE
    }
}
