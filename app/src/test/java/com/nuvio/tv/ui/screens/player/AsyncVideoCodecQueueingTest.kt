package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncVideoCodecQueueingTest {

    @Test
    fun `unset switch asks for async only on nvidia below API 31`() {
        assertTrue(asyncVideoCodecQueueing(switch = null, manufacturer = "NVIDIA", sdkInt = 30))
        assertFalse(asyncVideoCodecQueueing(switch = null, manufacturer = "NVIDIA", sdkInt = 31))
        assertFalse(asyncVideoCodecQueueing(switch = null, manufacturer = "Amlogic", sdkInt = 30))
        assertFalse(asyncVideoCodecQueueing(switch = null, manufacturer = null, sdkInt = 30))
    }

    @Test
    fun `the debug switch wins either way`() {
        assertTrue(asyncVideoCodecQueueing(switch = true, manufacturer = "Amlogic", sdkInt = 33))
        assertFalse(asyncVideoCodecQueueing(switch = false, manufacturer = "NVIDIA", sdkInt = 30))
    }
}
