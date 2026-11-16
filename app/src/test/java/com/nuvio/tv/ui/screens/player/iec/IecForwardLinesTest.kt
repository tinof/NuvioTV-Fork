package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Test

class IecForwardLinesTest {
    private val trueHd = "audio/true-hd"

    @Test
    fun `IEC off by System passthrough or the route logs HBR DIRECT`() {
        val lines = IecForwardLines.of(trueHd, hbrAllowed = false, iecCarries = true, iecFailedThisSession = false, tunnelReady = true)
        assertEquals("HBR DIRECT mime=audio/true-hd (platform passthrough, IEC off by design)", lines.log)
        assertEquals("iec_hbr_direct mime=audio/true-hd hbrAllowed=false iecCarries=true", lines.event)
    }

    @Test
    fun `a format not carried on IEC logs HBR DIRECT`() {
        val lines = IecForwardLines.of(
            "audio/vnd.dts.hd", hbrAllowed = true, iecCarries = false, iecFailedThisSession = false, tunnelReady = true
        )
        assertEquals("HBR DIRECT mime=audio/vnd.dts.hd (platform passthrough, IEC off by design)", lines.log)
        assertEquals("iec_hbr_direct mime=audio/vnd.dts.hd hbrAllowed=true iecCarries=false", lines.event)
    }

    @Test
    fun `IEC off by design wins over an earlier failure`() {
        val lines = IecForwardLines.of(trueHd, hbrAllowed = false, iecCarries = true, iecFailedThisSession = true, tunnelReady = true)
        assertEquals("HBR DIRECT mime=audio/true-hd (platform passthrough, IEC off by design)", lines.log)
    }

    @Test
    fun `IEC that failed this session logs the RAW fallback`() {
        val lines = IecForwardLines.of(trueHd, hbrAllowed = true, iecCarries = true, iecFailedThisSession = true, tunnelReady = true)
        assertEquals("HBR RAW mime=audio/true-hd (compressed, not PCM)", lines.log)
        assertEquals("iec_hbr_raw_fallback mime=audio/true-hd iecFailedThisSession=true tunnelReady=true", lines.event)
    }

    @Test
    fun `tunnelling in the way logs the RAW fallback`() {
        val lines = IecForwardLines.of(trueHd, hbrAllowed = true, iecCarries = true, iecFailedThisSession = false, tunnelReady = false)
        assertEquals("HBR RAW mime=audio/true-hd (compressed, not PCM)", lines.log)
        assertEquals("iec_hbr_raw_fallback mime=audio/true-hd iecFailedThisSession=false tunnelReady=false", lines.event)
    }
}
