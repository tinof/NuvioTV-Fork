package com.nuvio.tv.core.image

import android.os.StatFs
import com.nuvio.tv.core.performance.DeviceProfile
import java.io.File

private const val MIB = 1024L * 1024L
private const val SMALL_DISK_CACHE_BYTES = 200L * MIB
private const val LARGE_DISK_CACHE_BYTES = 300L * MIB
private const val LARGE_CACHE_MIN_FREE_BYTES = 4L * 1024L * MIB

/**
 * Fixed size like the official app. 3 GB boxes (the Shield) get the larger cache too, but only
 * when the storage has room to spare: a full disk on a small box hurts more than refetching posters.
 */
internal fun imageDiskCacheBytes(ramClass: DeviceProfile.RamClass, freeBytes: Long): Long {
    val roomyRam = ramClass == DeviceProfile.RamClass.MID || ramClass == DeviceProfile.RamClass.LARGE
    return if (roomyRam && freeBytes >= LARGE_CACHE_MIN_FREE_BYTES) LARGE_DISK_CACHE_BYTES else SMALL_DISK_CACHE_BYTES
}

/** Free bytes on the volume that holds [dir]; 0 when unknown, which picks the small cache. */
internal fun freeBytesOn(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
