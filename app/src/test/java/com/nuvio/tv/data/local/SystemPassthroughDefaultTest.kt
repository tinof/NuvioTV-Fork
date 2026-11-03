package com.nuvio.tv.data.local

import com.nuvio.tv.ui.screens.player.hbrIecEnabled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPassthroughDefaultTest {

    @Test
    fun `unsaved settings take the packer for TrueHD and DTS-HD`() {
        val settings = PlayerSettings()
        assertFalse(settings.useSystemPassthrough)
        assertTrue(
            hbrIecEnabled(
                switch = null,
                useSystemPassthrough = settings.useSystemPassthrough,
                forceOpticalPassthrough = false,
                bluetoothForcePcm = false,
                iecOff = false
            )
        )
    }
}
