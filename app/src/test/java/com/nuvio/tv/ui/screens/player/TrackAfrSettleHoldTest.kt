package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackAfrSettleHoldTest {

    @Test
    fun `unset switch keeps the two second hold`() {
        assertEquals(2_000L, trackAfrSettleHoldMs(null))
    }

    @Test
    fun `switch value is used within what the deadline leaves`() {
        assertEquals(800L, trackAfrSettleHoldMs(800))
        assertEquals(0L, trackAfrSettleHoldMs(0))
        assertEquals(0L, trackAfrSettleHoldMs(-5))
        assertEquals(4_000L, trackAfrSettleHoldMs(10_000))
    }
}
