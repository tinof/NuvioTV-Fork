package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** debug.nuvio.render_trace: which frames log, and the line they log. */
class RenderTraceTest {

    @Test fun everyTwoHundredFortiethFrame() {
        assertFalse(renderTraceDue(1))
        assertFalse(renderTraceDue(239))
        assertTrue(renderTraceDue(240))
        assertFalse(renderTraceDue(241))
        assertTrue(renderTraceDue(480))
    }

    @Test fun releaseAheadOfNowInMillisecondsWithOneDecimal() {
        assertEquals(
            "RENDER_TRACE: pts=41708333 releaseInMs=16.7 frames=240",
            renderTraceLine(ptsUs = 41_708_333, releaseTimeNs = 5_016_700_000, nowNs = 5_000_000_000, frames = 240)
        )
    }

    @Test fun lateReleaseIsNegative() {
        assertEquals(
            "RENDER_TRACE: pts=0 releaseInMs=-2.5 frames=480",
            renderTraceLine(ptsUs = 0, releaseTimeNs = 1_000_000_000, nowNs = 1_002_500_000, frames = 480)
        )
    }

    /** A PTS-scaled time like the one SurfaceFlinger calls implausible shows as a huge lead. */
    @Test fun ptsBasedReleaseTimeStandsOut() {
        assertEquals(
            "RENDER_TRACE: pts=64064000 releaseInMs=900064064.0 frames=720",
            renderTraceLine(ptsUs = 64_064_000, releaseTimeNs = 1_000_064_064_000_000, nowNs = 100_000_000_000_000, frames = 720)
        )
    }
}
