package com.nuvio.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDiskCacheSizeTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun plentyOfFreeStorage_getsOneGigabyte() {
        assertEquals(1 * gb, imageDiskCacheBytes(totalRamBytes = 3 * gb, freeStorageBytes = 64 * gb))
        assertEquals(1 * gb, imageDiskCacheBytes(totalRamBytes = 2 * gb, freeStorageBytes = 10 * gb + 1))
    }

    @Test
    fun tenGigabytesOrLess_staysAsBefore() {
        assertEquals(300 * mb, imageDiskCacheBytes(totalRamBytes = 4 * gb, freeStorageBytes = 10 * gb))
        assertEquals(200 * mb, imageDiskCacheBytes(totalRamBytes = 2 * gb, freeStorageBytes = 5 * gb))
        assertEquals(200 * mb, imageDiskCacheBytes(totalRamBytes = 3_299L * mb, freeStorageBytes = 0L))
        assertEquals(300 * mb, imageDiskCacheBytes(totalRamBytes = 3_300L * mb, freeStorageBytes = 0L))
    }

    @Test
    fun nvidiaTier_keeps300MbOnAThreeGigabyteBoxWithRoom() {
        val shieldRam = 2_946L * mb
        assertEquals(300 * mb, imageDiskCacheBytes(shieldRam, freeStorageBytes = 4 * gb, nvidiaTier = true))
        assertEquals(200 * mb, imageDiskCacheBytes(shieldRam, freeStorageBytes = 4 * gb - 1, nvidiaTier = true))
        assertEquals(200 * mb, imageDiskCacheBytes(shieldRam, freeStorageBytes = 8 * gb, nvidiaTier = false))
        assertEquals(1 * gb, imageDiskCacheBytes(shieldRam, freeStorageBytes = 11 * gb, nvidiaTier = true))
    }

    @Test
    fun nvidiaTier_isOnForNvidiaAndTheSwitchWins() {
        assertTrue(nvidiaImageDiskTier(switch = null, manufacturer = "NVIDIA"))
        assertFalse(nvidiaImageDiskTier(switch = null, manufacturer = "Amlogic"))
        assertFalse(nvidiaImageDiskTier(switch = false, manufacturer = "NVIDIA"))
        assertTrue(nvidiaImageDiskTier(switch = true, manufacturer = "Amlogic"))
    }
}
