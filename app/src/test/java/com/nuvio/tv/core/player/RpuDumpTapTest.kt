package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RpuDumpTapTest {

    private class SourceExtractor : Extractor {
        lateinit var output: ExtractorOutput
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun sniff(input: ExtractorInput) = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = C.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    /** What the delegate track got: every byte per part, and each sample's metadata. */
    private class RecordingTrack : TrackOutput {
        val main = ByteArrayOutputStream()
        val side = ByteArrayOutputStream()
        val metadata = ArrayList<List<Any?>>()
        var format: Format? = null
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            if (read > 0) (if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) main else side).write(bytes, 0, read)
            return read
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            (if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) main else side).write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            metadata += listOf(timeUs, flags, size, offset, cryptoData)
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val tracks = HashMap<Int, RecordingTrack>()
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack() }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    private class Harness(firstAus: Int, reader: RpuSceneRefreshReader) {
        val lines = ArrayList<String>()
        val source = SourceExtractor()
        val downstream = RecordingOutput()
        val extractor: Extractor =
            RpuDumpTap.wrap(ExtractorsFactory { arrayOf(source) }, firstAus, reader) { lines += it }
                .createExtractors().single()

        init { extractor.init(downstream) }

        fun video(id: Int = 1, mime: String = MimeTypes.VIDEO_DOLBY_VISION, codecs: String = "dvhe.08.06") =
            source.output.track(id, C.TRACK_TYPE_VIDEO).also {
                it.format(Format.Builder().setSampleMimeType(mime).setCodecs(codecs).build())
            }

        val records get() = RpuDumpTestLines.parse(lines)
        val generations get() = lines.filter { " event=" in it }
    }

    /** `rpu[2] == 1` marks a scene refresh; counts the parses. */
    private class FlagReader(private val result: (ByteArray, Int) -> Boolean? = { nal, off -> nal[off + 2].toInt() == 1 }) :
        RpuSceneRefreshReader {
        var calls = 0
        override fun sceneRefresh(nal: ByteArray, offset: Int, length: Int): Boolean? {
            calls++
            return result(nal, offset)
        }
    }

    private val startCode = byteArrayOf(0, 0, 0, 1)

    /** RPU NAL: `7C 01`, the refresh marker, an id byte and the 0x80 stop byte. */
    private fun rpu(id: Int, refresh: Boolean = false) = byteArrayOf(0x7C, 0x01, if (refresh) 1 else 0, id.toByte(), 0x80.toByte())

    /** Annex B AU: a TRAIL_R slice (type 1), then the RPU. */
    private fun au(rpu: ByteArray?) = startCode + byteArrayOf(0x02, 0x01, 0x55, 0x66) + (rpu?.let { startCode + it } ?: byteArrayOf())

    private fun TrackOutput.write(sample: ByteArray, timeUs: Long, flags: Int = C.BUFFER_FLAG_KEY_FRAME) {
        sampleData(ParsableByteArray(sample.copyOf()), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        sampleMetadata(timeUs, flags, sample.size, 0, null)
    }

    @Test
    fun offReturnsTheDelegateItself() {
        val factory = ExtractorsFactory { arrayOf() }
        assertSame(factory, RpuDumpTap.wrap(factory, 0))
        assertSame(factory, RpuDumpTap.wrap(factory, -3))
        assertNotSame(factory, RpuDumpTap.wrap(factory, 1))
    }

    @Test
    fun firstAusOfEachGenerationThenRefreshOnly() {
        val h = Harness(firstAus = 2, FlagReader())
        val track = h.video()
        for (i in 0 until 5) track.write(au(rpu(i, refresh = i == 3)), i * 40_000L)
        h.extractor.seek(1_000, 5_000_000)
        for (i in 5 until 9) track.write(au(rpu(i, refresh = i == 8)), i * 40_000L)

        val records = h.records
        assertEquals(listOf(0, 1, 3, 5, 6, 8), records.map { it.nal[3].toInt() })
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), records.map { it.fields["seq"] })
        assertEquals(listOf("0", "0", "0", "1", "1", "1"), records.map { it.fields["gen"] })
        assertEquals(listOf("0", "1", "3", "0", "1", "3"), records.map { it.fields["au"] })
        assertEquals(listOf("start", "start", "refresh", "start", "start", "refresh"), records.map { it.fields["why"] })
        assertEquals(listOf("0", "0", "1", "0", "0", "1"), records.map { it.fields["sr"] })
        assertEquals(listOf("0", "40000", "120000", "200000", "240000", "320000"), records.map { it.fields["pts"] })
        assertEquals(
            listOf(
                "v1 gen=0 trk=1 event=init seekUs=-1 n=2 codecs=dvhe.08.06",
                "v1 gen=1 trk=1 event=seek seekUs=5000000 n=2 codecs=dvhe.08.06"
            ),
            h.generations
        )
    }

    @Test
    fun seekBeforeTheFirstAuStartsNoGeneration() {
        val h = Harness(firstAus = 1, FlagReader())
        h.extractor.seek(0, 0)
        val track = h.video()
        track.write(au(rpu(0)), 0)
        assertEquals(listOf("v1 gen=0 trk=1 event=seek seekUs=0 n=1 codecs=dvhe.08.06"), h.generations)
        assertEquals(listOf("0"), h.records.map { it.fields["gen"] })
    }

    @Test
    fun unreadableFlagKeepsOnlyTheFirstAus() {
        val h = Harness(firstAus = 1, FlagReader { _, _ -> null })
        val track = h.video()
        for (i in 0 until 4) track.write(au(rpu(i, refresh = true)), i * 40_000L)
        assertEquals(listOf("?"), h.records.map { it.fields["sr"] })
    }

    @Test
    fun repeatedRpuIsParsedOnce() {
        val reader = FlagReader()
        val h = Harness(firstAus = 10, reader)
        val track = h.video()
        repeat(3) { track.write(au(rpu(7)), it * 40_000L) }
        track.write(au(rpu(8)), 120_000)
        assertEquals(4, h.records.size)
        assertEquals(2, reader.calls)
    }

    @Test
    fun carriedOffsetBytesBelongToTheNextAu() {
        val h = Harness(firstAus = 5, FlagReader())
        val track = h.video()
        val first = au(rpu(1))
        val second = au(rpu(2))
        // The first AU's metadata comes after 6 bytes of the second, as a TS reader writes it.
        track.sampleData(ParsableByteArray(first + second.copyOfRange(0, 6)), first.size + 6, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, first.size, 6, null)
        track.sampleData(ParsableByteArray(second.copyOfRange(6, second.size)), second.size - 6, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(40_000, 0, second.size, 0, null)
        assertEquals(listOf(rpu(1).toList(), rpu(2).toList()), h.records.map { it.nal.toList() })
    }

    @Test
    fun matroskaSupplementalPartsAreNotTheAu() {
        val h = Harness(firstAus = 5, FlagReader())
        val track = h.video()
        val sample = au(rpu(3))
        val prefix = byteArrayOf(0, 0, 0, sample.size.toByte())
        val blockAdditional = byteArrayOf(0x7C, 0x01, 9, 9, 9, 0x80.toByte())
        track.sampleData(ParsableByteArray(prefix), 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleData(ParsableByteArray(blockAdditional), blockAdditional.size, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        val flags = C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA
        track.sampleMetadata(0, flags, 4 + sample.size + blockAdditional.size, 0, null)
        track.write(au(rpu(4)), 40_000)
        assertEquals(listOf(rpu(3).toList(), rpu(4).toList()), h.records.map { it.nal.toList() })
    }

    @Test
    fun seekDropsASampleCutOffMidWrite() {
        val h = Harness(firstAus = 1, FlagReader())
        val track = h.video()
        val cut = au(rpu(1))
        // Matroska's supplemental size prefix and half the main part, then the load is cancelled.
        track.sampleData(ParsableByteArray(byteArrayOf(0, 0, 0, cut.size.toByte())), 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        track.sampleData(ParsableByteArray(cut.copyOfRange(0, 7)), 7, TrackOutput.SAMPLE_DATA_PART_MAIN)
        h.extractor.seek(5_000, 2_000_000)
        val sample = au(rpu(2))
        track.sampleData(ParsableByteArray(byteArrayOf(0, 0, 0, sample.size.toByte())), 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(2_000_000, C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA, 4 + sample.size, 0, null)
        assertEquals(listOf(rpu(2).toList()), h.records.map { it.nal.toList() })
        assertEquals("seek", RpuDumpTestLines.fields(h.generations.single())["event"])
    }

    @Test
    fun seekClearsAnOverflowedSample() {
        val h = Harness(firstAus = 1, FlagReader())
        val track = h.video()
        val runaway = 16 * 1024 * 1024 + 1
        track.sampleData(ParsableByteArray(ByteArray(runaway)), runaway, TrackOutput.SAMPLE_DATA_PART_MAIN)
        h.extractor.seek(5_000, 2_000_000)
        track.write(au(rpu(3)), 2_000_000)
        assertEquals(listOf(rpu(3).toList()), h.records.map { it.nal.toList() })
    }

    @Test
    fun bytesReachTheDelegateUnchanged() {
        val h = Harness(firstAus = 2, FlagReader())
        val track = h.video()
        val expected = ByteArrayOutputStream()
        for (i in 0 until 4) {
            val sample = au(rpu(i, refresh = i % 2 == 1))
            expected.write(sample)
            // Through the DataReader path, in two reads.
            val reader = object : DataReader {
                var at = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = minOf(length, sample.size - at)
                    System.arraycopy(sample, at, buffer, offset, n)
                    at += n
                    return n
                }
            }
            assertEquals(3, track.sampleData(reader, 3, false, TrackOutput.SAMPLE_DATA_PART_MAIN))
            assertEquals(sample.size - 3, track.sampleData(reader, sample.size - 3, false, TrackOutput.SAMPLE_DATA_PART_MAIN))
            track.sampleMetadata(i * 40_000L, 0, sample.size, 0, null)
        }
        val downstream = h.downstream.tracks.getValue(1)
        assertArrayEquals(expected.toByteArray(), downstream.main.toByteArray())
        assertEquals((0 until 4).map { listOf(it * 40_000L, 0, au(rpu(it)).size, 0, null) }, downstream.metadata)
        assertEquals(listOf("0", "1", "3"), h.records.map { it.fields["au"] })
    }

    @Test
    fun aHugeRequestedLengthReadsAtMostOneChunk() {
        val h = Harness(firstAus = 1, FlagReader())
        val track = h.video()
        val source = ByteArray(200_000) { (it * 7).toByte() }
        var at = 0
        val reader = DataReader { buffer, offset, length ->
            val n = minOf(length, source.size - at)
            System.arraycopy(source, at, buffer, offset, n)
            at += n
            n
        }
        // A corrupt Matroska NAL length asks for almost 2 GB in one call.
        val first = track.sampleData(reader, Int.MAX_VALUE - 8, false, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals(64 * 1024, first)
        while (at < source.size) {
            assertTrue(track.sampleData(reader, Int.MAX_VALUE - 8, false, TrackOutput.SAMPLE_DATA_PART_MAIN) <= 64 * 1024)
        }
        assertArrayEquals(source, h.downstream.tracks.getValue(1).main.toByteArray())
    }

    @Test
    fun encryptedAuIsCountedButNotDumped() {
        val h = Harness(firstAus = 2, FlagReader())
        val track = h.video()
        track.write(au(rpu(0)), 0, C.BUFFER_FLAG_ENCRYPTED)
        track.write(au(rpu(1)), 40_000)
        track.write(au(rpu(2)), 80_000)
        assertEquals(listOf("1"), h.records.map { it.fields["au"] })
    }

    @Test
    fun lengthDelimitedSampleFallsBack() {
        val h = Harness(firstAus = 1, FlagReader())
        val track = h.video()
        val slice = byteArrayOf(0x02, 0x01, 0x55)
        val nal = rpu(5)
        val sample = byteArrayOf(0, 0, 0, slice.size.toByte()) + slice + byteArrayOf(0, 0, 0, nal.size.toByte()) + nal
        track.write(sample, 0)
        assertEquals(listOf(nal.toList()), h.records.map { it.nal.toList() })
    }

    @Test
    fun bytesBeforeTheFormatBelongToTheFirstAu() {
        // TS's H265Reader writes the AUD and parameter sets, then calls format() at the end of the PPS.
        val h = Harness(firstAus = 1, FlagReader())
        val track = h.source.output.track(1, C.TRACK_TYPE_VIDEO)
        val sample = au(rpu(0))
        track.sampleData(ParsableByteArray(sample.copyOfRange(0, 6)), 6, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153").build())
        track.sampleData(ParsableByteArray(sample.copyOfRange(6, sample.size)), sample.size - 6, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
        assertEquals(listOf(rpu(0).toList()), h.records.map { it.nal.toList() })
        assertEquals("0", h.records.single().fields["au"])
    }

    @Test
    fun bytesBeforeANonHevcFormatAreDropped() {
        val h = Harness(firstAus = 3, FlagReader())
        val track = h.source.output.track(1, C.TRACK_TYPE_VIDEO)
        val sample = au(rpu(0))
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.640028").build())
        track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
        track.write(au(rpu(1)), 40_000)
        assertEquals(emptyList<String>(), h.lines)
        assertArrayEquals(sample + au(rpu(1)), h.downstream.tracks.getValue(1).main.toByteArray())
    }

    @Test
    fun onlyHevcVideoIsTapped() {
        val h = Harness(firstAus = 3, FlagReader())
        val avc = h.video(id = 1, mime = MimeTypes.VIDEO_H264, codecs = "avc1.640028")
        avc.write(au(rpu(0)), 0)
        val audio = h.source.output.track(2, C.TRACK_TYPE_AUDIO)
        assertSame(h.downstream.tracks.getValue(2), audio)
        val hevc = h.video(id = 3, mime = MimeTypes.VIDEO_H265, codecs = "hvc1.2.4.L153")
        hevc.write(au(rpu(1)), 0)
        hevc.write(au(null), 40_000)
        assertEquals(listOf("3"), h.records.map { it.fields["trk"] })
        assertTrue(h.generations.single().endsWith("codecs=hvc1.2.4.L153"))
    }
}
