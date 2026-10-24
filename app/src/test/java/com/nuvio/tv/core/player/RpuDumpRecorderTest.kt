package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RPU dump at the video renderer (ROADMAP DV2.8): what it logs for the input buffers it is shown. */
class RpuDumpRecorderTest {

    private class Harness(firstAus: Int, reader: RpuSceneRefreshReader) {
        val lines = ArrayList<String>()
        val recorder = RpuDumpRecorder(firstAus, reader) { lines += it }

        /** A renderer start: the position reset, then the first Format, as Media3 orders them. */
        fun start(positionUs: Long = 0, format: Format? = dvFormat()) {
            recorder.onPositionReset(positionUs)
            recorder.onFormat(format)
        }

        fun queue(au: ByteArray, timeUs: Long, encrypted: Boolean = false) =
            recorder.onQueueInputBuffer(buffer(au), timeUs, encrypted)

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

    private companion object {
        val startCode = byteArrayOf(0, 0, 0, 1)

        fun dvFormat(codecs: String = "dvhe.08.06", id: String = "1") =
            Format.Builder().setId(id).setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs).build()

        /** As the codec gets it: position 0, limit at the end of the AU, inside a larger buffer. */
        fun buffer(au: ByteArray): ByteBuffer =
            ByteBuffer.allocate(au.size + 64).apply { put(au); flip() }
    }

    /** RPU NAL: `7C 01`, the refresh marker, an id byte and the 0x80 stop byte. */
    private fun rpu(id: Int, refresh: Boolean = false) = byteArrayOf(0x7C, 0x01, if (refresh) 1 else 0, id.toByte(), 0x80.toByte())

    /** Annex B AU: a TRAIL_R slice (type 1), then the RPU. */
    private fun au(rpu: ByteArray?) = startCode + byteArrayOf(0x02, 0x01, 0x55, 0x66) + (rpu?.let { startCode + it } ?: byteArrayOf())

    @Test
    fun offCreatesNoRecorder() {
        assertNull(RpuDumpRecorder.create(0))
        assertNull(RpuDumpRecorder.create(-3))
        assertTrue(RpuDumpRecorder.create(1) != null)
    }

    @Test
    fun firstAusOfEachGenerationThenRefreshOnly() {
        val h = Harness(firstAus = 2, FlagReader())
        h.start()
        for (i in 0 until 5) h.queue(au(rpu(i, refresh = i == 3)), i * 40_000L)
        h.recorder.onPositionReset(5_000_000)
        for (i in 5 until 9) h.queue(au(rpu(i, refresh = i == 8)), i * 40_000L)

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
                "v1 gen=0 trk=1 event=seek seekUs=0 n=2 codecs=dvhe.08.06",
                "v1 gen=1 trk=1 event=seek seekUs=5000000 n=2 codecs=dvhe.08.06"
            ),
            h.generations
        )
    }

    @Test
    fun aSeekInsideTheBufferStartsAGenerationToo() {
        // Media3 serves a seek inside already-extracted samples without the extractor; the renderer still
        // resets, and the AUs it queues again after that reset are the new generation's first N.
        val h = Harness(firstAus = 2, FlagReader())
        h.start()
        for (i in 0 until 4) h.queue(au(rpu(i)), i * 40_000L)
        h.recorder.onPositionReset(40_000)
        for (i in 1 until 4) h.queue(au(rpu(i)), i * 40_000L)
        assertEquals(listOf(0, 1, 1, 2), h.records.map { it.nal[3].toInt() })
        assertEquals(listOf("0", "0", "1", "1"), h.records.map { it.fields["gen"] })
        assertEquals("v1 gen=1 trk=1 event=seek seekUs=40000 n=2 codecs=dvhe.08.06", h.generations.last())
    }

    @Test
    fun resetsBeforeTheFirstAuStartNoGeneration() {
        val h = Harness(firstAus = 1, FlagReader())
        h.start(positionUs = 0)
        h.recorder.onPositionReset(7_000)
        h.queue(au(rpu(0)), 7_000)
        assertEquals(listOf("v1 gen=0 trk=1 event=seek seekUs=7000 n=1 codecs=dvhe.08.06"), h.generations)
        assertEquals(listOf("0"), h.records.map { it.fields["gen"] })
    }

    @Test
    fun aFormatChangeStartsAGenerationWithItsCodecs() {
        // An adaptive variant switch: the renderer gets a new Format mid-stream.
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        h.queue(au(rpu(0)), 0)
        h.queue(au(rpu(1)), 40_000)
        h.recorder.onFormat(dvFormat(codecs = "dvhe.08.09"))
        h.queue(au(rpu(2)), 80_000)
        assertEquals(
            listOf(
                "v1 gen=0 trk=1 event=seek seekUs=0 n=1 codecs=dvhe.08.06",
                "v1 gen=1 trk=1 event=format seekUs=-1 n=1 codecs=dvhe.08.09"
            ),
            h.generations
        )
        assertEquals(listOf(0, 2), h.records.map { it.nal[3].toInt() })
    }

    @Test
    fun theSameFormatAgainStartsNoGeneration() {
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        h.queue(au(rpu(0)), 0)
        h.recorder.onFormat(dvFormat())
        h.queue(au(rpu(1)), 40_000)
        assertEquals(1, h.generations.size)
        assertEquals(listOf(0), h.records.map { it.nal[3].toInt() })
    }

    @Test
    fun unreadableFlagKeepsOnlyTheFirstAus() {
        val h = Harness(firstAus = 1, FlagReader { _, _ -> null })
        h.start()
        for (i in 0 until 4) h.queue(au(rpu(i, refresh = true)), i * 40_000L)
        assertEquals(listOf("?"), h.records.map { it.fields["sr"] })
    }

    @Test
    fun repeatedRpuIsParsedOnce() {
        val reader = FlagReader()
        val h = Harness(firstAus = 10, reader)
        h.start()
        repeat(3) { h.queue(au(rpu(7)), it * 40_000L) }
        h.queue(au(rpu(8)), 120_000)
        assertEquals(4, h.records.size)
        assertEquals(2, reader.calls)
    }

    @Test
    fun theBufferIsOnlyRead() {
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        val sample = au(rpu(3))
        val buffer = buffer(sample)
        h.recorder.onQueueInputBuffer(buffer, 0, false)
        assertEquals(0, buffer.position())
        assertEquals(sample.size, buffer.limit())
        val after = ByteArray(sample.size).also { buffer.duplicate().get(it) }
        assertArrayEquals(sample, after)
        assertArrayEquals(rpu(3), h.records.single().nal)
    }

    @Test
    fun onlyTheBytesUpToTheLimitAreTheAu() {
        // The codec buffer is larger than the AU; bytes past the limit are not part of it.
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        val sample = au(null)
        val buffer = ByteBuffer.allocate(64).apply { put(sample); put(startCode); put(rpu(9)); position(0); limit(sample.size) }
        h.recorder.onQueueInputBuffer(buffer, 0, false)
        assertEquals(emptyList<Any>(), h.records)
        assertEquals(1, h.generations.size)
    }

    @Test
    fun theAuRunsFromThePositionToTheLimit() {
        // The native FEL renderer hands its buffer over at position(), not 0, and keeps reading from there.
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        val junk = startCode + rpu(9)
        val sample = au(rpu(4))
        val buffer = ByteBuffer.allocate(junk.size + sample.size + 8).apply { put(junk); put(sample); flip(); position(junk.size) }
        h.recorder.onQueueInputBuffer(buffer, 0, false)
        assertArrayEquals(rpu(4), h.records.single().nal)
        assertEquals(junk.size, buffer.position())
    }

    @Test
    fun parameterSetsInFrontOfTheSampleAreSkipped() {
        // A codec reconfiguration puts the VPS, SPS and PPS before the sample.
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        val prefix = startCode + byteArrayOf(0x40, 0x01, 0x0C) + startCode + byteArrayOf(0x42, 0x01, 0x01) +
            startCode + byteArrayOf(0x44, 0x01, 0xC1.toByte())
        h.queue(prefix + au(rpu(4)), 0)
        assertArrayEquals(rpu(4), h.records.single().nal)
    }

    @Test
    fun trailingZeroBytesAreNotTheRpus() {
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        h.queue(au(rpu(5)) + byteArrayOf(0, 0), 0)
        assertArrayEquals(rpu(5), h.records.single().nal)
    }

    @Test
    fun encryptedAuIsCountedButNotDumped() {
        val h = Harness(firstAus = 2, FlagReader())
        h.start()
        h.queue(au(rpu(0)), 0, encrypted = true)
        h.queue(au(rpu(1)), 40_000)
        h.queue(au(rpu(2)), 80_000)
        assertEquals(listOf(1), h.records.map { it.nal[3].toInt() })
        assertEquals(listOf("1"), h.records.map { it.fields["au"] })
    }

    @Test
    fun lengthDelimitedSampleFallsBack() {
        val h = Harness(firstAus = 1, FlagReader())
        h.start()
        val slice = byteArrayOf(0x02, 0x01, 0x55, 0x66)
        val nal = rpu(6)
        fun lengthOf(n: ByteArray) = byteArrayOf(0, 0, 0, n.size.toByte())
        h.queue(lengthOf(slice) + slice + lengthOf(nal) + nal, 0)
        assertArrayEquals(nal, h.records.single().nal)
    }

    @Test
    fun onlyHevcVideoIsRecorded() {
        val h = Harness(firstAus = 5, FlagReader())
        h.start(format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        h.queue(au(rpu(0)), 0)
        h.recorder.onFormat(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvav.09.05").build())
        h.queue(au(rpu(1)), 40_000)
        assertEquals(emptyList<String>(), h.lines)
        // A plain HEVC track (HDR10, or a stream whose RPU the extractor kept) is recorded; trk falls back to 0.
        h.recorder.onFormat(Format.Builder().setId("video/1").setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build())
        h.queue(au(rpu(2)), 80_000)
        assertEquals(listOf(2), h.records.map { it.nal[3].toInt() })
        assertEquals("0", h.records.single().fields["trk"])
    }

    @Test
    fun anAuWithoutAnRpuIsCountedButLogsNothing() {
        val h = Harness(firstAus = 2, FlagReader())
        h.start()
        h.queue(au(null), 0)
        h.queue(au(rpu(1)), 40_000)
        h.queue(au(rpu(2)), 80_000)
        assertEquals(listOf("1"), h.records.map { it.fields["au"] })
    }
}
