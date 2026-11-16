package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartThresholdLogTest {

    @Test
    fun `below API 31 nothing is logged`() {
        assertNull(startThresholdLogLine(sdkInt = 30, frames = 262144))
    }

    @Test
    fun `API 31 logs the frame count`() {
        assertEquals(
            "STHRESH: DIRECT start threshold -> 262144 frames",
            startThresholdLogLine(sdkInt = 31, frames = 262144)
        )
    }

    @Test
    fun `API 34 logs the frame count`() {
        assertEquals(
            "STHRESH: DIRECT start threshold -> 4096 frames",
            startThresholdLogLine(sdkInt = 34, frames = 4096)
        )
    }

    @Test
    fun `a disabled threshold logs nothing`() {
        assertNull(startThresholdLogLine(sdkInt = 34, frames = 0))
        assertNull(startThresholdLogLine(sdkInt = 34, frames = -1))
    }
}
