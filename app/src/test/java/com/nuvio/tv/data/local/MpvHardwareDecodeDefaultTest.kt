package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvHardwareDecodeDefaultTest {

    @Test
    fun `nvidia starts on direct mediacodec with copy fallback, other boxes on auto-safe`() {
        assertEquals(MpvHardwareDecodeMode.LEGACY_DIRECT_COPY, defaultMpvHardwareDecodeMode("NVIDIA"))
        assertEquals(MpvHardwareDecodeMode.AUTO_SAFE, defaultMpvHardwareDecodeMode("Amlogic"))
        assertEquals(MpvHardwareDecodeMode.AUTO_SAFE, defaultMpvHardwareDecodeMode(null))
    }

    @Test
    fun `a saved mode wins over the device default`() {
        assertEquals(MpvHardwareDecodeMode.AUTO_SAFE, storedMpvHardwareDecodeMode("AUTO_SAFE", manufacturer = "NVIDIA"))
        assertEquals(MpvHardwareDecodeMode.HARDWARE_COPY, storedMpvHardwareDecodeMode("HARDWARE_COPY", manufacturer = "NVIDIA"))
        assertEquals(MpvHardwareDecodeMode.HARDWARE_DIRECT, storedMpvHardwareDecodeMode("HARDWARE_DIRECT", manufacturer = "Amlogic"))
        assertEquals(MpvHardwareDecodeMode.DISABLED, storedMpvHardwareDecodeMode("DISABLED", manufacturer = "NVIDIA"))
        assertEquals(
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY,
            storedMpvHardwareDecodeMode("LEGACY_DIRECT_COPY", manufacturer = "Amlogic")
        )
    }

    @Test
    fun `nothing saved or an unknown value falls back to the device default`() {
        assertEquals(MpvHardwareDecodeMode.LEGACY_DIRECT_COPY, storedMpvHardwareDecodeMode(null, manufacturer = "NVIDIA"))
        assertEquals(MpvHardwareDecodeMode.AUTO_SAFE, storedMpvHardwareDecodeMode(null, manufacturer = "Amlogic"))
        assertEquals(MpvHardwareDecodeMode.LEGACY_DIRECT_COPY, storedMpvHardwareDecodeMode("BOGUS", manufacturer = "NVIDIA"))
    }
}
