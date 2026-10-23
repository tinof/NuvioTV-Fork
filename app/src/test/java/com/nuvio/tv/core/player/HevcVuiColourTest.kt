package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.container.NalUnitUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcVuiColourTest {

    private fun vui(
        primaries: Int?,
        transfer: Int?,
        matrix: Int?,
        signalType: Boolean = true,
        fullRange: Boolean = false,
        profile: Int = 2,
        bitDepth: Int = 10,
    ) = HevcVuiColour(
        generalProfileIdc = profile,
        chromaFormatIdc = 1,
        bitDepthLuma = bitDepth,
        bitDepthChroma = bitDepth,
        videoSignalTypePresent = signalType,
        videoFullRange = fullRange,
        colourDescriptionPresent = primaries != null,
        colourPrimaries = primaries,
        transferCharacteristics = transfer,
        matrixCoeffs = matrix,
    )

    private val expected = mapOf(
        "hlg_tagged" to vui(9, 18, 9),
        "untagged10" to vui(null, null, null, signalType = false),
        "pq" to vui(9, 16, 9),
        "hlg_matrix709" to vui(9, 18, 1),
        "hlg_full_range" to vui(9, 18, 9, fullRange = true),
        "hlg_prim709" to vui(1, 18, 9),
        "hlg_matrix_cl" to vui(9, 18, 10),
        "hlg_8bit" to vui(9, 18, 9, profile = 1, bitDepth = 8),
        "range_only10" to vui(null, null, null, fullRange = true),
        "hlg_still10" to vui(9, 18, 9, profile = 4),
        "atc_sei" to vui(9, 14, 9),
    )

    @Test
    fun `every fixture SPS gives the colour fields x265 was asked for`() {
        assertEquals(expected.keys, HevcColourFixtures.cases.toSet())
        for ((case, want) in expected) {
            assertEquals(case, want, HevcVuiColour.parse(HevcColourFixtures.sps(case)))
        }
    }

    @Test
    fun `primaries, transfer, range and bit depth agree with Media3's own SPS parser`() {
        for (case in HevcColourFixtures.cases) {
            val sps = HevcColourFixtures.sps(case)
            val ours = checkNotNull(HevcVuiColour.parse(sps)) { case }
            val media3 = NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null)
            assertEquals(case, media3.colorSpace, ours.colourPrimaries?.let(ColorInfo::isoColorPrimariesToColorSpace) ?: Format.NO_VALUE)
            assertEquals(
                case,
                media3.colorTransfer,
                ours.transferCharacteristics?.let(ColorInfo::isoTransferCharacteristicsToColorTransfer) ?: Format.NO_VALUE
            )
            val range = when {
                !ours.videoSignalTypePresent -> Format.NO_VALUE
                ours.videoFullRange -> C.COLOR_RANGE_FULL
                else -> C.COLOR_RANGE_LIMITED
            }
            // Media3 sets the range only when the VUI is present, from video_full_range_flag.
            assertEquals(case, media3.colorRange, range)
            assertEquals(case, media3.bitDepthLumaMinus8 + 8, ours.bitDepthLuma)
            assertEquals(case, media3.bitDepthChromaMinus8 + 8, ours.bitDepthChroma)
            assertEquals(case, media3.chromaFormatIdc, ours.chromaFormatIdc)
            assertEquals(case, media3.profileTierLevel!!.generalProfileIdc, ours.generalProfileIdc)
        }
    }

    @Test
    fun `the fixtures carry emulation prevention bytes before the VUI`() {
        // 00 00 03 inside the profile_tier_level: the walk only lands on the VUI if they are removed.
        val sps = HevcColourFixtures.sps("hlg_tagged")
        val hasEpb = (0 until sps.size - 2).any { sps[it] == 0.toByte() && sps[it + 1] == 0.toByte() && sps[it + 2] == 3.toByte() }
        assertTrue(hasEpb)
    }

    @Test
    fun `a truncated SPS is unreadable, never misread`() {
        for (case in HevcColourFixtures.cases) {
            val sps = HevcColourFixtures.sps(case)
            val full = HevcVuiColour.parse(sps)
            for (length in 0 until sps.size) {
                val parsed = HevcVuiColour.parse(sps.copyOf(length))
                if (parsed != null) assertEquals("$case cut at $length", full, parsed)
            }
            assertNull(case, HevcVuiColour.parse(sps.copyOf(12)))
        }
    }

    @Test
    fun `an SPS inside a larger buffer is read between offset and limit`() {
        val sps = HevcColourFixtures.sps("hlg_tagged")
        val buffer = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1, 0x44, 0x01)
        assertEquals(expected["hlg_tagged"], HevcVuiColour.parse(buffer, 4, 4 + sps.size))
    }

    @Test
    fun `a layered SPS or another NAL type is not read`() {
        val layered = HevcColourFixtures.sps("hlg_tagged")
        layered[1] = 0x09 // nuh_layer_id 1, nuh_temporal_id_plus1 1
        assertNull(HevcVuiColour.parse(layered))
        assertNull(HevcVuiColour.parse(HevcColourFixtures.vps("hlg_tagged")))
        assertNull(HevcVuiColour.parse(HevcColourFixtures.pps("hlg_tagged")))
        assertNull(HevcVuiColour.parse(ByteArray(0)))
        assertNotNull(HevcVuiColour.parse(HevcColourFixtures.sps("hlg_tagged")))
    }

    @Test
    fun `garbage after the header is unreadable`() {
        assertNull(HevcVuiColour.parse(byteArrayOf(0x42, 0x01) + ByteArray(40) { 0xFF.toByte() }))
        assertNull(HevcVuiColour.parse(byteArrayOf(0x42, 0x01) + ByteArray(40)))
    }
}
