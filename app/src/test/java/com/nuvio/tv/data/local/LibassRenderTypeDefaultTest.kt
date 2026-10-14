package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LibassRenderTypeDefaultTest {

    @Test
    fun `nvidia starts with the canvas overlay, other boxes with the OpenGL overlay`() {
        assertEquals(LibassRenderType.OVERLAY_CANVAS, defaultLibassRenderType(switch = null, manufacturer = "NVIDIA"))
        assertEquals(LibassRenderType.OVERLAY_CANVAS, defaultLibassRenderType(switch = null, manufacturer = "nvidia"))
        assertEquals(LibassRenderType.OVERLAY_OPEN_GL, defaultLibassRenderType(switch = null, manufacturer = "Amlogic"))
        assertEquals(LibassRenderType.OVERLAY_OPEN_GL, defaultLibassRenderType(switch = null, manufacturer = null))
    }

    @Test
    fun `the switch forces either side`() {
        assertEquals(LibassRenderType.OVERLAY_CANVAS, defaultLibassRenderType(switch = true, manufacturer = "Amlogic"))
        assertEquals(LibassRenderType.OVERLAY_OPEN_GL, defaultLibassRenderType(switch = false, manufacturer = "NVIDIA"))
    }

    @Test
    fun `a stored choice beats the device default`() {
        assertEquals(
            LibassRenderType.OVERLAY_OPEN_GL,
            storedLibassRenderType("OVERLAY_OPEN_GL", default = LibassRenderType.OVERLAY_CANVAS)
        )
        assertEquals(
            LibassRenderType.EFFECTS_CANVAS,
            storedLibassRenderType("EFFECTS_CANVAS", default = LibassRenderType.OVERLAY_CANVAS)
        )
    }

    @Test
    fun `missing or unknown stored value reads as the device default`() {
        assertEquals(LibassRenderType.OVERLAY_CANVAS, storedLibassRenderType(null, default = LibassRenderType.OVERLAY_CANVAS))
        assertEquals(LibassRenderType.OVERLAY_CANVAS, storedLibassRenderType("BOGUS", default = LibassRenderType.OVERLAY_CANVAS))
        assertEquals(LibassRenderType.OVERLAY_OPEN_GL, storedLibassRenderType(null, default = LibassRenderType.OVERLAY_OPEN_GL))
    }
}
