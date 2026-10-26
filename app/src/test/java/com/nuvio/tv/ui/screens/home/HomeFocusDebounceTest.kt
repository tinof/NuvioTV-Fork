package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.KeyHoldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFocusDebounceTest {
    @Test fun onlyRepeatsOfAHeldKeyCountAsHeld() {
        assertFalse(KeyHoldState.isHeld(down = true, repeatCount = 0))
        assertTrue(KeyHoldState.isHeld(down = true, repeatCount = 3))
        assertFalse(KeyHoldState.isHeld(down = false, repeatCount = 3))
        assertFalse(KeyHoldState.isHeld(down = false, repeatCount = 0))
    }

    @Test fun singlePressSettlesSoonerThanAHeldKey() {
        assertEquals(110L, modernFocusDebounceMs(keyHeld = false))
        assertEquals(250L, modernFocusDebounceMs(keyHeld = true))
    }
}
