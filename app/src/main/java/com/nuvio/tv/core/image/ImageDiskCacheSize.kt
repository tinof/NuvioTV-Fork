package com.nuvio.tv.core.image

import android.os.Build
import com.nuvio.tv.core.performance.DeviceProfile

private const val MB = 1024L * 1024
private const val GB = 1024L * MB

/**
 * Disk room for posters and backdrops: 1 GB with more than 10 GB free, otherwise 300 MB, or 200 MB under ~3.3 GB of
 * RAM. With [nvidiaTier], a box under ~3.3 GB of RAM (the Shield TV Pro 2019 reports 2946 MB) keeps 300 MB while it has
 * at least 4 GB free, as the fork gave it before the sync of 2026-10-26.
 */
fun imageDiskCacheBytes(totalRamBytes: Long, freeStorageBytes: Long, nvidiaTier: Boolean = false): Long = when {
    freeStorageBytes > 10 * GB -> 1 * GB
    totalRamBytes >= 3_300L * MB -> 300 * MB
    nvidiaTier && freeStorageBytes >= 4 * GB -> 300 * MB
    else -> 200 * MB
}

/**
 * The 300 MB middle tier of [imageDiskCacheBytes]: on by default on Nvidia. [switch] (debug.nuvio.image_cache_tier):
 * false takes upstream's rule on Nvidia too, true gives the tier to any box.
 */
internal fun nvidiaImageDiskTier(switch: Boolean?, manufacturer: String? = Build.MANUFACTURER): Boolean =
    switch ?: DeviceProfile.isNvidia(manufacturer)
