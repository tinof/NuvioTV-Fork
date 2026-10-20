package com.nuvio.tv.core.image

import com.nuvio.tv.core.performance.DeviceProfile.RamClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDiskCacheSizeTest {
    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test fun boxesWithThreeGigabytesOrMoreAndFreeStorageGetTheLargerCache() {
        assertEquals(300L * mib, imageDiskCacheBytes(RamClass.MID, 4L * gib))
        assertEquals(300L * mib, imageDiskCacheBytes(RamClass.LARGE, 20L * gib))
    }

    @Test fun smallBoxesFullStorageOrUnknownMemoryKeepTheSmallCache() {
        assertEquals(200L * mib, imageDiskCacheBytes(RamClass.SMALL, 20L * gib))
        assertEquals(200L * mib, imageDiskCacheBytes(RamClass.MID, 4L * gib - 1))
        assertEquals(200L * mib, imageDiskCacheBytes(RamClass.LARGE, 0L))
        assertEquals(200L * mib, imageDiskCacheBytes(RamClass.UNKNOWN, 20L * gib))
    }
}
