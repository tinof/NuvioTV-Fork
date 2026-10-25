package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlgContractTest {

    private fun vui(case: String) = checkNotNull(HevcVuiColour.parse(HevcColourFixtures.sps(case))) { case }

    private fun container(
        primaries: Int? = null,
        transfer: Int? = null,
        matrix: Int? = null,
        range: Int? = null,
        hdrStaticInfo: ByteArray? = null,
    ) = ContainerColour(primaries, transfer, matrix, range, hdrStaticInfo)

    private val untaggedContainer = container()
    private val hlgContainer = container(primaries = 9, transfer = 18, matrix = 9, range = 1)

    private val hlgBase = ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(C.COLOR_TRANSFER_HLG)
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .build()

    private fun hlg84(vararg untagged: String) = HlgDecision.Hlg84(untagged.toList(), hlgBase)

    private fun resolve(container: ContainerColour, case: String?) =
        HlgContract.resolve(container, case?.let(::vui))

    @Test
    fun `HLG tagged in the VUI only is 8_4`() {
        assertEquals(hlg84(), resolve(untaggedContainer, "hlg_tagged"))
    }

    @Test
    fun `HLG in the container only, with an untagged VUI, is 8_4`() {
        assertEquals(hlg84(), resolve(hlgContainer, "untagged10"))
        assertEquals(hlg84("range", "matrix", "primaries"), resolve(container(transfer = 18), "untagged10"))
        assertEquals(hlg84("matrix", "primaries"), resolve(container(transfer = 18, range = 1), "untagged10"))
    }

    @Test
    fun `container and VUI that agree are 8_4`() {
        assertEquals(hlg84(), resolve(hlgContainer, "hlg_tagged"))
        // Unspecified container values (2, Range 0 and 3) are untagged, not contradicting.
        assertEquals(hlg84(), resolve(container(primaries = 2, transfer = 2, matrix = 2, range = 3), "hlg_tagged"))
        assertEquals(hlg84(), resolve(container(transfer = 18, range = 0), "hlg_tagged"))
    }

    @Test
    fun `a 10-bit RExt stream is accepted like Main 10`() {
        assertEquals(4, vui("hlg_still10").generalProfileIdc)
        assertEquals(hlg84(), resolve(untaggedContainer, "hlg_still10"))
    }

    @Test
    fun `a contradicting transfer is refused`() {
        assertEquals(
            HlgDecision.Refused("transfer 16 in the container, HLG in the SPS"),
            resolve(container(transfer = 16), "hlg_tagged")
        )
        assertEquals(
            HlgDecision.Refused("transfer 16 in the SPS, HLG in the container"),
            resolve(container(transfer = 18), "pq")
        )
        // atc_sei signals BT.2020 10-bit (14) in the VUI and HLG only in its SEI.
        assertEquals(
            HlgDecision.Refused("transfer 14 in the SPS, HLG in the container"),
            resolve(container(transfer = 18), "atc_sei")
        )
        assertEquals(
            HlgDecision.Refused("transfer 1 in the container, HLG in the SPS"),
            resolve(container(transfer = 1), "hlg_tagged")
        )
    }

    @Test
    fun `HLG with a BT_709 matrix is refused`() {
        assertEquals(HlgDecision.Refused("matrix 1 in the SPS"), resolve(untaggedContainer, "hlg_matrix709"))
        assertEquals(
            HlgDecision.Refused("matrix 1 in the container"),
            resolve(container(transfer = 18, matrix = 1), "untagged10")
        )
    }

    @Test
    fun `HLG with the BT_2020 constant-luminance matrix is refused`() {
        assertEquals(HlgDecision.Refused("matrix 10 in the SPS"), resolve(untaggedContainer, "hlg_matrix_cl"))
        assertEquals(
            HlgDecision.Refused("matrix 10 in the container"),
            resolve(container(transfer = 18, matrix = HlgContract.MATRIX_BT2020_CL), "untagged10")
        )
    }

    @Test
    fun `HLG with full range is refused`() {
        assertEquals(HlgDecision.Refused("range full in the SPS"), resolve(untaggedContainer, "hlg_full_range"))
        assertEquals(HlgDecision.Refused("range full in the SPS"), resolve(container(transfer = 18), "range_only10"))
        assertEquals(
            HlgDecision.Refused("range full in the container"),
            resolve(container(transfer = 18, range = 2), "untagged10")
        )
    }

    @Test
    fun `HLG with BT_709 primaries is refused`() {
        assertEquals(HlgDecision.Refused("primaries 1 in the SPS"), resolve(untaggedContainer, "hlg_prim709"))
        assertEquals(
            HlgDecision.Refused("primaries 1 in the container"),
            resolve(container(primaries = 1, transfer = 18), "untagged10")
        )
    }

    @Test
    fun `container and VUI stating different values for a field are refused`() {
        assertEquals(
            HlgDecision.Refused("matrix 9 in the container, 1 in the SPS"),
            resolve(container(matrix = 9), "hlg_matrix709")
        )
        assertEquals(
            HlgDecision.Refused("range limited in the container, full in the SPS"),
            resolve(container(range = 1), "hlg_full_range")
        )
        assertEquals(
            HlgDecision.Refused("primaries 9 in the container, 1 in the SPS"),
            resolve(container(primaries = 9), "hlg_prim709")
        )
    }

    @Test
    fun `8-bit HLG is refused`() {
        assertEquals(HlgDecision.Refused("bit depth 8/8, not 10"), resolve(untaggedContainer, "hlg_8bit"))
        assertEquals(HlgDecision.Refused("bit depth 8/8, not 10"), resolve(hlgContainer, "hlg_8bit"))
    }

    @Test
    fun `another HEVC profile is refused`() {
        val stillPicture = vui("hlg_tagged").copy(generalProfileIdc = 3)
        assertEquals(
            HlgDecision.Refused("HEVC profile 3, not Main 10"),
            HlgContract.resolve(untaggedContainer, stillPicture)
        )
        val tenBitWithEightBitChroma = vui("hlg_tagged").copy(bitDepthChroma = 8)
        assertEquals(
            HlgDecision.Refused("bit depth 10/8, not 10"),
            HlgContract.resolve(untaggedContainer, tenBitWithEightBitChroma)
        )
    }

    @Test
    fun `HLG in 4_2_2 or 4_4_4 is refused`() {
        assertEquals(2, vui("hlg_422").chromaFormatIdc)
        assertEquals(3, vui("hlg_444").chromaFormatIdc)
        assertEquals(HlgDecision.Refused("chroma_format_idc 2, not 4:2:0"), resolve(untaggedContainer, "hlg_422"))
        assertEquals(HlgDecision.Refused("chroma_format_idc 3, not 4:2:0"), resolve(hlgContainer, "hlg_444"))
        // Both are 10-bit RExt (profile 4) like hlg_still10, which is 4:2:0 and accepted.
        assertEquals(4, vui("hlg_444").generalProfileIdc)
        assertEquals(hlg84(), resolve(hlgContainer, "hlg_still10"))
        assertEquals(
            HlgDecision.Refused("chroma_format_idc 0, not 4:2:0"),
            HlgContract.resolve(untaggedContainer, vui("hlg_tagged").copy(chromaFormatIdc = 0))
        )
    }

    @Test
    fun `a Matroska Range outside 0 to 3 is a stated contradiction, not untagged`() {
        assertEquals(
            HlgDecision.Refused("range 4 in the container, not a Matroska Range code"),
            resolve(container(transfer = 18, range = 4), "untagged10")
        )
        assertEquals(
            HlgDecision.Refused("range 4 in the container, not a Matroska Range code"),
            resolve(container(range = 4), "hlg_tagged")
        )
        assertEquals(
            HlgDecision.Refused("range ${Int.MAX_VALUE} in the container, not a Matroska Range code"),
            resolve(container(range = Int.MAX_VALUE), "hlg_tagged")
        )
        // 0 and 3 stay untagged.
        assertEquals(hlg84("range", "matrix", "primaries"), resolve(container(transfer = 18, range = 3), "untagged10"))
        assertEquals(hlg84("range", "matrix", "primaries"), resolve(container(transfer = 18, range = 0), "untagged10"))
    }

    @Test
    fun `HLG without a readable SPS is refused`() {
        assertEquals(HlgDecision.Refused("no readable SPS"), resolve(hlgContainer, null))
        assertEquals(HlgDecision.Refused("no readable SPS"), resolve(container(transfer = 18), null))
    }

    @Test
    fun `no stated transfer is transfer unknown`() {
        assertEquals(HlgDecision.TransferUnknown, resolve(untaggedContainer, "untagged10"))
        assertEquals(HlgDecision.TransferUnknown, resolve(untaggedContainer, "range_only10"))
        assertEquals(HlgDecision.TransferUnknown, resolve(untaggedContainer, null))
        assertEquals(HlgDecision.TransferUnknown, resolve(container(primaries = 9, transfer = 2, matrix = 9, range = 1), "untagged10"))
    }

    @Test
    fun `PQ or another stated transfer is not HLG`() {
        assertEquals(HlgDecision.NotHlg, resolve(untaggedContainer, "pq"))
        assertEquals(HlgDecision.NotHlg, resolve(container(transfer = 16), null))
        assertEquals(HlgDecision.NotHlg, resolve(container(transfer = 16), "pq"))
        assertEquals(HlgDecision.NotHlg, resolve(container(transfer = 1), "untagged10"))
        // HLG signalled only in an alternative-transfer SEI: the VUI states BT.2020 10-bit.
        assertEquals(HlgDecision.NotHlg, resolve(untaggedContainer, "atc_sei"))
    }

    @Test
    fun `the base layer is HLG and carries the container's static metadata unchanged`() {
        val hdr = ByteArray(25) { it.toByte() }
        val decision = resolve(container(transfer = 18, hdrStaticInfo = hdr.copyOf()), "hlg_tagged")
        decision as HlgDecision.Hlg84
        val colorInfo = decision.baseLayerColorInfo
        assertEquals(C.COLOR_TRANSFER_HLG, colorInfo.colorTransfer)
        assertNotEquals(C.COLOR_TRANSFER_ST2084, colorInfo.colorTransfer)
        assertEquals(C.COLOR_SPACE_BT2020, colorInfo.colorSpace)
        assertEquals(C.COLOR_RANGE_LIMITED, colorInfo.colorRange)
        assertTrue(hdr.contentEquals(colorInfo.hdrStaticInfo))
        assertEquals(hlgBase, (resolve(container(transfer = 18), "hlg_tagged") as HlgDecision.Hlg84).baseLayerColorInfo)
    }

    @Test
    fun `every fixture without container colour gets the decision its VUI implies`() {
        val expected = mapOf(
            "hlg_tagged" to hlg84(),
            "untagged10" to HlgDecision.TransferUnknown,
            "pq" to HlgDecision.NotHlg,
            "hlg_matrix709" to HlgDecision.Refused("matrix 1 in the SPS"),
            "hlg_full_range" to HlgDecision.Refused("range full in the SPS"),
            "hlg_prim709" to HlgDecision.Refused("primaries 1 in the SPS"),
            "hlg_matrix_cl" to HlgDecision.Refused("matrix 10 in the SPS"),
            "hlg_8bit" to HlgDecision.Refused("bit depth 8/8, not 10"),
            "range_only10" to HlgDecision.TransferUnknown,
            "hlg_still10" to hlg84(),
            "atc_sei" to HlgDecision.NotHlg,
            "hlg_sublayers" to hlg84(),
            "hlg_sar_ext" to hlg84(),
            "hlg_conf_win" to hlg84(),
            "hlg_scaling_default" to hlg84(),
            "hlg_scaling_list" to hlg84(),
            "hlg_sps_rps" to hlg84(),
            "hlg_422" to HlgDecision.Refused("chroma_format_idc 2, not 4:2:0"),
            "hlg_444" to HlgDecision.Refused("chroma_format_idc 3, not 4:2:0"),
        )
        assertEquals(HevcColourFixtures.cases.toSet(), expected.keys)
        for ((case, decision) in expected) assertEquals(case, decision, resolve(untaggedContainer, case))
    }

    @Test
    fun `every other SPS layout without container colour gets the decision its VUI implies`() {
        val expected = mapOf(
            "m3_lpcm_sample" to hlg84(),
            "m3_mvhevc_hdr_view0" to HlgDecision.TransferUnknown,
            "m3_exoghi_10316" to HlgDecision.NotHlg,
            "m3_st_rps" to HlgDecision.NotHlg,
            "m3_hlg_1080p" to hlg84(),
            "m3_rps_pred_ts" to HlgDecision.TransferUnknown,
            "hand_long_term_refs" to hlg84(),
            "hand_sub_layer_ptl" to hlg84(),
        )
        assertEquals(HevcColourFixtures.moreSpsCases.toSet(), expected.keys)
        for ((case, decision) in expected) {
            val vui = checkNotNull(HevcVuiColour.parse(HevcColourFixtures.moreSps(case))) { case }
            assertEquals(case, decision, HlgContract.resolve(untaggedContainer, vui))
        }
    }

    @Test
    fun `the log line names the decision, the untagged fields and the SEI value`() {
        assertEquals(
            "HLG contract, track 1: HLG for 8.4, untagged accepted: range, matrix, primaries; " +
                "alternative transfer SEI 18 (not acted on)",
            HlgContract.logLine(1, hlg84("range", "matrix", "primaries"), 18)
        )
        assertEquals("HLG contract, track 2: HLG for 8.4", HlgContract.logLine(2, hlg84(), null))
        assertEquals(
            "HLG contract, track 3: HLG refused: no readable SPS",
            HlgContract.logLine(3, HlgDecision.Refused("no readable SPS"), null)
        )
        assertEquals(
            "HLG contract, track 4: transfer unknown; alternative transfer SEI 18 (not acted on)",
            HlgContract.logLine(4, HlgDecision.TransferUnknown, 18)
        )
        assertEquals("HLG contract, track 5: not HLG", HlgContract.logLine(5, HlgDecision.NotHlg, null))
    }

    // DV2.10: HLG in an alternative-transfer SEI over an HLG-compatible stated transfer.

    private fun withTransfer(case: String, transfer: Int?) = vui(case).copy(transferCharacteristics = transfer)

    private fun hlg84FromSei(vararg untagged: String) = HlgDecision.Hlg84(untagged.toList(), hlgBase, transferFromSei = true)

    @Test
    fun `an HLG SEI over every HLG-compatible SPS transfer is 8_4 from the SEI`() {
        assertEquals(setOf(1, 6, 14, 15), HlgContract.HLG_COMPATIBLE_TRANSFERS)
        for (transfer in listOf(1, 6, 14, 15)) {
            val vui = withTransfer("hlg_tagged", transfer)
            assertEquals("$transfer", HlgDecision.NotHlg, HlgContract.resolve(untaggedContainer, vui))
            assertEquals("$transfer", hlg84FromSei(), HlgContract.resolve(untaggedContainer, vui, 18))
        }
    }

    @Test
    fun `an HLG SEI over agreeing or HLG container tags is 8_4 from the SEI`() {
        val vui = vui("atc_sei")
        assertEquals(14, vui.transferCharacteristics)
        assertEquals(hlg84FromSei(), HlgContract.resolve(container(primaries = 9, transfer = 14, matrix = 9, range = 1), vui, 18))
        // Container HLG over SPS 14: a contradiction without the SEI.
        assertTrue(HlgContract.resolve(hlgContainer, vui) is HlgDecision.Refused)
        assertEquals(hlg84FromSei(), HlgContract.resolve(hlgContainer, vui, 18))
        // Container BT.709 transfer over SPS 14: both HLG-compatible.
        assertEquals(hlg84FromSei(), HlgContract.resolve(container(transfer = 1), vui, 18))
    }

    @Test
    fun `an HLG SEI with no stated transfer is 8_4 from the SEI`() {
        assertEquals(HlgDecision.TransferUnknown, resolve(untaggedContainer, "untagged10"))
        assertEquals(
            hlg84FromSei("range", "matrix", "primaries"),
            HlgContract.resolve(untaggedContainer, vui("untagged10"), 18)
        )
        assertEquals(hlg84FromSei(), HlgContract.resolve(untaggedContainer, withTransfer("hlg_tagged", 2), 18))
    }

    @Test
    fun `a stated transfer outside the compatible set keeps the decision without the SEI`() {
        // PQ in the SPS.
        assertEquals(HlgContract.resolve(untaggedContainer, vui("pq")), HlgContract.resolve(untaggedContainer, vui("pq"), 18))
        // PQ in the container over SPS 14: not lifted, so not HLG.
        val pqOver14 = container(transfer = 16)
        assertEquals(HlgContract.resolve(pqOver14, vui("atc_sei")), HlgContract.resolve(pqOver14, vui("atc_sei"), 18))
        // Linear (8) in the SPS.
        val linear = withTransfer("hlg_tagged", 8)
        assertEquals(HlgDecision.NotHlg, HlgContract.resolve(untaggedContainer, linear, 18))
    }

    @Test
    fun `an SEI that is not HLG or no SEI changes nothing`() {
        val vui = vui("atc_sei")
        for (sei in listOf(null, 1, 14, 16)) {
            assertEquals("$sei", HlgDecision.NotHlg, HlgContract.resolve(untaggedContainer, vui, sei))
        }
        // HLG stated already: the SEI does not mark the decision.
        val tagged = HlgContract.resolve(untaggedContainer, vui("hlg_tagged"), 18) as HlgDecision.Hlg84
        assertFalse(tagged.transferFromSei)
    }

    @Test
    fun `the other field rules still refuse under an HLG SEI`() {
        val matrix709 = vui("hlg_matrix709").copy(transferCharacteristics = 14)
        val decision = HlgContract.resolve(untaggedContainer, matrix709, 18)
        assertTrue(decision.toString(), decision is HlgDecision.Refused)
        assertTrue((decision as HlgDecision.Refused).reason.contains("matrix"))
        assertTrue(decision.transferFromSei)
        assertEquals(
            "HLG contract, track 1: HLG from the alternative transfer SEI refused: ${decision.reason}",
            HlgContract.logLine(1, decision, 18)
        )
        // Without the SEI the same stream is simply not HLG.
        assertEquals(HlgDecision.NotHlg, HlgContract.resolve(untaggedContainer, matrix709))
        // Stated HLG that is refused without the SEI: the SEI changed nothing and is not credited.
        val statedRefused = HlgContract.resolve(untaggedContainer, vui("hlg_matrix709"))
        assertTrue(statedRefused is HlgDecision.Refused)
        assertEquals(statedRefused, HlgContract.resolve(untaggedContainer, vui("hlg_matrix709"), 18))
        assertFalse((statedRefused as HlgDecision.Refused).transferFromSei)
        val fullRange = vui("hlg_full_range").copy(transferCharacteristics = 14)
        assertTrue(HlgContract.resolve(untaggedContainer, fullRange, 18) is HlgDecision.Refused)
    }

    @Test
    fun `the log line says when the SEI decided and when it was not acted on`() {
        assertEquals(
            "HLG contract, track 1: HLG for 8.4 from the alternative transfer SEI",
            HlgContract.logLine(1, hlg84FromSei(), 18)
        )
        assertEquals(
            "HLG contract, track 1: HLG for 8.4 from the alternative transfer SEI, untagged accepted: range",
            HlgContract.logLine(1, hlg84FromSei("range"), 18)
        )
        assertEquals(
            "HLG contract, track 1: not HLG; alternative transfer SEI 18 (not acted on)",
            HlgContract.logLine(1, HlgDecision.NotHlg, 18)
        )
        assertEquals("HLG contract, track 1: HLG for 8.4; alternative transfer SEI 18 (not acted on)", HlgContract.logLine(1, hlg84(), 18))
    }
}
