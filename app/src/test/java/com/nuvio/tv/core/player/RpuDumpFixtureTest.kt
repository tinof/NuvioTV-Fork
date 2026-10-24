package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden round trip's Kotlin half: `rpu_dump/p81_v29_shots.RPU.bin` (see its README) queued through the
 * renderer's recorder must log exactly the lines of `scripts/tests/fixtures/rpu_dump_p81_v29.logcat`, which the
 * script's test turns back into the same `RPU.bin`.
 */
class RpuDumpFixtureTest {

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
        val recorder = RpuDumpRecorder(nals.size, reader) { lines += it }
        recorder.onPositionReset(0)
        recorder.onFormat(
            Format.Builder().setId("1").setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.08.06").build()
        )
        nals.forEachIndexed { i, nal ->
            val au = startCode + byteArrayOf(0x02, 0x01, i.toByte(), 0x55) + startCode + nal
            recorder.onQueueInputBuffer(ByteBuffer.wrap(au), i * 41_708L, false)
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
