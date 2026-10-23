package com.nuvio.tv.core.player

import androidx.media3.common.C
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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden round trip's Kotlin half: `rpu_dump/p81_v29_shots.RPU.bin` (see its README) played through the tap
 * must log exactly the lines of `scripts/tests/fixtures/rpu_dump_p81_v29.logcat`, which the script's test turns
 * back into the same `RPU.bin`.
 */
class RpuDumpFixtureTest {

    private class Source : Extractor {
        lateinit var output: ExtractorOutput
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun sniff(input: ExtractorInput) = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = C.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    private val startCode = byteArrayOf(0, 0, 0, 1)

    /** The fixture's records, each as the RPU NAL a stream carries: `7C 01`, then the record. */
    private fun fixtureNals(): List<ByteArray> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("rpu_dump/p81_v29_shots.RPU.bin")!!.readBytes()
        val starts = (0..bytes.size - 4).filter { i -> (0 until 4).all { bytes[i + it] == startCode[it] } }
        return starts.mapIndexed { n, at ->
            byteArrayOf(0x7C, 0x01) + bytes.copyOfRange(at + 4, starts.getOrElse(n + 1) { bytes.size })
        }
    }

    private fun logcatFixture(): File =
        listOf(File("../scripts/tests/fixtures"), File("scripts/tests/fixtures"))
            .first { it.isDirectory }
            .resolve("rpu_dump_p81_v29.logcat")

    @Test
    fun fixtureRpusLogAsTheCommittedLines() {
        val nals = fixtureNals()
        assertEquals(7, nals.size)
        val refreshAus = setOf(0, 3, 5)                 // the shot starts in p81_v29_shots.extra.json
        val reader = RpuSceneRefreshReader { nal, offset, length ->
            val index = nals.indexOfFirst { it.size == length && it.indices.all { i -> it[i] == nal[offset + i] } }
            index in refreshAus
        }
        val lines = ArrayList<String>()
        val source = Source()
        val extractor = RpuDumpTap.wrap(ExtractorsFactory { arrayOf(source) }, nals.size, reader) { lines += it }
            .createExtractors().single()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) {}
                override fun sampleData(
                    input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int
                ): Int = input.read(ByteArray(length), 0, length)
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        extractor.seek(0, 0)
        val track = source.output.track(1, C.TRACK_TYPE_VIDEO)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.08.06").build())
        nals.forEachIndexed { i, nal ->
            val au = startCode + byteArrayOf(0x02, 0x01, i.toByte(), 0x55) + startCode + nal
            track.sampleData(ParsableByteArray(au), au.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            track.sampleMetadata(i * 41_708L, if (i in refreshAus) C.BUFFER_FLAG_KEY_FRAME else 0, au.size, 0, null)
        }

        val fixture = logcatFixture()
        val expected = fixture.readLines()
            .filter { " ${RpuDumpFormat.TAG}: " in it }
            .map { it.substringAfter(" ${RpuDumpFormat.TAG}: ") }
        if (expected != lines) {
            File("build/rpu_dump_p81_v29.actual.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }
        assertEquals(expected, lines)
    }
}
