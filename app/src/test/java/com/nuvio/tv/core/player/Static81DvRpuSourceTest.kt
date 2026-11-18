package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.Handle
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.Level6
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The static 8.1 RPU of ROADMAP DV4.10 on a fake library: the L1 heuristic from MaxCLL and MaxFALL, and
 * what the source renders and releases. The RPU bytes for given params are the library's (`cargo test`
 * in native/rpu_builder).
 */
class Static81DvRpuSourceTest {

    private val p81 = DvSignallingDecision.Target.PROFILE_8_1
    private val fake = FakeHdr10PlusNative()

    /** The L1 the source renders for [facts]. */
    private fun l1(facts: DvTrackFacts, seiStatic: Boolean = true): L1 {
        val prepared = Static81DvRpuSource(fake, seiStatic).prepare(p81, facts)
        assertTrue("$prepared", prepared is DvRpuSource.Prepared.Ready)
        return fake.rendered.last()
    }

    private fun container(maxCll: Int? = null, maxFall: Int? = null, peak: Float? = null, min: Float? = peak?.let { 0.005f }) =
        DvTrackFacts(emptyList(), peak, min, maxCll, maxFall)

    @Test
    fun `pq12 is the crate's nits_to_pq_code`() {
        val crate = mapOf(
            0 to 0, 1 to 614, 10 to 1227, 50 to 1803, 53 to 1826, 100 to 2081, 108 to 2112, 300 to 2547,
            400 to 2672, 500 to 2771, 1000 to 3079, 1200 to 3160, 4000 to 3696, 10_000 to 4095,
        )
        for ((nits, code) in crate) assertEquals("$nits nits", code, Static81L1.pq12(nits))
        assertEquals("above 10 000 nits", 4095, Static81L1.pq12(12_000))
        assertEquals(0, Static81L1.pq12(-1))
    }

    @Test
    fun `the maximum comes from MaxCLL, else the mastering peak, the average from MaxFALL, else the floor`() {
        assertEquals(L1(0, 2672, 3079), l1(container(1000, 400)))
        assertEquals("nothing stated: mkvdovi's 1000-nit peak", L1(0, 1229, 3079), l1(DvTrackFacts()))
        assertEquals(L1(0, 1229, 3696), l1(container(peak = 4000f)))
        assertEquals("the DV3.1 title's values", L1(0, 1826, 2112), l1(container(108, 53)))
        assertEquals("a MaxCLL below 100 nits takes the floor of the maximum", L1(0, 1229, 2081), l1(container(maxCll = 50)))
    }

    @Test
    fun `the maximum is never below a stated MaxFALL`() {
        // Two container values stay as mkvdovi keeps them in L6; the maximum rises to MaxFALL.
        assertEquals(L1(0, 2770, 2771), l1(container(300, 500)))
        // Only MaxFALL: above the 1000-nit default peak.
        assertEquals(L1(0, 3159, 3160), l1(container(maxFall = 1200)))
        // The SEI states MaxFALL above MaxCLL: L6 lowers MaxFALL (DV3.4), the maximum rises to it.
        val lowered = DvTrackFacts(seiStatic = DvSeiStatic(maxContentLightLevel = 50, maxFrameAverageLightLevel = 400))
        assertEquals(L1(0, 1803, 2672), l1(lowered))
        assertEquals(Level6(1000, 50, 50, 50), fake.created.last().level6)
    }

    @Test
    fun `zero and 65535 are not stated, and a zero mastering peak takes the floor`() {
        assertEquals(L1(0, 1229, 3696), l1(container(maxCll = 65_535, peak = 4000f)))
        assertEquals(L1(0, 1229, 3696), l1(container(maxCll = 0, maxFall = 0, peak = 4000f)))
        assertEquals(L1(0, 1229, 3079), l1(container(maxCll = 1000, maxFall = 65_535)))
        assertEquals(L1(0, 1229, 2081), l1(container(peak = 0f)))
        assertEquals(null, Static81L1.stated(65_535))
        assertEquals(null, Static81L1.stated(-5))
        assertEquals(12_000, Static81L1.stated(12_000))
    }

    @Test
    fun `the SEI fills what the container leaves open, unless the switch is off`() {
        val facts = DvTrackFacts(seiStatic = DvSeiStatic(null, 1000f, 0.0001f, 1000, 400))
        assertEquals(L1(0, 2672, 3079), l1(facts))
        assertEquals(Level6(1000, 1, 1000, 400), fake.created.last().level6)
        assertEquals(L1(0, 1229, 3079), l1(facts, seiStatic = false))
        assertEquals(Level6(1000, 50, 1000, 400), fake.created.last().level6)
    }

    @Test
    fun `the RPU has mkvdovi's 8_1 params for HDR10 and the builder is released at once`() {
        val prepared = Static81DvRpuSource(fake).prepare(p81, container(1000, 400, peak = 1000f, min = 0.0001f))
        prepared as DvRpuSource.Prepared.Ready
        assertEquals(Hdr10PlusDvRpuSource.params(container(1000, 400, peak = 1000f, min = 0.0001f), fake), fake.created.single())
        assertEquals(listOf(Handle(1)), fake.released)
        assertTrue(prepared.rpus is StaticTrackRpus)
        assertEquals(2672, fakeAvgOf(prepared.rpus.first))
        assertEquals(1, prepared.rpus.minNalLengthFieldLength)
    }

    @Test
    fun `an 8_4 target, a failed create and a failed render are unavailable`() {
        assertEquals(
            DvRpuSource.Prepared.Unavailable("static 8.1: a static 8.1 RPU is 8.1 only"),
            Static81DvRpuSource(fake).prepare(DvSignallingDecision.Target.PROFILE_8_4, DvTrackFacts()),
        )
        assertTrue(fake.created.isEmpty())
        fake.failCreateFrom = 1
        assertEquals(
            DvRpuSource.Prepared.Unavailable("static 8.1: create failed: RPU"),
            Static81DvRpuSource(fake).prepare(p81, DvTrackFacts()),
        )
        val rendering = FakeHdr10PlusNative().apply { failRender = true }
        assertEquals(
            DvRpuSource.Prepared.Unavailable("static 8.1: renderShot failed: RPU"),
            Static81DvRpuSource(rendering).prepare(p81, DvTrackFacts()),
        )
        assertEquals("the builder is released after a failed render", listOf(Handle(1)), rendering.released)
    }

    @Test
    fun `the log line names the L1 and where MaxCLL and MaxFALL came from`() {
        val sei = DvTrackFacts(seiStatic = DvSeiStatic(null, 1000f, 0.0001f, 1000, 400))
        val label = (Static81DvRpuSource(fake).prepare(p81, sei) as DvRpuSource.Prepared.Ready).label
        assertEquals(
            "static 8.1, L1 0/2672/3079 from MaxCLL 1000 (SEI), MaxFALL 400 (SEI), L6 1000/1/1000/400, " +
                "L9 primaries 2, source range 1..1000",
            label,
        )
        val none = (Static81DvRpuSource(fake).prepare(p81, container(maxCll = 300)) as DvRpuSource.Prepared.Ready).label
        assertTrue(none, none.startsWith("static 8.1, L1 0/1229/2547 from MaxCLL 300 (container), MaxFALL not stated, "))
    }
}
