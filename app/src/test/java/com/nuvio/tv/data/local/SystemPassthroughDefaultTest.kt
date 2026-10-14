package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPassthroughDefaultTest {

    @Test
    fun `nvidia starts with system passthrough on, other boxes keep it off`() {
        assertTrue(defaultUseSystemPassthrough("NVIDIA"))
        assertTrue(defaultUseSystemPassthrough(" nvidia "))
        assertFalse(defaultUseSystemPassthrough("Amlogic"))
        assertFalse(defaultUseSystemPassthrough(null))
    }
}
