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

    // DV3.1: the HDR10+ T.35 payloads an hvcC or an AU carries.

    private val hdr10PlusPayload =
        byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x40, 0x00, 0x00, 0x01, 0x22)

    /** A prefix SEI NAL of [messages] (payloadType, RBSP payload), escaped as a coded NAL is. */
    private fun seiNal(vararg messages: Pair<Int, ByteArray>): ByteArray {
        val rbsp = mutableListOf<Byte>()
        for ((type, payload) in messages) {
            rbsp += type.toByte()
            rbsp += payload.size.toByte()
            rbsp += payload.toList()
        }
        rbsp += 0x80.toByte()
        val escaped = mutableListOf<Byte>(0x4E, 0x01)
        var zeros = 0
        for (b in rbsp) {
            if (zeros == 2 && (b.toInt() and 0xFF) <= 3) {
                escaped += 3
                zeros = 0
            }
            escaped += b
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return escaped.toByteArray()
    }

    @Test
    fun `HDR10+ payloads come back unescaped from the country code on, every message in order`() {
        val other = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x7F)
        val nal = seiNal(5 to ByteArray(16), 4 to hdr10PlusPayload, 147 to byteArrayOf(18), 4 to other)
        // The payload holds 00 00 01: the coded NAL carries an emulation prevention byte in it.
        assertEquals(-1, String(nal, Charsets.ISO_8859_1).indexOf(String(hdr10PlusPayload, Charsets.ISO_8859_1)))
        val payloads = HevcInitNals.hdr10PlusPayloadsInSei(nal)
        assertEquals(2, payloads.size)
        assertArrayEquals(hdr10PlusPayload, payloads[0])
        assertArrayEquals(other, payloads[1])
    }

    @Test
    fun `a T35 message of another provider or another payload type is not a candidate`() {
        val dolby = byteArrayOf(0xB5.toByte(), 0x00, 0x3B, 0x00, 0x00, 0x08, 0x00)
        assertEquals(0, HevcInitNals.hdr10PlusPayloadsInSei(seiNal(4 to dolby)).size)
        // The HDR10+ bytes in a user_data_unregistered message are not a T.35 message.
        assertEquals(0, HevcInitNals.hdr10PlusPayloadsInSei(seiNal(5 to hdr10PlusPayload)).size)
    }

    @Test
    fun `an hvcC prefix SEI array can carry HDR10+`() {
        val hvcC = HevcColourFixtures.hvcC(
            33 to listOf(HevcColourFixtures.sps("pq")),
            39 to listOf(seiNal(4 to hdr10PlusPayload))
        )
        assertArrayEquals(hdr10PlusPayload, HevcInitNals.hdr10PlusPayloads(initData(hvcC)).single())
        assertEquals(0, HevcInitNals.hdr10PlusPayloads(initData(HevcColourFixtures.hvcCOf("pq"))).size)
    }

    @Test
    fun `an AU's HDR10+ payloads are those before its first slice`() {
        val before = seiNal(4 to hdr10PlusPayload)
        val after = seiNal(4 to byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x55))
        val au = lengthPrefixed(aud, before, idr, after)
        assertArrayEquals(hdr10PlusPayload, HevcInitNals.hdr10PlusPayloadsInAu(au, au.size, 4).single())
        // The AU prefix walk of the early scan collects them too.
        assertArrayEquals(hdr10PlusPayload, HevcInitNals.readAuPrefix(au, au.size, au.size, 4).hdr10PlusPayloads.single())
        // A malformed length ends the walk with what was found before it.
        val broken = lengthPrefixed(before) + byteArrayOf(0, 0, 0x7F, 0x7F, 0x26)
        assertArrayEquals(hdr10PlusPayload, HevcInitNals.hdr10PlusPayloadsInAu(broken, broken.size, 4).single())
        assertEquals(0, HevcInitNals.hdr10PlusPayloadsInAu(au, au.size, 0).size)
        assertEquals(0, HevcInitNals.hdr10PlusPayloadsInAu(lengthPrefixed(aud, idr), 13, 4).size)
    }

    // ROADMAP DV3.4: mastering display (137) and content light level (144), docs/dv-conversion/DESIGN.md 2.4.

    private val r = floatArrayOf(0.68f, 0.32f)
    private val g = floatArrayOf(0.265f, 0.69f)
    private val b = floatArrayOf(0.15f, 0.06f)
    private val d65 = floatArrayOf(0.3127f, 0.329f)

    private fun staticOf(nal: ByteArray) =
        HevcInitNals.readAuPrefix(lengthPrefixed(nal, idr), Int.MAX_VALUE, lengthPrefixed(nal, idr).size, 4).seiStatic

    private fun assertRgbw(expected: FloatArray, actual: FloatArray?) {
        assertEquals(8, actual!!.size)
        for (i in expected.indices) assertEquals("value $i", expected[i], actual[i], 0.00001f)
    }

    @Test
    fun `the mastering display SEI comes in G, B, R and goes out as R, G, B, W`() {
        val sei = staticOf(fakeSeiNal(137 to fakeMasteringPayload()))!!
        assertRgbw(r + g + b + d65, sei.masteringPrimaries)
        assertEquals(1000f, sei.masteringMaxNits!!, 0f)
        assertEquals(0.0001f, sei.masteringMinNits!!, 0.00000001f)
        assertNull(sei.maxContentLightLevel)
        assertNull(sei.maxFrameAverageLightLevel)
    }

    @Test
    fun `values that differ per position keep their position through the reorder`() {
        val pairs = listOf(10000 to 40000, 7000 to 2000, 36000 to 15000)
        val sei = staticOf(fakeSeiNal(137 to fakeMasteringPayload(pairs, 15000 to 16000)))!!
        assertRgbw(
            floatArrayOf(0.72f, 0.3f, 0.2f, 0.8f, 0.14f, 0.04f, 0.3f, 0.32f),
            sei.masteringPrimaries,
        )
    }

    @Test
    fun `primaries in another order are sorted by chromaticity, as MediaInfo does`() {
        val (green, blue, red) = SEI_P3_GBR
        for (order in listOf(listOf(red, green, blue), listOf(blue, green, red), listOf(green, red, blue))) {
            val sei = staticOf(fakeSeiNal(137 to fakeMasteringPayload(order)))!!
            assertRgbw(r + g + b + d65, sei.masteringPrimaries)
        }
    }

    @Test
    fun `primaries that do not sort into one of each keep the spec's order`() {
        val pairs = listOf(30000 to 10000, 31000 to 11000, 32000 to 12000)
        val sei = staticOf(fakeSeiNal(137 to fakeMasteringPayload(pairs)))!!
        assertRgbw(
            floatArrayOf(0.64f, 0.24f, 0.6f, 0.2f, 0.62f, 0.22f, 0.3127f, 0.329f),
            sei.masteringPrimaries,
        )
    }

    @Test
    fun `emulation prevention in the luminance is undone`() {
        // Minimum 1: the payload ends 00 00 00 01, which a coded NAL escapes.
        val nal = fakeSeiNal(137 to fakeMasteringPayload())
        assertEquals(nal.size, fakeMasteringPayload().size + 2 + 2 + 1 + 1)
        val sei = staticOf(nal)!!
        assertEquals(0.0001f, sei.masteringMinNits!!, 0.00000001f)
        assertEquals(1000f, sei.masteringMaxNits!!, 0f)
        // A light level with 00 00 01 in it, escaped too.
        val cll = staticOf(fakeSeiNal(144 to byteArrayOf(0, 0, 1, 0)))!!
        assertNull(cll.maxContentLightLevel)
        assertEquals(256, cll.maxFrameAverageLightLevel)
    }

    @Test
    fun `luminance is unsigned 32 bits`() {
        val sei = staticOf(fakeSeiNal(137 to fakeMasteringPayload(maxUnits = 0xFFFF_FFFFL, minUnits = 0x8000_0000L)))!!
        assertEquals(429_496.72f, sei.masteringMaxNits!!, 0.1f)
        assertEquals(214_748.36f, sei.masteringMinNits!!, 0.1f)
    }

    @Test
    fun `a short message is ignored and the walk goes on, a longer one counts`() {
        val short = staticOf(fakeSeiNal(137 to ByteArray(20) { 0x11 }, 144 to fakeLightLevelPayload(108, 53)))!!
        assertNull(short.masteringPrimaries)
        assertNull(short.masteringMaxNits)
        assertEquals(108, short.maxContentLightLevel)
        assertNull(staticOf(fakeSeiNal(144 to byteArrayOf(0, 108, 0))))
        val long = staticOf(fakeSeiNal(137 to fakeMasteringPayload() + byteArrayOf(0x11, 0x22)))!!
        assertEquals(1000f, long.masteringMaxNits!!, 0f)
        val longCll = staticOf(fakeSeiNal(144 to fakeLightLevelPayload(108, 53) + byteArrayOf(0x11, 0x22)))!!
        assertEquals(53, longCll.maxFrameAverageLightLevel)
    }

    @Test
    fun `a message that overruns the NAL, or no static SEI at all, gives nothing`() {
        val whole = fakeSeiNal(137 to fakeMasteringPayload())
        assertNull(staticOf(whole.copyOf(whole.size - 6)))
        assertNull(staticOf(seiNal(1 to byteArrayOf(1, 2, 3))))
        assertNull(HevcInitNals.seiStatic(null))
        assertNull(HevcInitNals.seiStatic(initData(HevcColourFixtures.hvcCOf("pq"))))
    }

    @Test
    fun `a zero or 65535 light level is not stated`() {
        val none = staticOf(fakeSeiNal(144 to fakeLightLevelPayload(0, 0)))
        assertNull(none)
        val cll = staticOf(fakeSeiNal(144 to fakeLightLevelPayload(0, 53)))!!
        assertNull(cll.maxContentLightLevel)
        assertEquals(53, cll.maxFrameAverageLightLevel)
        val fall = staticOf(fakeSeiNal(144 to fakeLightLevelPayload(65535, 65535)))
        assertNull(fall)
        assertEquals(12000, staticOf(fakeSeiNal(144 to fakeLightLevelPayload(12000, 0)))!!.maxContentLightLevel)
    }

    @Test
    fun `the first mastering display and the last light level win, in one NAL and across NALs`() {
        val second = fakeMasteringPayload(maxUnits = 40_000_000, minUnits = 50)
        val oneNal = staticOf(
            fakeSeiNal(
                137 to fakeMasteringPayload(), 144 to fakeLightLevelPayload(108, 53),
                137 to second, 144 to fakeLightLevelPayload(500, 300),
            )
        )!!
        assertEquals(1000f, oneNal.masteringMaxNits!!, 0f)
        assertEquals(500, oneNal.maxContentLightLevel)
        val au = lengthPrefixed(
            fakeSeiNal(137 to fakeMasteringPayload(), 144 to fakeLightLevelPayload(108, 53)),
            fakeSeiNal(137 to second, 144 to fakeLightLevelPayload(500, 300)),
            idr,
        )
        val twoNals = HevcInitNals.readAuPrefix(au, au.size, au.size, 4).seiStatic!!
        assertEquals(1000f, twoNals.masteringMaxNits!!, 0f)
        assertEquals(300, twoNals.maxFrameAverageLightLevel)
    }

    @Test
    fun `the hvcC comes before the first AU in reading order`() {
        val hvcC = HevcInitNals.seiStaticInSei(fakeStaticSei())!!
        val au = HevcInitNals.seiStaticInSei(
            fakeSeiNal(137 to fakeMasteringPayload(maxUnits = 40_000_000), 144 to fakeLightLevelPayload(500, 0))
        )!!
        val merged = hvcC.then(au)
        assertEquals(1000f, merged.masteringMaxNits!!, 0f)
        assertEquals(500, merged.maxContentLightLevel)
        // A field the later message does not state stays.
        assertEquals(53, merged.maxFrameAverageLightLevel)
        assertEquals(40.0f * 100, (DvSeiStatic().then(au)).masteringMaxNits!!, 0f)
        assertEquals(hvcC, hvcC.then(null))
    }

    @Test
    fun `the hvcC's prefix SEI array is read`() {
        val hvcC = HevcColourFixtures.hvcC(
            32 to listOf(HevcColourFixtures.vps("pq")),
            33 to listOf(HevcColourFixtures.sps("pq")),
            34 to listOf(HevcColourFixtures.pps("pq")),
            39 to listOf(fakeStaticSei()),
        )
        val sei = HevcInitNals.seiStatic(initData(hvcC))!!
        assertRgbw(r + g + b + d65, sei.masteringPrimaries)
        assertEquals(108, sei.maxContentLightLevel)
    }

    @Test
    fun `the AU prefix stops at the first slice and keeps what it read before a truncation`() {
        val after = lengthPrefixed(idr, fakeStaticSei())
        assertNull(HevcInitNals.readAuPrefix(after, after.size, after.size, 4).seiStatic)
        val sei = fakeStaticSei()
        val filler = seiNal(5 to ByteArray(200) { 7 })
        val au = lengthPrefixed(sei, filler, idr)
        val truncated = HevcInitNals.readAuPrefix(au, sei.size + 4 + 10, au.size, 4)
        assertEquals(HevcInitNals.PREFIX_TRUNCATED, truncated.status)
        assertEquals(108, truncated.seiStatic!!.maxContentLightLevel)
        // A NAL cut by the window is not read.
        val cut = HevcInitNals.readAuPrefix(au, sei.size, au.size, 4)
        assertEquals(HevcInitNals.PREFIX_TRUNCATED, cut.status)
        assertNull(cut.seiStatic)
    }
}
