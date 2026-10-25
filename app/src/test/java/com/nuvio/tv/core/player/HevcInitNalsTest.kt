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

    // DV2.10: the walk over the start of a block the early scan peeks.

    private val sei = HevcColourFixtures.sei("atc_sei")!!
    private val aud = byteArrayOf(0x46, 0x01, 0x50)
    private val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x10, 0x11, 0x12)

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            for (i in 3 downTo 0) out.write((nal.size ushr (8 * i)) and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun prefix(au: ByteArray, available: Int) =
        HevcInitNals.preferredTransferInAuPrefix(au.copyOf(available), available, au.size, 4)

    @Test
    fun `the AU prefix walk reads the SEI and stops at the first VCL NAL even without its body`() {
        val au = lengthPrefixed(aud, sei, idr)
        assertEquals(18, prefix(au, au.size))
        // The window ends inside the slice: everything before it was read.
        assertEquals(18, prefix(au, au.size - 3))
        val noSei = lengthPrefixed(aud, idr)
        assertEquals(HevcInitNals.PREFIX_NO_SEI, prefix(noSei, noSei.size))
        // Only the slice's length field and NAL header are needed to stop.
        assertEquals(HevcInitNals.PREFIX_NO_SEI, prefix(noSei, 4 + aud.size + 4 + 2))
        // An SEI after the first slice is not a prefix SEI of this AU.
        val late = lengthPrefixed(idr, sei)
        assertEquals(HevcInitNals.PREFIX_NO_SEI, prefix(late, late.size))
    }

    @Test
    fun `a window that ends before the first slice is truncated`() {
        val au = lengthPrefixed(aud, sei, idr)
        // Inside the SEI.
        assertEquals(HevcInitNals.PREFIX_TRUNCATED, prefix(au, 4 + aud.size + 4 + 3))
        // Inside the slice's length field, after an SEI without payload 147.
        val otherSei = byteArrayOf(0x4E, 0x01, 0x05, 0x01, 0x00, 0x80.toByte())
        val other = lengthPrefixed(aud, otherSei, idr)
        assertEquals(HevcInitNals.PREFIX_TRUNCATED, prefix(other, 4 + aud.size + 4 + otherSei.size + 2))
        assertEquals(HevcInitNals.PREFIX_NO_SEI, prefix(other, other.size))
    }

    @Test
    fun `malformed NAL lengths are reported as such`() {
        val zero = byteArrayOf(0, 0, 0, 0) + lengthPrefixed(sei)
        assertEquals(HevcInitNals.PREFIX_MALFORMED, prefix(zero, zero.size))
        val overrun = lengthPrefixed(aud).also { it[3] = 100 }
        assertEquals(HevcInitNals.PREFIX_MALFORMED, prefix(overrun, overrun.size))
        val trailing = lengthPrefixed(aud) + byteArrayOf(0, 0)
        assertEquals(HevcInitNals.PREFIX_MALFORMED, prefix(trailing, trailing.size))
        assertEquals(HevcInitNals.PREFIX_MALFORMED, HevcInitNals.preferredTransferInAuPrefix(ByteArray(8), 8, 8, 5))
    }

    @Test
    fun `the whole-sample walk keeps its null results`() {
        val au = lengthPrefixed(aud, sei, idr)
        assertEquals(18, HevcInitNals.preferredTransferInSample(au, au.size, 4))
        assertNull(HevcInitNals.preferredTransferInSample(lengthPrefixed(aud, idr), lengthPrefixed(aud, idr).size, 4))
        val overrun = lengthPrefixed(aud).also { it[3] = 100 }
        assertNull(HevcInitNals.preferredTransferInSample(overrun, overrun.size, 4))
    }

    @Test
    fun `the AU prefix keeps the parameter sets in order and the SEI`() {
        val vps = HevcColourFixtures.vps("atc_sei")
        val sps = HevcColourFixtures.sps("atc_sei")
        val pps = HevcColourFixtures.pps("atc_sei")
        val au = lengthPrefixed(aud, vps, sps, pps, sei, idr)
        val prefix = HevcInitNals.readAuPrefix(au, au.size, au.size, 4)
        assertEquals(HevcInitNals.PREFIX_COMPLETE, prefix.status)
        assertEquals(18, prefix.preferredTransfer)
        assertEquals(3, prefix.parameterSets.size)
        assertArrayEquals(sps, prefix.sps)
        val annexB = byteArrayOf(0, 0, 0, 1) + vps + byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
        assertArrayEquals(annexB, prefix.parameterSetsAnnexB())
        // A slice ends the walk: parameter sets after it are not this AU's prefix.
        val late = lengthPrefixed(idr, sps)
        assertNull(HevcInitNals.readAuPrefix(late, late.size, late.size, 4).sps)
    }
}
