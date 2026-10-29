package com.nuvio.tv.core.player.dvmkv

import android.text.TextUtils
import android.util.Log
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
import androidx.media3.extractor.SeekMap
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
import com.nuvio.tv.core.player.HlgDecision
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * ROADMAP DV2.10 through the vendored Matroska extractor: HLG signalled only in the alternative-transfer
 * SEI of the first AU (BBC broadcast HLG: the SPS states BT.2020 10-bit) is read by the early scan
 * before the formats are published, so the track is Dolby Vision 8.4 from its first sample; every case
 * the scan cannot read stays native.
 */
class MatroskaSeiHlgScanTest {

    /** The extractor's android.util.Log.i lines. */
    private val logs = mutableListOf<String>()

    private val scanLines: List<String> get() = logs.filter { it.startsWith("HLG SEI scan") }

    @Before
    fun captureLogs() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } answers {
            logs += secondArg<String>()
            0
        }
    }

    @After
    fun releaseLogs() {
        unmockkStatic(Log::class)
    }

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
            if (current != null && current.number == 1) track = current
        }
    }

    /** Every format, endTracks and sample in the order the extractor emits them. */
    private class Recorder : ExtractorOutput {
        val events = mutableListOf<String>()
        val outputs = HashMap<Int, Collector>()

        override fun track(id: Int, type: Int): TrackOutput = outputs.getOrPut(id) { Collector(id, events) }

        override fun endTracks() {
            events += "endTracks"
        }

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class Collector(private val id: Int, private val events: MutableList<String>) : TrackOutput {
        private val pending = ByteArrayOutputStream()
        val samples = mutableListOf<ByteArray>()
        var format: Format? = null

        override fun format(format: Format) {
            this.format = format
            events += "format $id ${format.sampleMimeType}"
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
            events += "sample $id"
        }
    }

    private class Read(val recorder: Recorder, val track: MatroskaExtractor.Track?, val stats: DolbyVisionStatsSession) {
        val video: Collector get() = recorder.outputs.getValue(1)
        val format: Format get() = video.format!!
        val samples: List<ByteArray> get() = video.samples
    }

    private val refreshRpu = byteArrayOf(0x7C, 0x01, 0x11, 0x22, 0x33, 0x44)
    private val plainRpu = byteArrayOf(0x7C, 0x01, 0x55, 0x66, 0x77)
    private val readySource = DvRpuSource { _, _ -> DvRpuSource.Prepared.Ready(StaticTrackRpus(DoviRpuBuilder.ShotRpu(refreshRpu, plainRpu)), "test") }

    private val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x10)
    private val trail = byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x21)

    /** atc_sei's prefix SEI: alternative_transfer_characteristics, preferred transfer 18 (HLG). */
    private val hlgSei = HevcColourFixtures.sei("atc_sei")!!

    /** The same SEI saying 14: not HLG. */
    private val bt2020Sei = byteArrayOf(0x4E, 0x01, 0x93.toByte(), 0x01, 0x0E, 0x80.toByte())

    /** An HLG-compatible track: the SPS of atc_sei (BT.2020 10-bit transfer), no SEI in the hvcC, no Colour. */
    private fun videoEntry(vararg extra: ByteArray) = element(
        TRACK_ENTRY,
        uint(0xD7, 1),
        uint(0x83, 1),
        element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
        element(0x63A2, HevcColourFixtures.hvcCOf("atc_sei", withSei = false)),
        element(0xE0, uint(0xB0, 1920), uint(0xBA, 1080)),
        uint(0x23E383, 40_000_000L),
        *extra
    )

    private val audioEntry = element(
        TRACK_ENTRY,
        uint(0xD7, 2),
        uint(0x83, 2),
        element(0x86, "A_PCM/INT/LIT".toByteArray()),
        element(0xE1, float(0xB5, 48_000f), uint(0x9F, 2), uint(0x6264, 16)),
    )

    /** Two Clusters of three AUs each; the HLG SEI in every IRAP AU, as BBC broadcast HLG has it. */
    private val clusters = listOf(
        cluster(0, block(0, keyframe = true, hlgSei, idr), block(40, keyframe = false, trail), block(80, keyframe = false, trail)),
        cluster(120, block(0, keyframe = true, hlgSei, idr), block(40, keyframe = false, trail), block(80, keyframe = false, trail)),
    )

    @Test
    fun `an HLG SEI in the first block converts the track before the formats are published`() {
        val native = read(mkv(listOf(videoEntry()), clusters), transformer = null)
        val converted = read(mkv(listOf(videoEntry()), clusters))
        assertEquals(MimeTypes.VIDEO_H265, native.format.sampleMimeType)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, converted.format.sampleMimeType)
        assertTrue(converted.format.codecs!!.startsWith("dvhe.08."))
        assertEquals(C.COLOR_TRANSFER_HLG, converted.format.colorInfo!!.colorTransfer)
        val track = converted.track!!
        assertTrue((track.hlgDecision as HlgDecision.Hlg84).transferFromSei)
        assertEquals(DvSignallingDecision.Target.PROFILE_8_4, (track.dolbyVisionSignalling as DvSignallingDecision.Convert).target)
        assertEquals(DvSignallingDecision.Target.PROFILE_8_4, converted.stats.getConvertedTrackTarget())
        assertEquals(18, track.hevcPreferredTransfer)
        assertFalse(track.waitingForHlgSeiScan)
        // The provisional HEVC Format, then the Dolby Vision one, before endTracks and every sample.
        val events = converted.recorder.events
        val dolbyVision = events.indexOf("format 1 ${MimeTypes.VIDEO_DOLBY_VISION}")
        assertEquals("format 1 ${MimeTypes.VIDEO_H265}", events.first { it.startsWith("format 1") })
        assertTrue(events.toString(), dolbyVision in 0 until events.indexOf("endTracks"))
        assertTrue(events.toString(), dolbyVision < events.indexOf("sample 1"))
        assertEquals(
            listOf(MimeTypes.VIDEO_DOLBY_VISION),
            events.drop(dolbyVision).filter { it.startsWith("format 1") }.map { it.substringAfterLast(' ') }.distinct()
        )
        // Every AU is the native AU with one RPU, scene refresh on frame 0 only.
        assertEquals(6, converted.samples.size)
        for (i in native.samples.indices) {
            val rpu = if (i == 0) refreshRpu else plainRpu
            assertArrayEquals("AU $i", native.samples[i] + annexB(rpu), converted.samples[i])
        }
    }

    @Test
    fun `an audio block before the first video block is skipped by the scan`() {
        val audio = element(0xA3, byteArrayOf(0x82.toByte(), 0, 0, 0x80.toByte()), ByteArray(64))
        val first = cluster(0, audio, block(0, keyframe = true, hlgSei, idr), block(40, keyframe = false, trail))
        val out = read(mkv(listOf(videoEntry(), audioEntry), listOf(first)))
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format.sampleMimeType)
        assertEquals(2, out.samples.size)
        assertEquals(MimeTypes.AUDIO_RAW, out.recorder.outputs.getValue(2).format!!.sampleMimeType)
    }

    @Test
    fun `an HLG SEI only in a later AU leaves the track native and its samples untouched`() {
        val late = listOf(cluster(0, block(0, keyframe = true, idr), block(40, keyframe = false, hlgSei, trail)))
        assertNative(late)
        assertEquals(
            listOf("HLG SEI scan, track 1: no alternative transfer SEI before the first slice of the first AU; native"),
            scanLines.distinct()
        )
    }

    @Test
    fun `a first AU whose SEI says another transfer leaves the track native`() {
        val other = listOf(cluster(0, block(0, keyframe = true, bt2020Sei, idr), block(40, keyframe = false, trail)))
        val out = assertNative(other)
        assertEquals(14, out.track!!.hevcPreferredTransfer)
        assertEquals(HlgDecision.NotHlg, out.track!!.hlgDecision)
    }

    @Test
    fun `a laced first block leaves the track native`() {
        // Fixed-size lacing (flags bit 0x04): two frames of equal size in one SimpleBlock.
        val laced = element(
            0xA3,
            byteArrayOf(0x81.toByte(), 0, 0, (0x80 or 0x04).toByte(), 1),
            lengthPrefixed(hlgSei, idr),
            lengthPrefixed(hlgSei, idr),
        )
        assertNative(listOf(cluster(0, laced, block(80, keyframe = false, trail))))
        assertEquals(listOf("HLG SEI scan, track 1: the first block is laced; native"), scanLines.distinct())
    }

    @Test
    fun `a prefix past the scan cap leaves the track native`() {
        // A filler NAL (type 38) of 70 000 bytes before the SEI: the scan reads 64 KiB of the block.
        val filler = byteArrayOf(0x4C, 0x01) + ByteArray(70_000) { 0x55 }
        assertNative(listOf(cluster(0, block(0, keyframe = true, filler, hlgSei, idr), block(40, keyframe = false, trail))))
        assertEquals(
            listOf("HLG SEI scan, track 1: the NALs before the first slice run past the 65536 bytes the scan reads; native"),
            scanLines.distinct()
        )
    }

    @Test
    fun `header-stripped blocks are not scanned`() {
        // Header stripping removes the first byte of every block (the high byte of the 4-byte NAL length).
        val encoding = element(0x6D80, element(0x6240, element(0x5034, uint(0x4254, 3), element(0x4255, byteArrayOf(0)))))
        val stripped = cluster(
            0,
            strippedBlock(0, keyframe = true, hlgSei, idr),
            strippedBlock(40, keyframe = false, trail),
        )
        val out = read(mkv(listOf(videoEntry(encoding)), listOf(stripped)))
        assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertEquals(2, out.samples.size)
        assertFalse(out.track!!.isDolbyVisionConverted)
        assertEquals(listOf("HLG SEI scan, track 1: encrypted or compressed blocks are not scanned; native"), scanLines)
    }

    @Test
    fun `zlib-compressed blocks are not scanned`() {
        val encoding = element(0x6D80, element(0x6240, element(0x5034, uint(0x4254, 0))))
        val compressed = cluster(
            0,
            element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()), deflate(lengthPrefixed(hlgSei, idr))),
            element(0xA3, byteArrayOf(0x81.toByte(), 0, 40, 0), deflate(lengthPrefixed(trail))),
        )
        val out = read(mkv(listOf(videoEntry(encoding)), listOf(compressed)))
        assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertEquals(2, out.samples.size)
        assertFalse(out.track!!.isDolbyVisionConverted)
        assertEquals(listOf("HLG SEI scan, track 1: encrypted or compressed blocks are not scanned; native"), scanLines)
    }

    @Test
    fun `with conversion off the first AU is not scanned and the track gets no decision`() {
        for (transformer in listOf(null, transformer(convert = false))) {
            val out = read(mkv(listOf(videoEntry()), clusters), transformer)
            assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
            assertNull(out.track!!.dolbyVisionSignalling)
            assertFalse(out.track!!.waitingForHlgSeiScan)
            assertEquals(HlgDecision.NotHlg, out.track!!.hlgDecision)
        }
        assertEquals(emptyList<String>(), scanLines)
    }

    @Test
    fun `an HLG SEI in the hvcC converts at track init with one RPU per AU and no scan`() {
        val entry = element(
            TRACK_ENTRY,
            uint(0xD7, 1),
            uint(0x83, 1),
            element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
            element(0x63A2, HevcColourFixtures.hvcCOf("atc_sei", withSei = true)),
            element(0xE0, uint(0xB0, 1920), uint(0xBA, 1080)),
            uint(0x23E383, 40_000_000L),
        )
        val plain = listOf(cluster(0, block(0, keyframe = true, idr), block(40, keyframe = false, trail)))
        val native = read(mkv(listOf(entry), plain), transformer = null)
        val converted = read(mkv(listOf(entry), plain))
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, converted.format.sampleMimeType)
        assertEquals(
            listOf("format 1 ${MimeTypes.VIDEO_DOLBY_VISION}"),
            converted.recorder.events.filter { it.startsWith("format 1") }.distinct()
        )
        assertEquals(2, converted.samples.size)
        assertArrayEquals(native.samples[0] + annexB(refreshRpu), converted.samples[0])
        assertArrayEquals(native.samples[1] + annexB(plainRpu), converted.samples[1])
        assertEquals(emptyList<String>(), scanLines)
        assertTrue(logs.toString(), "HLG contract, track 1: HLG for 8.4 from the alternative transfer SEI" in logs)
    }

    @Test
    fun `an empty first block ends the scan and a later SEI is not read as the first AU`() {
        val empty = element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()))
        val file = mkv(listOf(videoEntry()), listOf(cluster(0, empty, block(40, keyframe = true, hlgSei, idr))))
        val out = read(file)
        assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertFalse(out.track!!.isDolbyVisionConverted)
        assertEquals(listOf("HLG SEI scan, track 1: the first block is empty; native"), scanLines)
    }

    @Test
    fun `deeply nested elements after the Tracks end the scan without overflowing the stack`() {
        // 60 000 BlockGroups of unknown size, each inside the previous one, in an unknown-size Cluster.
        val nested = ByteArray(120_000) { if (it % 2 == 0) 0xA0.toByte() else 0xFF.toByte() }
        val cluster = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0xFF.toByte()) + nested
        val file = mkv(listOf(videoEntry()), emptyList()).let { segmentWith(it, cluster) }
        val recorder = Recorder()
        val extractor = LiveExtractor(transformer())
        extractor.init(recorder)
        try {
            readFrom(extractor, file, 0)
        } catch (expected: Exception) {
            // The regular parser may refuse the layout; an Error (a stack overflow) must not escape.
        }
        assertEquals(MimeTypes.VIDEO_H265, recorder.outputs.getValue(1).format!!.sampleMimeType)
        assertEquals(
            listOf("HLG SEI scan, track 1: the early scan did not reach the first block; native"),
            scanLines
        )
    }

    @Test
    fun `a read error during the scan is rethrown and the retry converts the track`() {
        val file = mkv(listOf(videoEntry()), clusters)
        val firstCluster = clusterPosition(file, 0)
        var failed = false
        val recorder = Recorder()
        val extractor = LiveExtractor(transformer())
        extractor.init(recorder)
        // The first load fails once while the scan peeks into the first Cluster.
        var position = 0
        val failing = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!failed && position > firstCluster) {
                    failed = true
                    throw IOException("connection reset")
                }
                if (position >= file.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, file.size - position, 16)
                System.arraycopy(file, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val input = DefaultExtractorInput(failing, 0, file.size.toLong())
        val holder = PositionHolder()
        var thrown: IOException? = null
        try {
            repeat(500) { if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return@repeat }
        } catch (e: IOException) {
            thrown = e
        }
        assertEquals("connection reset", thrown?.message)
        assertTrue(scanLines.isEmpty())
        // Media3's retry: the same extractor, a new input at the last committed position.
        readFrom(extractor, file, input.position.toInt())
        val video = recorder.outputs.getValue(1)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, video.format!!.sampleMimeType)
        assertEquals(6, video.samples.size)
        assertTrue(video.samples[0].endsWith(annexB(refreshRpu)))
    }

    @Test
    fun `a retry from the file start after a read error in the scan converts before endTracks`() {
        // A stream of unknown length: Media3's retry seeks the extractor to 0 instead of resuming.
        val file = mkv(listOf(videoEntry()), clusters)
        val firstCluster = clusterPosition(file, 0)
        var failed = false
        var position = 0
        val failing = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!failed && position > firstCluster) {
                    failed = true
                    throw IOException("connection reset")
                }
                if (position >= file.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, file.size - position, 16)
                System.arraycopy(file, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val recorder = Recorder()
        val extractor = LiveExtractor(transformer())
        extractor.init(recorder)
        val input = DefaultExtractorInput(failing, 0, C.LENGTH_UNSET.toLong())
        try {
            while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
        } catch (expected: IOException) {
        }
        assertTrue(failed)
        extractor.seek(0, 0)
        readFrom(extractor, file, 0)
        val events = recorder.events
        val dolbyVision = events.indexOf("format 1 ${MimeTypes.VIDEO_DOLBY_VISION}")
        assertTrue(events.toString(), dolbyVision in 0 until events.indexOf("endTracks"))
        assertEquals(emptyList<String>(), scanLines)
        assertEquals(6, recorder.outputs.getValue(1).samples.size)
    }

    @Test
    fun `repeated read errors in the scan never let the parser advance past the first AU`() {
        // PR #19 review F1: each resumed retry must service the pending scan before reading on.
        val file = mkv(listOf(videoEntry()), clusters)
        var failures = 0
        var position = 0
        val reader = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= file.size) return C.RESULT_END_OF_INPUT
                // The first three reads the early scan makes fail, as a dropped connection would.
                val scanning = Throwable().stackTrace.any { it.methodName == "scanElementSequence" }
                if (scanning && failures < 3) {
                    failures++
                    throw IOException("connection reset $failures")
                }
                val count = minOf(length, file.size - position)
                System.arraycopy(file, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val recorder = Recorder()
        val extractor = LiveExtractor(transformer())
        extractor.init(recorder)
        var done = false
        repeat(10) {
            if (done) return@repeat
            // Media3 resumes a known-length stream at the committed input position.
            val input = DefaultExtractorInput(reader, position.toLong(), file.size.toLong())
            try {
                while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
                done = true
            } catch (expected: IOException) {
                position = input.position.toInt()
            }
        }
        assertEquals(3, failures)
        assertTrue(done)
        val events = recorder.events
        val dolbyVision = events.indexOf("format 1 ${MimeTypes.VIDEO_DOLBY_VISION}")
        assertTrue(events.toString(), dolbyVision in 0 until events.indexOf("sample 1"))
        assertTrue(events.toString(), dolbyVision < events.indexOf("endTracks"))
        val video = recorder.outputs.getValue(1)
        assertEquals(6, video.samples.size)
        assertTrue(video.samples[0].endsWith(annexB(refreshRpu)))
    }

    @Test
    fun `the first video block in a Cluster after an unknown-size Cluster is scanned`() {
        // PR #19 review F2: an unknown-size Cluster ends where the next Cluster starts; that Cluster
        // is a sibling, and its BlockGroup is an ordinary one.
        val audio = element(0xA3, byteArrayOf(0x82.toByte(), 0, 0, 0x80.toByte()), ByteArray(64))
        val unknownSizeCluster = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0xFF.toByte()) +
            uint(0xE7, 0) + audio
        val blockGroup = element(
            0xA0,
            element(0xA1, byteArrayOf(0x81.toByte(), 0, 0, 0), lengthPrefixed(hlgSei, idr)),
        )
        val second = cluster(40, blockGroup, block(40, keyframe = false, trail))
        val file = segmentWith(mkv(listOf(videoEntry(), audioEntry), emptyList()), unknownSizeCluster + second)
        // A scan that misses the block warns at the end of the input through Media3's Log, which needs
        // TextUtils (a stub on the JVM).
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        val out = try {
            read(file)
        } finally {
            unmockkStatic(TextUtils::class)
        }
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, out.format.sampleMimeType)
        assertEquals(2, out.samples.size)
        assertEquals(emptyList<String>(), scanLines)
    }

    // DV2.11: an hvcC with no parameter sets (VPS, SPS and PPS only in the access units), as the BBC
    // broadcast HLG samples of the DV3.2 device session have it: container Colour 9/14/9, no Range.

    private val bbcColour = element(0x55B0, uint(0x55B1, 9), uint(0x55BA, 14), uint(0x55BB, 9))

    private fun inBandEntry(colour: ByteArray? = bbcColour, hvcC: ByteArray = HevcColourFixtures.hvcC()) = element(
        TRACK_ENTRY,
        uint(0xD7, 1),
        uint(0x83, 1),
        element(0x86, "V_MPEGH/ISO/HEVC".toByteArray()),
        element(0x63A2, hvcC),
        element(0xE0, *listOfNotNull(uint(0xB0, 1920), uint(0xBA, 1080), colour).toTypedArray()),
        uint(0x23E383, 40_000_000L),
    )

    private fun parameterSets(case: String) =
        listOf(HevcColourFixtures.vps(case), HevcColourFixtures.sps(case), HevcColourFixtures.pps(case))

    private fun inBandFile(case: String, vararg firstAuExtra: ByteArray, colour: ByteArray? = bbcColour) = mkv(
        listOf(inBandEntry(colour)),
        listOf(
            cluster(
                0,
                block(0, keyframe = true, *(parameterSets(case) + firstAuExtra.toList() + listOf(idr)).toTypedArray()),
                block(40, keyframe = false, trail),
            )
        ),
    )

    @Test
    fun `an hvcC without parameter sets takes the SPS and the HLG SEI from the first AU and converts`() {
        val file = inBandFile("atc_sei", hlgSei)
        val native = read(file, transformer = null)
        val converted = read(file)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, converted.format.sampleMimeType)
        assertEquals(C.COLOR_TRANSFER_HLG, converted.format.colorInfo!!.colorTransfer)
        // The Dolby Vision decoder gets the AU's parameter sets as its initialization data.
        assertArrayEquals(annexB(*parameterSets("atc_sei").toTypedArray()), converted.format.initializationData.single())
        assertTrue(native.format.initializationData.isEmpty())
        val track = converted.track!!
        assertTrue((track.hlgDecision as HlgDecision.Hlg84).transferFromSei)
        assertEquals(DvSignallingDecision.Target.PROFILE_8_4, (track.dolbyVisionSignalling as DvSignallingDecision.Convert).target)
        val events = converted.recorder.events
        val dolbyVision = events.indexOf("format 1 ${MimeTypes.VIDEO_DOLBY_VISION}")
        assertTrue(events.toString(), dolbyVision in 0 until events.indexOf("endTracks"))
        assertTrue(events.toString(), dolbyVision < events.indexOf("sample 1"))
        assertArrayEquals(native.samples[0] + annexB(refreshRpu), converted.samples[0])
        assertArrayEquals(native.samples[1] + annexB(plainRpu), converted.samples[1])
        assertTrue(
            logs.toString(),
            "HLG contract, track 1: HLG for 8.4 from the alternative transfer SEI (SPS from the first AU)" in logs
        )
    }

    @Test
    fun `an hvcC with only an HLG SEI takes the parameter sets from the first AU into the initialization data`() {
        val file = mkv(
            listOf(inBandEntry(hvcC = HevcColourFixtures.hvcC(39 to listOf(hlgSei)))),
            listOf(
                cluster(
                    0,
                    block(0, keyframe = true, *(parameterSets("atc_sei") + listOf(idr)).toTypedArray()),
                    block(40, keyframe = false, trail),
                )
            ),
        )
        val converted = read(file)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, converted.format.sampleMimeType)
        // The AU's VPS, SPS and PPS come first; the hvcC's SEI is kept behind them.
        assertArrayEquals(
            annexB(*(parameterSets("atc_sei") + listOf(hlgSei)).toTypedArray()),
            converted.format.initializationData.single()
        )
    }

    @Test
    fun `an hvcC without parameter sets and an in-band SPS that states HLG converts without an SEI`() {
        // No container Colour: a container 14 would contradict the SPS's 18.
        val converted = read(inBandFile("hlg_tagged", colour = null))
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, converted.format.sampleMimeType)
        assertFalse((converted.track!!.hlgDecision as HlgDecision.Hlg84).transferFromSei)
    }

    @Test
    fun `an hvcC without parameter sets whose in-band SPS is not HLG and has no SEI stays native`() {
        val out = read(inBandFile("atc_sei"))
        assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertEquals(HlgDecision.NotHlg, out.track!!.hlgDecision)
        assertFalse(out.track!!.waitingForHlgSeiScan)
        assertTrue(out.format.initializationData.isEmpty())
    }

    @Test
    fun `an hvcC without parameter sets and a first AU without them stays native`() {
        val file = mkv(listOf(inBandEntry()), listOf(cluster(0, block(0, keyframe = true, idr), block(40, keyframe = false, trail))))
        val out = read(file)
        assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertEquals(
            listOf("HLG SEI scan, track 1: no alternative transfer SEI before the first slice of the first AU; native"),
            scanLines
        )
    }

    @Test
    fun `with conversion off an hvcC without parameter sets is not scanned`() {
        val out = read(inBandFile("atc_sei", hlgSei), transformer(convert = false))
        assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertTrue(out.format.initializationData.isEmpty())
        assertEquals(emptyList<String>(), scanLines)
    }

    /** [file] with [extra] appended inside its Segment (the Segment's size field grows with it). */
    private fun segmentWith(file: ByteArray, extra: ByteArray): ByteArray {
        val id = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
        val at = (0..file.size - 4).first { file.copyOfRange(it, it + 4).contentEquals(id) }
        val sizeAt = at + 4
        var size = 0L
        for (i in 1..7) size = (size shl 8) or (file[sizeAt + i].toLong() and 0xFF)
        size += extra.size
        val out = file.copyOf()
        for (i in 1..7) out[sizeAt + i] = (size ushr (8 * (7 - i))).toByte()
        return out + extra
    }

    @Test
    fun `a mid-file start after the opening read keeps the conversion without scene refresh`() {
        // Media3 opens every load at the file start (header and first Cluster), then seeks.
        val file = mkv(listOf(videoEntry()), clusters)
        val second = clusterPosition(file, 1)
        val recorder = Recorder()
        val extractor = LiveExtractor(transformer())
        extractor.init(recorder)
        readFrom(extractor, file, 0, end = second)
        val opening = recorder.outputs.getValue(1).samples.size
        extractor.seek(second.toLong(), 120_000)
        readFrom(extractor, file, second)
        val video = recorder.outputs.getValue(1)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, video.format!!.sampleMimeType)
        val resumed = video.samples.drop(opening)
        assertEquals(3, resumed.size)
        for (sample in resumed) assertTrue(sample.endsWith(annexB(plainRpu)))
    }

    private fun assertNative(clusters: List<ByteArray>): Read {
        val file = mkv(listOf(videoEntry()), clusters)
        val native = read(file, transformer = null)
        val out = read(file)
        assertEquals(MimeTypes.VIDEO_H265, out.format.sampleMimeType)
        assertFalse(out.track!!.isDolbyVisionConverted)
        assertFalse(out.track!!.waitingForHlgSeiScan)
        assertNull(out.stats.getConvertedTrackTarget())
        assertFalse(out.recorder.events.contains("format 1 ${MimeTypes.VIDEO_DOLBY_VISION}"))
        assertEquals(native.samples.size, out.samples.size)
        for (i in native.samples.indices) assertArrayEquals("AU $i", native.samples[i], out.samples[i])
        return out
    }

    private fun transformer(convert: Boolean = true) = DolbyVisionMatroskaTransformer(
        DolbyVisionConversionConfig(active = false, convertNonDolbyVision = convert, stats = DolbyVisionStatsSession()),
        rpuSource = readySource,
    )

    private fun read(file: ByteArray, transformer: DolbyVisionMatroskaTransformer? = transformer()): Read {
        val recorder = Recorder()
        val extractor = LiveExtractor(transformer)
        extractor.init(recorder)
        readFrom(extractor, file, 0)
        val stats = transformer?.let { statsOf(it) } ?: DolbyVisionStatsSession()
        return Read(recorder, extractor.track, stats)
    }

    private fun statsOf(transformer: DolbyVisionMatroskaTransformer): DolbyVisionStatsSession {
        val field = DolbyVisionMatroskaTransformer::class.java.getDeclaredField("config")
        field.isAccessible = true
        return (field.get(transformer) as DolbyVisionConversionConfig).stats
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

    /** The byte offset of Cluster [index] in [file], the position a Media3 seek hands the extractor. */
    private fun clusterPosition(file: ByteArray, index: Int): Int {
        val id = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        var found = -1
        for (i in 0..file.size - id.size) {
            if (file.copyOfRange(i, i + id.size).contentEquals(id) && ++found == index) return i
        }
        error("no Cluster $index")
    }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

    private fun cluster(timecodeMs: Int, vararg blocks: ByteArray) = element(0x1F43B675, uint(0xE7, timecodeMs), *blocks)

    /** A SimpleBlock of track 1 at [relativeMs] from its Cluster. */
    private fun block(relativeMs: Int, keyframe: Boolean, vararg nals: ByteArray) = element(
        0xA3,
        byteArrayOf(0x81.toByte(), (relativeMs ushr 8).toByte(), relativeMs.toByte(), if (keyframe) 0x80.toByte() else 0),
        lengthPrefixed(*nals)
    )

    /** [block] without the first payload byte, which header stripping (settings `00`) puts back. */
    private fun strippedBlock(relativeMs: Int, keyframe: Boolean, vararg nals: ByteArray) = element(
        0xA3,
        byteArrayOf(0x81.toByte(), (relativeMs ushr 8).toByte(), relativeMs.toByte(), if (keyframe) 0x80.toByte() else 0),
        lengthPrefixed(*nals).copyOfRange(1, lengthPrefixed(*nals).size)
    )

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
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

    private fun mkv(entries: List<ByteArray>, clusters: List<ByteArray>): ByteArray {
        val header = element(
            0x1A45DFA3,
            element(0x4282, "matroska".toByteArray()),
            uint(0x4287, 4),
            uint(0x4285, 2)
        )
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = element(0x1654AE6B, *entries.toTypedArray())
        return header + element(0x18538067, info, tracks, *clusters.toTypedArray())
    }

    private fun float(id: Int, value: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(value)
        return element(id, ByteArray(4) { i -> (bits ushr (8 * (3 - i))).toByte() })
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
