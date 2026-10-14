package com.nuvio.tv.core.performance

import com.nuvio.tv.core.util.DebugSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    private val mib = 1024L * 1024L

    @Test
    fun shieldCodenamesAreRecognisedOnlyForNvidia() {
        assertTrue(DeviceProfile.isShield("NVIDIA", "mdarcy"))
        assertTrue(DeviceProfile.isShield("NVIDIA", "sif"))
        assertTrue(DeviceProfile.isShield("nvidia", "foster"))
        assertTrue(DeviceProfile.isShield("NVIDIA", "darcy"))
        assertFalse(DeviceProfile.isShield("Amazon", "mdarcy"))
        assertFalse(DeviceProfile.isShield("NVIDIA", "other"))
        assertFalse(DeviceProfile.isShield(null, null))
    }

    @Test
    fun amazonIsMatchedByManufacturerOnly() {
        assertTrue(DeviceProfile.isAmazon("Amazon"))
        assertTrue(DeviceProfile.isAmazon(" amazon "))
        assertFalse(DeviceProfile.isAmazon("NVIDIA"))
        assertFalse(DeviceProfile.isAmazon(null))
    }

    @Test
    fun shieldPro2019IsMdarcyOnly() {
        assertTrue(DeviceProfile.isShieldPro2019("NVIDIA", "mdarcy"))
        assertFalse(DeviceProfile.isShieldPro2019("NVIDIA", "sif"))
    }

    @Test
    fun shield2019IsMdarcyAndSifOnly() {
        assertTrue(DeviceProfile.isShield2019("NVIDIA", "mdarcy"))
        assertTrue(DeviceProfile.isShield2019("NVIDIA", " SIF "))
        assertFalse(DeviceProfile.isShield2019("NVIDIA", "darcy"))
        assertFalse(DeviceProfile.isShield2019("NVIDIA", "foster"))
        assertFalse(DeviceProfile.isShield2019("Amazon", "sif"))
        assertFalse(DeviceProfile.isShield2019(null, null))
    }

    @Test
    fun nvidiaMatchIgnoresCaseAndBlanks() {
        assertTrue(DeviceProfile.isNvidia(" NVIDIA "))
        assertTrue(DeviceProfile.isNvidia("Nvidia"))
        assertFalse(DeviceProfile.isNvidia("Amazon"))
        assertFalse(DeviceProfile.isNvidia(null))
    }

    @Test
    fun ramClassPutsThreeGbBoxesInMid() {
        assertEquals(DeviceProfile.RamClass.UNKNOWN, DeviceProfile.ramClass(0))
        assertEquals(DeviceProfile.RamClass.SMALL, DeviceProfile.ramClass(1_900 * mib))
        assertEquals(DeviceProfile.RamClass.MID, DeviceProfile.ramClass(2_900 * mib))
        assertEquals(DeviceProfile.RamClass.LARGE, DeviceProfile.ramClass(3_800 * mib))
    }

    @Test
    fun debugFlagParsing() {
        assertEquals(true, DebugSwitches.parseFlag("1"))
        assertEquals(true, DebugSwitches.parseFlag(" true "))
        assertEquals(false, DebugSwitches.parseFlag("0"))
        assertNull(DebugSwitches.parseFlag(""))
        assertNull(DebugSwitches.parseFlag(null))
        assertNull(DebugSwitches.property("debug.nuvio.unit_test_unset"))
    }
}
