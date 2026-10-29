package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.Handle
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.Level6
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu
import com.nuvio.tv.core.player.DoviRpuBuilder.SourceRange
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HDR10+ → 8.1 provider of ROADMAP DV3.1 (`docs/dv-conversion/DESIGN.md` 2.4) on a fake library: the
 * JVM has no libnuvio_rpu.so, and `cargo test` in native/rpu_builder shows the library's L1, keys and
 * RPUs equal `dovi_tool`. Here: which tracks convert, the params, and the scene and gap rules over AUs
 * read in decode order.
 */
class Hdr10PlusDvRpuSourceTest {

    private val fake = FakeHdr10PlusNative()

    @Test
    fun `a PQ track converts only with an HDR10+ payload the library reads`() {
        val source = Hdr10PlusDvRpuSource(native = fake)
        assertEquals(
            DvRpuSource.Prepared.Unavailable("no HDR10+ SEI in the hvcC or the first AU (static HDR10 is DV3.3)"),
            source.prepare(P81, DvTrackFacts())
        )
        assertEquals(
            DvRpuSource.Prepared.Unavailable("the first AU's HDR10+ SEI: NOT_HDR10_PLUS"),
            source.prepare(P81, DvTrackFacts(listOf(notHdr10Plus())))
        )
        assertEquals(
            DvRpuSource.Prepared.Unavailable("the first AU's HDR10+ SEI: HDR10_PLUS_PARSE"),
            source.prepare(P81, DvTrackFacts(listOf(payload(0xEE))))
        )
        assertTrue(fake.created.isEmpty())
        // A message that is not HDR10+ is skipped; the next one counts.
        val ready = source.prepare(P81, DvTrackFacts(listOf(notHdr10Plus(), payload(3, 7))))
        assertTrue(ready is DvRpuSource.Prepared.Ready)
        ready as DvRpuSource.Prepared.Ready
        assertEquals(listOf(L1(0, 1007, 3079)), fake.rendered)
        assertTrue(ready.label, ready.label.startsWith("HDR10+ 8.1, peak histogram, CM V40, L9 primaries 2, L6 1000/50/1000/400, source range 50..1000"))
        assertEquals(2, ready.rpus.minNalLengthFieldLength)
    }

    @Test
    fun `the switch and the HLG target keep the track native`() {
        val facts = DvTrackFacts(listOf(payload(1)))
        assertEquals(
            DvRpuSource.Prepared.Unavailable("HDR10+ conversion is off (debug.nuvio.dv_hdr10plus 0)"),
            Hdr10PlusDvRpuSource(enabled = false, native = fake).prepare(P81, facts)
        )
        assertEquals(
            DvRpuSource.Prepared.Unavailable("HDR10+ gives 8.1 only"),
            Hdr10PlusDvRpuSource(native = fake).prepare(DvSignallingDecision.Target.PROFILE_8_4, facts)
        )
        assertEquals("debug.nuvio.dv_hdr10plus", Hdr10PlusDvRpuSource.SWITCH)
    }

    @Test
    fun `a first shot that cannot render releases the builder`() {
        fake.failRender = true
        assertEquals(
            DvRpuSource.Prepared.Unavailable("renderShot failed: RPU"),
            Hdr10PlusDvRpuSource(native = fake).prepare(P81, DvTrackFacts(listOf(payload(1))))
        )
        assertEquals(listOf(Handle(1)), fake.released)
    }

    @Test
    fun `L6 takes the container's values and mkvdovi's defaults for the rest`() {
        assertEquals(Level6(1000, 50, 1000, 400), Hdr10PlusDvRpuSource.level6(DvTrackFacts()))
        assertEquals(
            Level6(4000, 50, 1532, 330),
            Hdr10PlusDvRpuSource.level6(DvTrackFacts(emptyList(), 4000f, 0.005f, 1532, 330))
        )
        // A zero mastering minimum is kept (mkvdovi keeps it); a zero MaxCLL or MaxFALL is not stated.
        assertEquals(Level6(1000, 0, 1000, 400), Hdr10PlusDvRpuSource.level6(DvTrackFacts(emptyList(), 1000f, 0f, 0, 0)))
        // The minimum in 0.0001 nits, rounded: float 0.0001 is not exactly 1e-4.
        assertEquals(1, Hdr10PlusDvRpuSource.level6(DvTrackFacts(emptyList(), 1000f, 0.0001f)).minDisplayMasteringLuminance)
        // Capped at 10 000 (Level6::clamped): MaxCLL 65535 is common and means "not measured".
        assertEquals(
            Level6(10_000, 10_000, 10_000, 10_000),
            Hdr10PlusDvRpuSource.level6(DvTrackFacts(emptyList(), 20_000f, 5f, 65_535, 65_535))
        )
        // mkvdovi's resolve_light_levels: a filled value keeps MaxFALL <= MaxCLL against the stated one.
        assertEquals(Level6(1000, 50, 300, 300), Hdr10PlusDvRpuSource.level6(DvTrackFacts(maxContentLightLevel = 300)))
        assertEquals(Level6(1000, 50, 1200, 1200), Hdr10PlusDvRpuSource.level6(DvTrackFacts(maxFrameAverageLightLevel = 1200)))
        // Both stated: kept as they are, even inverted.
        assertEquals(Level6(1000, 50, 300, 500), Hdr10PlusDvRpuSource.level6(DvTrackFacts(maxContentLightLevel = 300, maxFrameAverageLightLevel = 500)))
        // NaN and negative values are not a mastering display.
        assertEquals(Level6(1000, 50, 1000, 400), Hdr10PlusDvRpuSource.level6(DvTrackFacts(emptyList(), Float.NaN, -1f)))
    }

    @Test
    fun `the params are mkvdovi's for HDR10+ with the source range of the final L6`() {
        val params = Hdr10PlusDvRpuSource.params(DvTrackFacts(emptyList(), 4000f, 0.005f, 1532, 330), fake)
        assertEquals(DoviRpuBuilder.Profile.P81(SourceRange(50, 4000)), params.profile)
        assertEquals(DoviRpuBuilder.CmVersion.V40, params.cmVersion)
        assertEquals(DoviRpuBuilder.CmVersion.V40, params.l1AvgCmVersion)
        // Without a plausible mastering display the generator's L6 lookup decides.
        val implausible = Hdr10PlusDvRpuSource.params(DvTrackFacts(emptyList(), 50f, 0.005f), fake)
        assertEquals(DoviRpuBuilder.Profile.P81(null), implausible.profile)
    }

    @Test
    fun `L9 primaries follow the mastering display, then the container, as mkvdovi detects them`() {
        val p3 = floatArrayOf(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f, 0.3127f, 0.3290f)
        val bt2020 = floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.3290f)
        val bt709 = floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f, 0.3127f, 0.3290f)
        // The usual HDR10+ title: a P3 mastering display in a BT.2020 container.
        assertEquals(0, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts(masteringPrimaries = p3, containerPrimaries = 9)))
        assertEquals(2, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts(masteringPrimaries = bt2020, containerPrimaries = 9)))
        assertEquals(1, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts(masteringPrimaries = bt709)))
        // P3 as an SEI codes it (steps of 0.00002), and DCI-P3's white point: still P3.
        val coded = floatArrayOf(0.68f, 0.32002f, 0.26498f, 0.69f, 0.15f, 0.05998f, 0.314f, 0.351f)
        assertEquals(0, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts(masteringPrimaries = coded)))
        // No known gamut, or no mastering display: the container decides, BT.2020 without it.
        val odd = floatArrayOf(0.7f, 0.3f, 0.2f, 0.7f, 0.14f, 0.05f, 0.3127f, 0.329f)
        assertEquals(0, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts(masteringPrimaries = odd, containerPrimaries = 12)))
        assertEquals(1, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts(containerPrimaries = 1)))
        assertEquals(2, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts(containerPrimaries = 9)))
        assertEquals(2, Hdr10PlusDvRpuSource.sourcePrimaryIndex(DvTrackFacts()))
        assertEquals(0, Hdr10PlusDvRpuSource.params(DvTrackFacts(masteringPrimaries = p3), fake).sourcePrimaryIndex)
    }

    @Test
    fun `without B-frames a scene refresh goes on every key change and L1 follows each AU's payload`() {
        val rpus = prepared(payload(1, 1))
        val out = read(rpus, au(0, 1, 1), au(40, 1, 1), au(80, 2, 2), au(120, 2, 2), au(160, 1, 1))
        assertEquals(listOf(false, false, true, false, true), out.map { it.refresh })
        assertEquals(listOf(1001, 1001, 1002, 1002, 1001), out.map { it.avg })
        // Scenes alternate: the first scene's shot is reused, not rendered again.
        assertEquals(listOf(L1(0, 1001, 3079), L1(0, 1002, 3079)), fake.rendered)
    }

    @Test
    fun `a new scene with the same L1 still gets its refresh`() {
        val rpus = prepared(payload(1, 5))
        val out = read(rpus, au(0, 1, 5), au(40, 2, 5))
        assertEquals(listOf(false, true), out.map { it.refresh })
        assertEquals(listOf(1005, 1005), out.map { it.avg })
    }

    @Test
    fun `in a B-pyramid the refresh lands on the scene's first frame, with extra ones carrying an unchanged L1`() {
        // Presentation 0 to 4 with keys A A B B B, read in decode order 0 4 2 1 3 (Codex's example).
        val rpus = prepared(payload(1, 1))
        val out = read(rpus, au(0, 1, 1), au(160, 2, 2), au(80, 2, 2), au(40, 1, 1), au(120, 2, 2))
        val byTime = out.associateBy { it.timeUs }
        assertTrue("frame 2 starts the scene", byTime.getValue(80_000).refresh)
        assertTrue("frame 4 is compared with frame 0, read before 1 to 3", byTime.getValue(160_000).refresh)
        assertFalse(byTime.getValue(40_000).refresh)
        assertFalse(byTime.getValue(120_000).refresh)
        // In presentation order, every L1 change carries the flag.
        val presentation = out.sortedBy { it.timeUs }
        for (i in 1 until presentation.size) {
            if (presentation[i].avg != presentation[i - 1].avg) assertTrue("frame $i", presentation[i].refresh)
        }
    }

    @Test
    fun `the leading pictures of an open-GOP cut keep the old scene and get no refresh`() {
        // Presentation 0 to 4: A A A A B; decode 0, CRA 4 (B), RASL 2, 1, 3 (A).
        val rpus = prepared(payload(1, 1))
        val out = read(rpus, au(0, 1, 1), au(160, 2, 2), au(80, 1, 1), au(40, 1, 1), au(120, 1, 1))
        assertEquals(listOf(false, true, false, false, false), out.map { it.refresh })
        assertEquals(listOf(1001, 1002, 1001, 1001, 1001), out.map { it.avg })
    }

    @Test
    fun `an AU without a payload takes the previous payload in decode order`() {
        val rpus = prepared(payload(1, 1))
        val out = read(rpus, au(0, 1, 1), au(40, 2, 2), au(80, null), au(120, 2, 2), au(160, null))
        assertEquals(listOf(1001, 1002, 1002, 1002, 1002), out.map { it.avg })
        assertEquals(listOf(false, true, false, false, false), out.map { it.refresh })
        // A payload that fails to parse is a gap too, and so is an SEI that is not HDR10+.
        val more = read(rpus, au(200, 0xEE), Au(240_000, listOf(notHdr10Plus())), au(280, 3, 3))
        assertEquals(listOf(1002, 1002, 1003), more.map { it.avg })
        assertEquals(listOf(false, false, true), more.map { it.refresh })
    }

    @Test
    fun `after a reset the first AU gets no refresh of its own and a gap carries the last L1`() {
        val rpus = prepared(payload(1, 1))
        read(rpus, au(0, 1, 1), au(40, 2, 2))
        rpus.onReset()
        // A seek into scene 3: no predecessor, no flag (mkvdovi parity for a start inside a scene).
        val seek = read(rpus, au(400, 3, 3), au(440, 3, 3))
        assertEquals(listOf(false, false), seek.map { it.refresh })
        rpus.onReset()
        // No payload until 520: the last L1 written (scene 3's), then the new L1 with the flag.
        val gap = read(rpus, au(480, null), au(520, 4, 4), au(560, 4, 4))
        assertEquals(listOf(1003, 1004, 1004), gap.map { it.avg })
        assertEquals(listOf(false, true, false), gap.map { it.refresh })
        rpus.onReset()
        // A gap carried in one generation does not flag the first AU of the next.
        read(rpus, au(700, null))
        rpus.onReset()
        assertFalse(read(rpus, au(800, 5, 5)).single().refresh)
        rpus.onReset()
        // The same L1 after a carried gap needs no flag.
        val same = read(rpus, au(900, null), au(940, 5, 5))
        assertEquals(listOf(false, false), same.map { it.refresh })
    }

    @Test
    fun `a reset forgets the previous payload in decode order`() {
        val rpus = prepared(payload(1, 1))
        read(rpus, au(0, 1, 1), au(40, 2, 2))
        rpus.onReset()
        // The gap AU carries the last L1 written (2) but does not become scene 2 for the next comparison.
        val out = read(rpus, au(400, null), au(440, 2, 2))
        assertEquals(listOf(1002, 1002), out.map { it.avg })
        assertEquals(listOf(false, false), out.map { it.refresh })
    }

    @Test
    fun `a shot that fails to render keeps the last RPU`() {
        val rpus = prepared(payload(1, 1))
        fake.failRender = true
        val out = read(rpus, au(0, 1, 1), au(40, 2, 2))
        assertEquals(listOf(1001, 1001), out.map { it.avg })
    }

    @Test
    fun `only the last AUs' keys are kept`() {
        val rpus = prepared(payload(1, 1))
        // Frame 0 is scene 2; then 64 AUs of scene 1 push it out of the window.
        read(rpus, au(0, 2, 2))
        read(rpus, *Array(Hdr10PlusTrackRpus.KEY_WINDOW) { au(1_000L + 40L * it, 1, 1) })
        // An AU at 20 µs has no kept predecessor: no flag, although frame 0 had another key.
        assertFalse(read(rpus, au(20, 1, 1)).single().refresh)
    }

    @Test
    fun `release frees the builder once`() {
        val rpus = prepared(payload(1, 1))
        rpus.release()
        rpus.release()
        assertEquals(listOf(Handle(1)), fake.released)
    }

    private val P81 = DvSignallingDecision.Target.PROFILE_8_1

    private fun prepared(first: ByteArray): TrackRpus {
        val ready = Hdr10PlusDvRpuSource(native = fake).prepare(P81, DvTrackFacts(listOf(first)))
        return (ready as DvRpuSource.Prepared.Ready).rpus
    }

    private class Au(val timeUs: Long, val payloads: List<ByteArray>)

    private class Written(val timeUs: Long, val refresh: Boolean, val avg: Int)

    /** An AU at [timeUs] ms × 1000 whose prefix SEI holds [payload] (scene, L1), or none for null. */
    private fun au(timeMs: Long, scene: Int?, l1: Int = scene ?: 0) =
        Au(timeMs * 1000, if (scene == null) emptyList() else listOf(payload(scene, l1)))

    private fun au(timeMs: Int, scene: Int?, l1: Int = scene ?: 0) = au(timeMs.toLong(), scene, l1)

    private fun read(rpus: TrackRpus, vararg aus: Au): List<Written> = aus.map { au ->
        val sample = sample(au.payloads)
        val rpu = rpus.next(sample, sample.size, 4, au.timeUs)
        Written(au.timeUs, rpu.sceneRefresh, avgOf(rpu.shot))
    }

    private fun payload(scene: Int, l1: Int = scene) = fakeHdr10PlusPayload(scene, l1)

    // Provider 0x003B: a T.35 message the library does not read as HDR10+ (the T.35 header check in
    // HevcInitNals passes only 0x003C, so this one is given straight to the provider).
    private fun notHdr10Plus() = byteArrayOf(0xB5.toByte(), 0x00, 0x3B, 0x00, 0x01, 0x04, 0x01, 9, 9)

    /** A length-prefixed AU: a prefix SEI with one T.35 message per payload, then a slice. */
    private fun sample(payloads: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun nal(bytes: ByteArray) {
            for (i in 3 downTo 0) out.write((bytes.size ushr (8 * i)) and 0xFF)
            out.write(bytes)
        }
        for (p in payloads) nal(fakeHdr10PlusSei(p))
        nal(byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x21))
        return out.toByteArray()
    }

    private fun avgOf(shot: ShotRpu) = fakeAvgOf(shot)
}
