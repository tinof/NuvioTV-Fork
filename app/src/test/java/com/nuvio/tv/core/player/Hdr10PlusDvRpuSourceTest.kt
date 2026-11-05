package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.Handle
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.Level6
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu
import com.nuvio.tv.core.player.DoviRpuBuilder.SourceRange
import com.nuvio.tv.core.util.DebugSwitches
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
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

    // ROADMAP DV3.4: the SEI fills what the container does not state (docs/dv-conversion/DESIGN.md 2.4).

    private val p3 = floatArrayOf(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f, 0.3127f, 0.3290f)
    private val bt2020 = floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.3290f)
    private val title = DvSeiStatic(p3, 1000f, 0.0001f, 108, 53)

    private fun filled(facts: DvTrackFacts) = Hdr10PlusDvRpuSource.params(facts.filledFromSei(), fake)

    private fun level6(facts: DvTrackFacts) = filled(facts).level6

    private fun label(facts: DvTrackFacts, seiStatic: Boolean = true) =
        (Hdr10PlusDvRpuSource(native = fake, seiStatic = seiStatic).prepare(P81, facts) as DvRpuSource.Prepared.Ready).label

    @Test
    fun `the DV3_1 title's SEI replaces all the defaults`() {
        val params = filled(DvTrackFacts(seiStatic = title))
        assertEquals(Level6(1000, 1, 108, 53), params.level6)
        assertEquals(DoviRpuBuilder.Profile.P81(SourceRange(1, 1000)), params.profile)
        assertEquals(DoviRpuBuilder.PRIMARIES_P3_D65, params.sourcePrimaryIndex)
    }

    @Test
    fun `every value the container states wins over the SEI`() {
        val params = filled(DvTrackFacts(emptyList(), 4000f, 0.005f, 500, 300, bt2020, 9, title))
        assertEquals(Level6(4000, 50, 500, 300), params.level6)
        assertEquals(DoviRpuBuilder.PRIMARIES_BT2020, params.sourcePrimaryIndex)
    }

    @Test
    fun `MaxCLL and MaxFALL fill one by one, a container zero counting as not stated`() {
        assertEquals(Level6(1000, 1, 500, 53), level6(DvTrackFacts(maxContentLightLevel = 500, seiStatic = title)))
        assertEquals(Level6(1000, 1, 108, 90), level6(DvTrackFacts(maxFrameAverageLightLevel = 90, seiStatic = title)))
        assertEquals(
            Level6(1000, 1, 108, 53),
            level6(DvTrackFacts(maxContentLightLevel = 0, maxFrameAverageLightLevel = 0, seiStatic = title)),
        )
    }

    @Test
    fun `the mastering luminance is one field and only the container's pair wins`() {
        // A lone value loses to the SEI's pair, as MediaInfo reports it.
        assertEquals(Level6(1000, 1, 108, 53), level6(DvTrackFacts(masteringMaxNits = 4000f, seiStatic = title)))
        assertEquals(Level6(1000, 1, 108, 53), level6(DvTrackFacts(masteringMinNits = 0.005f, seiStatic = title)))
        assertEquals(Level6(1000, 1, 108, 53), level6(DvTrackFacts(masteringMaxNits = Float.NaN, masteringMinNits = 0f, seiStatic = title)))
        // Both win, a zero included.
        assertEquals(Level6(4000, 0, 108, 53), level6(DvTrackFacts(masteringMaxNits = 4000f, masteringMinNits = 0f, seiStatic = title)))
        assertEquals(Level6(0, 50, 108, 53), level6(DvTrackFacts(masteringMaxNits = 0f, masteringMinNits = 0.005f, seiStatic = title)))
        // Without an SEI pair a lone value stays as DV3.1 uses it.
        val lightOnly = DvSeiStatic(maxContentLightLevel = 108, maxFrameAverageLightLevel = 53)
        assertEquals(Level6(4000, 50, 108, 53), level6(DvTrackFacts(masteringMaxNits = 4000f, seiStatic = lightOnly)))
    }

    @Test
    fun `primaries are one field too, and an SEI gamut nobody names falls back to the Colour element`() {
        assertEquals(DoviRpuBuilder.PRIMARIES_BT2020, filled(DvTrackFacts(masteringPrimaries = bt2020, seiStatic = title)).sourcePrimaryIndex)
        assertEquals(DoviRpuBuilder.PRIMARIES_P3_D65, filled(DvTrackFacts(containerPrimaries = 9, seiStatic = title)).sourcePrimaryIndex)
        val odd = DvSeiStatic(masteringPrimaries = floatArrayOf(0.5f, 0.3f, 0.3f, 0.5f, 0.2f, 0.2f, 0.3f, 0.3f))
        assertEquals(DoviRpuBuilder.PRIMARIES_BT709, filled(DvTrackFacts(containerPrimaries = 1, seiStatic = odd)).sourcePrimaryIndex)
        assertEquals(DoviRpuBuilder.PRIMARIES_BT2020, filled(DvTrackFacts(seiStatic = odd)).sourcePrimaryIndex)
    }

    @Test
    fun `source_range_81 guards implausible SEI values before the cap`() {
        fun range(max: Float, min: Float) = filled(DvTrackFacts(seiStatic = DvSeiStatic(p3, max, min))).profile
        // Written in the wrong units: 1 nit, 0 nits.
        assertEquals(DoviRpuBuilder.Profile.P81(null), range(1f, 0.0001f))
        assertEquals(DoviRpuBuilder.Profile.P81(null), range(0f, 0f))
        // Capped, these two would look plausible: 10 000 nits, 1 nit.
        assertEquals(DoviRpuBuilder.Profile.P81(null), range(50_000f, 0.0001f))
        assertEquals(DoviRpuBuilder.Profile.P81(null), range(1000f, 5f))
        assertEquals(DoviRpuBuilder.Profile.P81(null), range(429_496.72f, 214_748.36f))
        // The values stay in L6, capped, as the library needs them.
        assertEquals(Level6(10_000, 1, 1000, 400), level6(DvTrackFacts(seiStatic = DvSeiStatic(p3, 50_000f, 0.0001f))))
        assertEquals(Level6(1000, 10_000, 1000, 400), level6(DvTrackFacts(seiStatic = DvSeiStatic(p3, 1000f, 5f))))
        // At the edge of the plausible range.
        assertEquals(DoviRpuBuilder.Profile.P81(SourceRange(10_000, 10_000)), range(10_000f, 1f))
        // Rounded, this peak would look plausible: 100 nits.
        assertEquals(DoviRpuBuilder.Profile.P81(null), range(99.6f, 0.005f))
    }

    @Test
    fun `a container pair keeps DV3_1's range, of the capped values, in both switch states`() {
        val container = DvTrackFacts(listOf(payload(1)), 20_000f, 5f)
        for (seiStatic in listOf(true, false)) {
            Hdr10PlusDvRpuSource(native = fake, seiStatic = seiStatic).prepare(P81, container)
            assertEquals(DoviRpuBuilder.Profile.P81(SourceRange(10_000, 10_000)), fake.created.last().profile)
        }
    }

    @Test
    fun `a set of 8 container chromaticities wins even when it names no gamut`() {
        val odd = floatArrayOf(0.5f, 0.3f, 0.3f, 0.5f, 0.2f, 0.2f, 0.3f, 0.3f)
        val filled = DvTrackFacts(masteringPrimaries = odd, containerPrimaries = 9, seiStatic = title).filledFromSei()
        assertTrue(DvStaticField.MASTERING_PRIMARIES !in filled.fromSei)
        assertEquals(DoviRpuBuilder.PRIMARIES_BT2020, Hdr10PlusDvRpuSource.sourcePrimaryIndex(filled))
    }

    @Test
    fun `the two switches reach the source and the extractor`() {
        val facts = DvTrackFacts(listOf(payload(1)), seiStatic = title)
        defaultRpuSource(hdr10PlusEnabled = true, seiStaticEnabled = false, native = fake).prepare(P81, facts)
        assertEquals(Level6(1000, 50, 1000, 400), fake.created.last().level6)
        defaultRpuSource(hdr10PlusEnabled = true, seiStaticEnabled = true, native = fake).prepare(P81, facts)
        assertEquals(Level6(1000, 1, 108, 53), fake.created.last().level6)

        val config = DolbyVisionConversionConfig(active = false, convertNonDolbyVision = true, stats = DolbyVisionStatsSession())
        mockkObject(DebugSwitches)
        try {
            every { DebugSwitches.flag(any()) } returns null
            assertTrue(DolbyVisionMatroskaTransformer(config).readsSeiStatic())
            every { DebugSwitches.flag(Hdr10PlusDvRpuSource.SEI_STATIC_SWITCH) } returns false
            assertFalse(DolbyVisionMatroskaTransformer(config).readsSeiStatic())
            assertTrue(DolbyVisionMatroskaTransformer(config).convertsHdr10Plus())
        } finally {
            unmockkObject(DebugSwitches)
        }
    }

    @Test
    fun `MaxFALL stays at or below MaxCLL when the SEI is involved`() {
        // A default gives way to a value from the SEI.
        assertEquals(1000 to 300, light(DvSeiStatic(maxContentLightLevel = 1000, maxFrameAverageLightLevel = 300)))
        assertEquals(108 to 108, light(DvSeiStatic(maxContentLightLevel = 108)))
        assertEquals(1200 to 1200, light(DvSeiStatic(maxFrameAverageLightLevel = 1200)))
        // Both stated, one or both from the SEI: lowered, a departure from mkvdovi.
        assertEquals(50 to 50, light(DvSeiStatic(maxContentLightLevel = 50, maxFrameAverageLightLevel = 108)))
        assertEquals(Level6(1000, 50, 50, 50), level6(DvTrackFacts(maxContentLightLevel = 50, seiStatic = DvSeiStatic(maxFrameAverageLightLevel = 108))))
        assertEquals(Level6(1000, 50, 50, 50), level6(DvTrackFacts(maxFrameAverageLightLevel = 108, seiStatic = DvSeiStatic(maxContentLightLevel = 50))))
        // Two container values stay as DV3.1 keeps them.
        assertEquals(Level6(1000, 1, 50, 108), level6(DvTrackFacts(maxContentLightLevel = 50, maxFrameAverageLightLevel = 108, seiStatic = title)))
    }

    private fun light(sei: DvSeiStatic): Pair<Int, Int> {
        val l6 = level6(DvTrackFacts(seiStatic = sei))!!
        return l6.maxContentLightLevel to l6.maxFrameAverageLightLevel
    }

    @Test
    fun `values above the L6 limit are capped`() {
        assertEquals(Level6(1000, 50, 10_000, 10_000), level6(DvTrackFacts(seiStatic = DvSeiStatic(maxContentLightLevel = 12_000, maxFrameAverageLightLevel = 11_000))))
    }

    @Test
    fun `every 16-bit minimum code survives the float`() {
        for (code in 0..65_535) {
            val nits = (code / 10_000.0).toFloat()
            val level6 = Hdr10PlusDvRpuSource.level6(DvTrackFacts(emptyList(), 1000f, nits))
            assertEquals(minOf(code, 10_000), level6.minDisplayMasteringLuminance)
        }
    }

    @Test
    fun `the switch off gives today's params and ignores the SEI`() {
        val on = fake.created.size
        Hdr10PlusDvRpuSource(native = fake, seiStatic = false).prepare(P81, DvTrackFacts(listOf(payload(1)), seiStatic = title))
        Hdr10PlusDvRpuSource(native = fake).prepare(P81, DvTrackFacts(listOf(payload(1))))
        val (off, today) = fake.created.drop(on)
        assertEquals(today, off)
        assertEquals(Level6(1000, 50, 1000, 400), off.level6)
        assertEquals(DoviRpuBuilder.PRIMARIES_BT2020, off.sourcePrimaryIndex)
    }

    @Test
    fun `the log line names where each value came from`() {
        val sei = label(DvTrackFacts(listOf(payload(1)), seiStatic = title))
        assertTrue(sei, "L6 1000/1/108/53, source range 1..1000" in sei)
        assertTrue(sei, "origin mastering max SEI, mastering min SEI, MaxCLL SEI, MaxFALL SEI, L9 SEI" in sei)
        assertTrue(sei, "SEI states 0.0001..1000 nits, MaxCLL 108, MaxFALL 53, primaries L9 0" in sei)

        val mixed = label(DvTrackFacts(listOf(payload(1)), 4000f, 0.005f, 500, null, null, 9, title))
        assertTrue(mixed, "origin mastering max container, mastering min container, MaxCLL container, MaxFALL SEI, L9 SEI" in mixed)

        val none = label(DvTrackFacts(listOf(payload(1))))
        assertTrue(none, "origin mastering max default, mastering min default, MaxCLL default, MaxFALL default, L9 default" in none)
        assertTrue(none, "no static SEI read" in none)

        val colourOnly = label(DvTrackFacts(listOf(payload(1)), containerPrimaries = 9))
        assertTrue(colourOnly, "L9 container" in colourOnly)

        val off = label(DvTrackFacts(listOf(payload(1)), seiStatic = title), seiStatic = false)
        assertTrue(off, "origin mastering max default" in off)
        assertTrue(off, "SEI states 0.0001..1000 nits, MaxCLL 108, MaxFALL 53, primaries L9 0 (ignored: debug.nuvio.dv_sei_static 0)" in off)

        val lowered = label(DvTrackFacts(listOf(payload(1)), seiStatic = DvSeiStatic(maxContentLightLevel = 50, maxFrameAverageLightLevel = 108)))
        assertTrue(lowered, "MaxFALL lowered to MaxCLL" in lowered)

        val raised = label(DvTrackFacts(listOf(payload(1)), seiStatic = DvSeiStatic(maxFrameAverageLightLevel = 1200)))
        assertTrue(raised, "MaxCLL from MaxFALL, MaxFALL SEI" in raised)
        val followed = label(DvTrackFacts(listOf(payload(1)), seiStatic = DvSeiStatic(maxContentLightLevel = 300)))
        assertTrue(followed, "MaxCLL SEI, MaxFALL from MaxCLL" in followed)
    }

    private fun au(timeMs: Int, scene: Int?, l1: Int = scene ?: 0) = au(timeMs.toLong(), scene, l1)

    private fun read(rpus: TrackRpus, vararg aus: Au): List<Written> = aus.map { au ->
        val sample = sample(au.payloads)
        val rpu = rpus.next(sample, sample.size, 4, AuInput(au.timeUs, ticks = au.timeUs, laceCount = 1, decodeIndex = 0))
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
