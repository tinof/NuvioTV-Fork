package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectHdr10MetadataDefaultTest {

    @Test
    fun `nvidia starts with injection on, other boxes off`() {
        assertTrue(defaultInjectHdr10MetadataOnStrip("NVIDIA"))
        assertTrue(defaultInjectHdr10MetadataOnStrip("nvidia"))
        assertFalse(defaultInjectHdr10MetadataOnStrip("Amlogic"))
        assertFalse(defaultInjectHdr10MetadataOnStrip(null))
    }

    @Test
    fun `nothing saved falls back to the device default`() {
        assertTrue(storedInjectHdr10MetadataOnStrip(stored = null, manufacturer = "NVIDIA"))
        assertFalse(storedInjectHdr10MetadataOnStrip(stored = null, manufacturer = "Amazon"))
    }

    @Test
    fun `a saved choice wins over the device default`() {
        assertFalse(storedInjectHdr10MetadataOnStrip(stored = false, manufacturer = "NVIDIA"))
        assertTrue(storedInjectHdr10MetadataOnStrip(stored = true, manufacturer = "Amlogic"))
    }
}
