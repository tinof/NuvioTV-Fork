package com.nuvio.tv.core.player.thumbnail

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The public entry points hand the box's swapRoom to the rules. With 900 MB free beside full swap the
 * field alone decides, on strong and on small hardware, so an entry point that loses it fails here.
 * The fields are private and init() needs a device, so they are set by name; a rename fails loudly.
 */
class SwapRoomEntryPointsTest {

    private val saved = FIELDS.associateWith { field(it).getBoolean(null) }

    @Before fun setUp() {
        mockkObject(MemoryGovernor)
        every { MemoryGovernor.readMeminfo() } returns MemoryGovernor.Meminfo(900, 1_024, 60)
        field("lowRam").setBoolean(null, false)
    }

    @After fun tearDown() {
        unmockkObject(MemoryGovernor)
        saved.forEach { (name, value) -> field(name).setBoolean(null, value) }
    }

    @Test fun pressureFollowsTheField() = onBothHardwares {
        field(SWAP_ROOM).setBoolean(null, false)
        assertEquals("SwapFree 60 MB < 64", MemoryGovernor.pressure())
        field(SWAP_ROOM).setBoolean(null, true)
        assertNull(MemoryGovernor.pressure())
    }

    @Test fun allowFollowsTheField() = onBothHardwares {
        field(SWAP_ROOM).setBoolean(null, false)
        val refused = MemoryGovernor.allow(DecodeTier.SW_4K, decoderWarm = true)
        assertFalse(refused.reason, refused.allowed)
        assertEquals("SwapFree 60 MB < 64", refused.reason)
        field(SWAP_ROOM).setBoolean(null, true)
        assertTrue(MemoryGovernor.allow(DecodeTier.SW_4K, decoderWarm = true).allowed)
    }

    @Test fun swapAllowsFollowsTheField() = onBothHardwares {
        field(SWAP_ROOM).setBoolean(null, false)
        val refused = MemoryGovernor.swapAllows(funded = false)
        assertFalse(refused.reason, refused.allowed)
        assertEquals("SwapFree 60 MB < 64", refused.reason)
        field(SWAP_ROOM).setBoolean(null, true)
        assertEquals("ok swapFree=60", MemoryGovernor.swapAllows(funded = false).reason)
    }

    @Test fun afterHardwareCloseFollowsTheField() = onBothHardwares {
        every { MemoryGovernor.readMeminfo() } returns MemoryGovernor.Meminfo(900, 1_024, 40)
        val held = GovernorVerdict(false, "SwapFree 40 MB < 64", 40L)
        field(SWAP_ROOM).setBoolean(null, false)
        val kept = MemoryGovernor.afterHardwareClose(held, DecodeTier.SW_4K_STRIPS)
        assertFalse(kept.reason, kept.allowed)
        field(SWAP_ROOM).setBoolean(null, true)
        val lifted = MemoryGovernor.afterHardwareClose(held, DecodeTier.SW_4K_STRIPS)
        assertTrue(lifted.reason, lifted.allowed)
    }

    private fun onBothHardwares(check: () -> Unit) {
        for (strong in listOf(true, false)) {
            field("strongHardware").setBoolean(null, strong)
            check()
        }
    }

    private fun field(name: String) = MemoryGovernor::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val SWAP_ROOM = "swapRoomHardware"
        val FIELDS = listOf("lowRam", "strongHardware", SWAP_ROOM)
    }
}
