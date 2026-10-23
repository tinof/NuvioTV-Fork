package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The per-playback conversion gate (ROADMAP DV2.3): every input turns it off, first reason wins. */
class DvConversionGateTest {

    private fun resolve(
        setting: Boolean = true,
        revertSwitch: Boolean? = null,
        isNvidia: Boolean = true,
        decoder: Boolean = true,
        display: Boolean = true,
        builder: Boolean = true,
        streamDisabled: Boolean = false,
        strip: Boolean = false,
        map: Boolean = false,
    ) = DvConversionGate.resolve(setting, revertSwitch, isNvidia, decoder, display, builder, streamDisabled, strip, map)

    @Test
    fun `on only when every input allows it`() {
        assertEquals(DvConversionGate.Result(true, "on"), resolve())
        assertEquals(DvConversionGate.Result(true, "on"), resolve(revertSwitch = true))
    }

    @Test
    fun `each input turns it off with its own reason`() {
        assertEquals("setting off", resolve(setting = false).reason)
        assertEquals("debug.nuvio.dv_convert=0", resolve(revertSwitch = false).reason)
        assertEquals("not an Nvidia device", resolve(isNvidia = false).reason)
        assertEquals("no Dolby Vision decoder", resolve(decoder = false).reason)
        assertEquals("display without Dolby Vision", resolve(display = false).reason)
        assertEquals("no RPU builder (libnuvio_rpu)", resolve(builder = false).reason)
        assertEquals("converted playback of this stream failed before", resolve(streamDisabled = true).reason)
        assertEquals("Dolby Vision strip or HDR10 base-layer mode", resolve(strip = true).reason)
        assertEquals("Dolby Vision mapped to HEVC", resolve(map = true).reason)
        for (off in listOf(
            resolve(setting = false), resolve(revertSwitch = false), resolve(isNvidia = false), resolve(decoder = false),
            resolve(display = false), resolve(builder = false), resolve(streamDisabled = true), resolve(strip = true),
            resolve(map = true),
        )) {
            assertEquals(off.reason, false, off.enabled)
        }
    }

    @Test
    fun `the first failing input in the fixed order is the reason`() {
        assertEquals("setting off", resolve(setting = false, revertSwitch = false, isNvidia = false, map = true).reason)
        assertEquals("debug.nuvio.dv_convert=0", resolve(revertSwitch = false, isNvidia = false, builder = false).reason)
        assertEquals("not an Nvidia device", resolve(isNvidia = false, decoder = false, display = false).reason)
        assertEquals("no Dolby Vision decoder", resolve(decoder = false, display = false, builder = false).reason)
        assertEquals("display without Dolby Vision", resolve(display = false, builder = false, strip = true).reason)
        assertEquals("no RPU builder (libnuvio_rpu)", resolve(builder = false, streamDisabled = true).reason)
        assertEquals("converted playback of this stream failed before", resolve(streamDisabled = true, strip = true).reason)
        assertEquals("Dolby Vision strip or HDR10 base-layer mode", resolve(strip = true, map = true).reason)
    }

    @Test
    fun `the log line`() {
        assertEquals("DV_CONVERT: on", DvConversionGate.logLine(resolve()))
        assertEquals("DV_CONVERT: off (no Dolby Vision decoder)", DvConversionGate.logLine(resolve(decoder = false)))
    }
}
