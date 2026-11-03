package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.AfrSettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAfrSettleHoldTest {

    @Test
    fun `unset switch keeps the two second hold`() {
        assertEquals(2_000L, trackAfrSettleHoldMs(null))
        assertEquals(2_000L, trackAfrSettleHoldMs(null, manufacturer = "Amlogic"))
    }

    @Test
    fun `unset switch holds 600 ms on nvidia`() {
        assertEquals(600L, trackAfrSettleHoldMs(null, manufacturer = "NVIDIA"))
    }

    @Test
    fun `switch value is used within what the deadline leaves`() {
        assertEquals(800L, trackAfrSettleHoldMs(800))
        assertEquals(0L, trackAfrSettleHoldMs(0))
        assertEquals(0L, trackAfrSettleHoldMs(-5))
        assertEquals(4_000L, trackAfrSettleHoldMs(10_000))
    }

    @Test
    fun `the settle floor replaces upstream's two second minimum`() {
        assertTrue(AfrSettle.settled(700L, 1, userDelayMs = 0L, floorMs = 600L))
        assertFalse(AfrSettle.settled(500L, 1, userDelayMs = 0L, floorMs = 600L))
        assertFalse(AfrSettle.settled(700L, 0, userDelayMs = 0L, floorMs = 600L))
        assertTrue(AfrSettle.settled(3_600L, 0, userDelayMs = 0L, floorMs = 600L))
        assertEquals(3_600L, AfrSettle.capMs(0L, 600L))
        assertEquals(6_000L, AfrSettle.minimumMs(6_000L, 600L))
    }

    @Test
    fun `the track deadline grows with the settle floor`() {
        assertEquals(10_000L, trackAfrDeadlineMs(0L))
        assertEquals(12_000L, trackAfrDeadlineMs(0L, 4_000L))
    }

    @Test
    fun `switch value wins over the nvidia hold`() {
        assertEquals(2_000L, trackAfrSettleHoldMs(2_000, manufacturer = "NVIDIA"))
        assertEquals(800L, trackAfrSettleHoldMs(800, manufacturer = "NVIDIA"))
    }
}
