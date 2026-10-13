package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AsyncVideoCodecQueueingTest {

    @Test
    fun `unset switch forces async only on nvidia below API 31`() {
        assertEquals(VideoCodecQueueing.FORCE_ASYNC, videoCodecQueueing(switch = null, manufacturer = "NVIDIA", sdkInt = 30))
        assertEquals(VideoCodecQueueing.DEFAULT, videoCodecQueueing(switch = null, manufacturer = "NVIDIA", sdkInt = 31))
        assertEquals(VideoCodecQueueing.DEFAULT, videoCodecQueueing(switch = null, manufacturer = "Amlogic", sdkInt = 30))
        assertEquals(VideoCodecQueueing.DEFAULT, videoCodecQueueing(switch = null, manufacturer = null, sdkInt = 30))
    }

    @Test
    fun `the debug switch wins either way`() {
        assertEquals(VideoCodecQueueing.FORCE_ASYNC, videoCodecQueueing(switch = true, manufacturer = "Amlogic", sdkInt = 33))
        assertEquals(VideoCodecQueueing.FORCE_SYNC, videoCodecQueueing(switch = false, manufacturer = "NVIDIA", sdkInt = 30))
        // 0 must force sync on API 31+ too, where Media3's default is async.
        assertEquals(VideoCodecQueueing.FORCE_SYNC, videoCodecQueueing(switch = false, manufacturer = "Google", sdkInt = 34))
    }

    @Test
    fun `hud label says what the codec runs`() {
        assertEquals("async (forced)", videoCodecQueueingLabel(VideoCodecQueueing.FORCE_ASYNC, 30))
        assertEquals("sync (forced)", videoCodecQueueingLabel(VideoCodecQueueing.FORCE_SYNC, 34))
        assertEquals("default (async on API 31+)", videoCodecQueueingLabel(VideoCodecQueueing.DEFAULT, 31))
        assertEquals("default (sync)", videoCodecQueueingLabel(VideoCodecQueueing.DEFAULT, 30))
    }
}
