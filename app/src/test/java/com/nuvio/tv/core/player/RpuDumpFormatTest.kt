package com.nuvio.tv.core.player

import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpuDumpFormatTest {

    /** An RPU-shaped NAL: `7C 01` header, then bytes that cover every value. */
    private fun nal(length: Int): ByteArray =
        ByteArray(length) { i -> if (i == 0) 0x7C else if (i == 1) 0x01 else (i * 31).toByte() }

    private fun encode(nal: ByteArray, offset: Int = 0, length: Int = nal.size, sr: Boolean? = true) =
        RpuDumpFormat.lines(7, 1, 2, 3, 41_708, sr, RpuDumpFormat.WHY_START, nal, offset, length)

    @Test
    fun partsSplitAtChunkBoundaries() {
        assertEquals(1, encode(nal(1499)).size)
        assertEquals(1, encode(nal(1500)).size)
        assertEquals(2, encode(nal(1501)).size)
        assertEquals(3, encode(nal(3001)).size)
        val lines = encode(nal(1501))
        assertTrue(lines[0].contains(" part=1/2 "))
        assertTrue(lines[1].contains(" part=2/2 "))
        assertEquals(1500 * 2, lines[0].substringAfterLast(' ').length)
        assertEquals(2, lines[1].substringAfterLast(' ').length)
    }

    @Test
    fun everyLineStaysUnderTheLogdLimit() {
        for (length in listOf(3, 1499, 1500, 1501, 4_000, 65_536)) {
            for (line in encode(nal(length))) {
                assertTrue("$length: ${line.length}", line.toByteArray(Charsets.UTF_8).size < 4000)
            }
        }
    }

    @Test
    fun hexAndCrcRoundTrip() {
        for (length in listOf(3, 1499, 1500, 1501, 3001)) {
            val bytes = nal(length)
            val record = RpuDumpTestLines.parse(encode(bytes)).single()
            assertArrayEquals(bytes, record.nal)
            assertEquals(length, record.fields["len"]!!.toInt())
            val crc = CRC32().apply { update(bytes) }.value
            assertEquals("%08x".format(crc), record.fields["crc"])
        }
    }

    @Test
    fun encodesOnlyTheGivenRange() {
        val padded = byteArrayOf(9, 9) + nal(200) + byteArrayOf(9)
        assertArrayEquals(nal(200), RpuDumpTestLines.parse(encode(padded, 2, 200)).single().nal)
    }

    @Test
    fun headerCarriesEveryField() {
        val line = encode(nal(4)).single()
        assertEquals("v1 seq=7 trk=1 gen=2 au=3 pts=41708 sr=1 why=start len=4 crc=", line.substringBefore("crc=") + "crc=")
        assertTrue(line.endsWith(" part=1/1 7c013e5d"))
        assertTrue(encode(nal(4), sr = false).single().contains(" sr=0 "))
        assertTrue(encode(nal(4), sr = null).single().contains(" sr=? "))
    }

    @Test
    fun generationLine() {
        assertEquals(
            "v1 gen=4 trk=1 event=seek seekUs=120000000 n=24 codecs=dvhe.08.06",
            RpuDumpFormat.generationLine(4, 1, "seek", 120_000_000, 24, "dvhe.08.06")
        )
        assertEquals(
            "v1 gen=0 trk=2 event=init seekUs=-1 n=1 codecs=-",
            RpuDumpFormat.generationLine(0, 2, "init", -1, 1, null)
        )
    }
}

/** Reassembles dump records from lines, as `scripts/rpu_dump_to_bin.py` does. */
internal object RpuDumpTestLines {
    class Record(val fields: Map<String, String>, val nal: ByteArray)

    fun fields(line: String): Map<String, String> =
        line.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    fun parse(lines: List<String>): List<Record> {
        val records = ArrayList<Record>()
        var hex = StringBuilder()
        for (line in lines.filter { " seq=" in it }) {
            val f = fields(line)
            val (part, parts) = f["part"]!!.split('/').map { it.toInt() }
            if (part == 1) hex = StringBuilder()
            hex.append(line.substringAfterLast(' '))
            if (part == parts) {
                val nal = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
                assertEquals(f["len"]!!.toInt(), nal.size)
                assertEquals(f["crc"], "%08x".format(CRC32().apply { update(nal) }.value))
                records += Record(f, nal)
            }
        }
        return records
    }
}
