package com.nuvio.tv.core.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The signalling decision table (ROADMAP DV2.3) and its Dolby Vision level table. */
class DvSignallingTest {

    private fun vui(case: String) = checkNotNull(HevcVuiColour.parse(HevcColourFixtures.sps(case))) { case }

    private fun container(primaries: Int? = null, transfer: Int? = null, matrix: Int? = null, range: Int? = null) =
        ContainerColour(primaries, transfer, matrix, range)

    private fun decide(
        container: ContainerColour,
        case: String,
        width: Int = 3840,
        height: Int = 2160,
        durationNs: Long = 41_708_333,
        encrypted: Boolean = false,
    ): DvSignallingDecision {
        val vui = vui(case)
        return DvSignalling.decide(encrypted, container, vui, HlgContract.resolve(container, vui), width, height, durationNs)
    }

    @Test
    fun `the level follows FFmpeg's pixel-rate and width limits`() {
        // 4K: 24 (and 23.976) fps is 06, 25 and 30 are 07, 48 is 08, 50 and 60 are 09, 120 is 10.
        val uhd = listOf(
            41_708_333L to 6, 41_666_666L to 6, 41_666_667L to 6, 40_000_000L to 7, 33_366_667L to 7,
            33_333_333L to 7, 20_833_333L to 8, 20_000_000L to 9, 16_683_333L to 9, 16_666_666L to 9,
            8_333_333L to 10,
        )
        for ((ns, level) in uhd) {
            assertEquals("3840x2160 at $ns ns", level, DvSignalling.level(3840, 2160, DvSignalling.declaredFrameRate(ns)!!))
        }
        assertEquals(1, DvSignalling.level(1280, 720, 24.0))
        assertEquals(2, DvSignalling.level(1280, 720, 30.0))
        assertEquals(3, DvSignalling.level(1920, 1080, 24_000.0 / 1001))
        assertEquals(4, DvSignalling.level(1920, 1080, 25.0))
        assertEquals(5, DvSignalling.level(1920, 1080, 60.0))
        // The pixel rate of 3840x1600 at 24 fps is above level 05, so it is 06 although it fits 05's width.
        assertEquals(6, DvSignalling.level(3840, 1600, 24.0))
        // A 2560-wide stream fits level 04 by width; 1440 lines at 24 fps exceed its pixel rate.
        assertEquals(5, DvSignalling.level(2560, 1440, 24.0))
        // Above 3840 the 8K levels take over: 11 holds 7680x4320 at 30 fps.
        assertEquals(11, DvSignalling.level(4096, 2160, 24.0))
        assertEquals(11, DvSignalling.level(7680, 4320, 30.0))
        assertEquals(12, DvSignalling.level(7680, 4320, 60.0))
        assertEquals(13, DvSignalling.level(7680, 4320, 120.0))
    }

    @Test
    fun `no level describes an unknown frame size or a stream above level 13`() {
        assertNull(DvSignalling.level(-1, 2160, 24.0))
        assertNull(DvSignalling.level(3840, 0, 24.0))
        assertNull(DvSignalling.level(8192, 4320, 24.0))
        assertNull(DvSignalling.level(7680, 4320, 121.0))
        assertEquals(
            DvSignallingDecision.Native("no Dolby Vision level for 8192x4320 at 23.976 fps"),
            decide(container(transfer = 16), "pq", width = 8192, height = 4320)
        )
    }

    @Test
    fun `a missing or out-of-range DefaultDuration counts as 60 fps`() {
        assertNull(DvSignalling.declaredFrameRate(0))
        assertNull(DvSignalling.declaredFrameRate(-1))
        // Below 5 fps (a still image) and above 121 fps are the extractor's own Format.frameRate bounds.
        assertNull(DvSignalling.declaredFrameRate(250_000_000))
        assertNull(DvSignalling.declaredFrameRate(8_000_000))
        assertEquals("dvhe.08.09", (decide(container(), "pq", durationNs = 0) as DvSignallingDecision.Convert).codecs)
        assertEquals("dvhe.08.05", (decide(container(), "pq", 1920, 1080, 0) as DvSignallingDecision.Convert).codecs)
    }

    @Test
    fun `a declared rate outside the Format bounds still sets the level`() {
        // 125 fps (8 000 000 ns) is above the 121 fps bound of Format.frameRate but is a declared rate.
        assertEquals("dvhe.08.08", (decide(container(), "pq", 1920, 1080, 8_000_000) as DvSignallingDecision.Convert).codecs)
        // 4 fps (250 000 000 ns) is below it: 3840x2160 at 4 fps fits level 01's pixel rate but not its width.
        assertEquals("dvhe.08.05", (decide(container(), "pq", 3840, 2160, 250_000_000) as DvSignallingDecision.Convert).codecs)
    }

    @Test
    fun `PQ is 8_1 over a BT_2020 limited PQ base layer`() {
        val convert = decide(container(), "pq") as DvSignallingDecision.Convert
        assertEquals(DvSignallingDecision.Target.PROFILE_8_1, convert.target)
        assertEquals("dvhe.08.06", convert.codecs)
        assertEquals(6, convert.level)
        assertEquals(C.COLOR_SPACE_BT2020, convert.colorInfo.colorSpace)
        assertEquals(C.COLOR_RANGE_LIMITED, convert.colorInfo.colorRange)
        assertEquals(C.COLOR_TRANSFER_ST2084, convert.colorInfo.colorTransfer)
        assertEquals(emptyList<String>(), convert.untaggedAccepted)
    }

    @Test
    fun `the PQ row uses the HLG contract's field rules with transfer 16`() {
        // Untagged fields are accepted and named.
        assertEquals(
            PqDecision.Pq81(listOf("range", "matrix", "primaries"), pqBase()),
            HlgContract.resolvePq(container(transfer = 16), vui("untagged10"))
        )
        // A stated contradiction refuses; unspecified values are untagged.
        assertEquals(PqDecision.Refused("matrix 1 in the container"), HlgContract.resolvePq(container(transfer = 16, matrix = 1), vui("untagged10")))
        assertEquals(PqDecision.Refused("range full in the container"), HlgContract.resolvePq(container(transfer = 16, range = 2), vui("untagged10")))
        assertEquals(PqDecision.Refused("primaries 1 in the container"), HlgContract.resolvePq(container(primaries = 1, transfer = 16), vui("untagged10")))
        assertEquals(PqDecision.Refused("matrix 10 in the container"), HlgContract.resolvePq(container(transfer = 16, matrix = 10), vui("untagged10")))
        assertEquals(PqDecision.Pq81(emptyList(), pqBase()), HlgContract.resolvePq(container(primaries = 2, transfer = 2, matrix = 2, range = 0), vui("pq")))
        assertEquals(
            PqDecision.Refused("transfer 18 in the SPS, PQ in the container"),
            HlgContract.resolvePq(container(transfer = 16), vui("hlg_tagged"))
        )
        assertEquals(PqDecision.Refused("transfer 18 in the container, PQ in the SPS"), HlgContract.resolvePq(container(transfer = 18), vui("pq")))
        // The 8-bit and 4:2:2 fixtures are HLG-tagged, so the PQ contract refuses them on the transfer first.
        assertEquals(PqDecision.Refused("transfer 18 in the SPS, PQ in the container"), HlgContract.resolvePq(container(transfer = 16), vui("hlg_8bit")))
        assertEquals(PqDecision.Refused("no readable SPS"), HlgContract.resolvePq(container(transfer = 16), null))
        assertEquals(PqDecision.NotPq, HlgContract.resolvePq(container(), vui("hlg_tagged")))
        assertEquals(PqDecision.TransferUnknown, HlgContract.resolvePq(container(), vui("untagged10")))
    }

    @Test
    fun `HLG is 8_4 with the contract's base layer, refused HLG and unknown transfers are native`() {
        val hlg = decide(container(), "hlg_tagged") as DvSignallingDecision.Convert
        assertEquals(DvSignallingDecision.Target.PROFILE_8_4, hlg.target)
        assertEquals((HlgContract.resolve(container(), vui("hlg_tagged")) as HlgDecision.Hlg84).baseLayerColorInfo, hlg.colorInfo)
        assertEquals(DvSignallingDecision.Native("HLG refused: matrix 1 in the SPS"), decide(container(), "hlg_matrix709"))
        assertEquals(DvSignallingDecision.Native("transfer unknown at track init"), decide(container(), "untagged10"))
        assertEquals(DvSignallingDecision.Native("transfer neither PQ nor HLG"), decide(container(), "atc_sei"))
        assertEquals(DvSignallingDecision.Native("transfer neither PQ nor HLG"), decide(container(transfer = 1), "untagged10"))
        assertEquals(DvSignallingDecision.Native("PQ refused: matrix 1 in the container"), decide(container(transfer = 16, matrix = 1), "untagged10"))
    }

    @Test
    fun `protected content is native before anything else`() {
        assertEquals(DvSignallingDecision.Native("protected content"), decide(container(), "pq", encrypted = true))
        assertEquals(DvSignallingDecision.Native("protected content"), decide(container(), "hlg_matrix709", encrypted = true))
    }

    @Test
    fun `the log line names the target, the codecs and the untagged fields`() {
        assertEquals(
            "DV signalling, track 1: Dolby Vision 8.1 as dvhe.08.06",
            DvSignalling.logLine(1, decide(container(), "pq"))
        )
        assertEquals(
            "DV signalling, track 2: Dolby Vision 8.4 as dvhe.08.06, untagged accepted: range, matrix, primaries",
            DvSignalling.logLine(2, decide(container(transfer = 18), "untagged10"))
        )
        assertEquals(
            "DV signalling, track 3: native, transfer unknown at track init",
            DvSignalling.logLine(3, decide(container(), "untagged10"))
        )
    }

    private fun pqBase() = androidx.media3.common.ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .build()
}
