package com.nuvio.tv.core.player

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.HevcConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HevcInitNalsTest {

    /** The initialization data Media3 builds from an hvcC, as the Matroska extractor receives it. */
    private fun initData(hvcC: ByteArray): List<ByteArray> =
        HevcConfig.parse(ParsableByteArray(hvcC)).initializationData

    @Test
    fun `the first SPS of every fixture hvcC comes back byte for byte`() {
        for (case in HevcColourFixtures.cases) {
            val init = initData(HevcColourFixtures.hvcCOf(case))
            assertArrayEquals(case, HevcColourFixtures.sps(case), HevcInitNals.firstSps(init))
        }
    }

    @Test
    fun `all hvcC NALs are split out in order`() {
        val init = initData(HevcColourFixtures.hvcCOf("atc_sei"))
        val nals = HevcInitNals.nals(init)
        assertEquals(4, nals.size)
        assertArrayEquals(HevcColourFixtures.vps("atc_sei"), nals[0])
        assertArrayEquals(HevcColourFixtures.sps("atc_sei"), nals[1])
        assertArrayEquals(HevcColourFixtures.pps("atc_sei"), nals[2])
        assertArrayEquals(HevcColourFixtures.sei("atc_sei"), nals[3])
    }

    @Test
    fun `the first of two SPS is taken`() {
        val hvcC = HevcColourFixtures.hvcC(
            33 to listOf(HevcColourFixtures.sps("pq"), HevcColourFixtures.sps("hlg_tagged"))
        )
        assertArrayEquals(HevcColourFixtures.sps("pq"), HevcInitNals.firstSps(initData(hvcC)))
    }

    @Test
    fun `no SPS or no initialization data gives null`() {
        assertNull(HevcInitNals.firstSps(null))
        assertNull(HevcInitNals.firstSps(emptyList()))
        val hvcC = HevcColourFixtures.hvcC(34 to listOf(HevcColourFixtures.pps("pq")))
        assertNull(HevcInitNals.firstSps(initData(hvcC)))
    }

    @Test
    fun `the alternative transfer SEI of the x265 fixture reads as HLG`() {
        assertEquals(18, HevcInitNals.preferredTransfer(initData(HevcColourFixtures.hvcCOf("atc_sei"))))
        assertNull(HevcInitNals.preferredTransfer(initData(HevcColourFixtures.hvcCOf("atc_sei", withSei = false))))
        assertNull(HevcInitNals.preferredTransfer(initData(HevcColourFixtures.hvcCOf("hlg_tagged"))))
        assertNull(HevcInitNals.preferredTransfer(null))
    }

    @Test
    fun `payload 147 is found after other messages and behind emulation prevention`() {
        // A user_data_unregistered (5) message of 16 zero bytes, escaped, then payload 147 with 18.
        val rbspPayload5 = ByteArray(16)
        val escaped = mutableListOf<Byte>(0x4E, 0x01, 5, 16)
        var zeros = 0
        for (b in rbspPayload5) {
            if (zeros == 2) {
                escaped += 3
                zeros = 0
            }
            escaped += b
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        escaped += listOf(0x93.toByte(), 1, 18, 0x80.toByte())
        val hvcC = HevcColourFixtures.hvcC(
            33 to listOf(HevcColourFixtures.sps("hlg_tagged")),
            39 to listOf(escaped.toByteArray())
        )
        assertEquals(18, HevcInitNals.preferredTransfer(initData(hvcC)))
    }

    @Test
    fun `a suffix SEI or a truncated message is not read`() {
        val suffix = HevcColourFixtures.sei("atc_sei")!!
        suffix[0] = 0x50 // nal_unit_type 40, suffix SEI
        assertNull(HevcInitNals.preferredTransfer(listOf(byteArrayOf(0, 0, 0, 1) + suffix)))
        val truncated = byteArrayOf(0, 0, 0, 1, 0x4E, 0x01, 0x93.toByte(), 4, 18, 0x80.toByte())
        assertNull(HevcInitNals.preferredTransfer(listOf(truncated)))
    }
}
