package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.HlgComposer
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import org.junit.Assert.assertEquals
import org.junit.Test

// The JVM has no libnuvio_rpu.so, so the canary RPU itself is checked on the device (DV3.2); these
// tests cover the switch and the log label.
class StaticDvRpuSourceTest {

    @Test
    fun `the canary switch picks the canary composer, anything else keeps BT_2100`() {
        assertEquals(HlgComposer.CANARY, StaticDvRpuSource.composerFor(true))
        assertEquals(HlgComposer.BT2100, StaticDvRpuSource.composerFor(false))
        assertEquals(HlgComposer.BT2100, StaticDvRpuSource.composerFor(null))
    }

    @Test
    fun `the switch is read from debug_nuvio_dv_hlg_canary`() {
        assertEquals("debug.nuvio.dv_hlg_canary", StaticDvRpuSource.CANARY_SWITCH)
    }

    @Test
    fun `the label names the composer and the L1`() {
        val l1 = L1(0, 1283, 3079)
        assertEquals(
            "static 8.4, BT.2100 composer, L1 0/1283/3079",
            StaticDvRpuSource.label(HlgComposer.BT2100, l1)
        )
        assertEquals(
            "static 8.4, canary composer, L1 0/1283/3079",
            StaticDvRpuSource.label(HlgComposer.CANARY, l1)
        )
    }

    @Test
    fun `without the library the canary source is unavailable as well`() {
        assertEquals(
            DvRpuSource.Prepared.Unavailable("static84L1 failed: UNAVAILABLE"),
            StaticDvRpuSource(HlgComposer.CANARY).prepare(DvSignallingDecision.Target.PROFILE_8_4, DvTrackFacts.NONE)
        )
    }
}
