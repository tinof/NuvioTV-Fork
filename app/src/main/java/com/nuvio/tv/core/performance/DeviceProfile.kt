package com.nuvio.tv.core.performance

import android.os.Build

/**
 * Known-device facts that capability probing cannot give. Codec and audio
 * support still come from MediaCodecList and AudioCapabilities; this only
 * picks defaults where a probe has no answer (for example the Shield's
 * system frame-rate matching, which no API reports).
 *
 * Every function takes the Build fields as nullable parameters: unit tests
 * see Build.* as null because of isReturnDefaultValues.
 */
object DeviceProfile {

    /** Shield TV codenames: 2019 Pro (mdarcy), 2019 tube (sif), 2017 (darcy), 2015 (foster). */
    private val SHIELD_DEVICES = setOf("mdarcy", "sif", "darcy", "foster")

    /** The 2019 Shields, the only ones with AI upscaling. */
    private val SHIELD_2019_DEVICES = setOf("mdarcy", "sif")

    private const val MIB = 1024L * 1024L
    private const val SMALL_BELOW_BYTES = 2_300L * MIB
    private const val LARGE_FROM_BYTES = 3_300L * MIB

    enum class RamClass { SMALL, MID, LARGE, UNKNOWN }

    fun isNvidia(manufacturer: String? = Build.MANUFACTURER): Boolean =
        manufacturer.orEmpty().trim().equals("NVIDIA", ignoreCase = true)

    fun isShield(
        manufacturer: String? = Build.MANUFACTURER,
        device: String? = Build.DEVICE,
    ): Boolean = isNvidia(manufacturer) && device.orEmpty().trim().lowercase() in SHIELD_DEVICES

    fun isShieldPro2019(
        manufacturer: String? = Build.MANUFACTURER,
        device: String? = Build.DEVICE,
    ): Boolean = isNvidia(manufacturer) && device.orEmpty().trim().equals("mdarcy", ignoreCase = true)

    /** 2019 Pro (mdarcy) or 2019 tube (sif): the Shields with AI upscaling. */
    fun isShield2019(
        manufacturer: String? = Build.MANUFACTURER,
        device: String? = Build.DEVICE,
    ): Boolean = isNvidia(manufacturer) && device.orEmpty().trim().lowercase() in SHIELD_2019_DEVICES

    /** 3 GB boxes report 2.6 to 2.9 GiB, so MID is the "3 GB" class. */
    fun ramClass(totalMemBytes: Long): RamClass = when {
        totalMemBytes <= 0L -> RamClass.UNKNOWN
        totalMemBytes < SMALL_BELOW_BYTES -> RamClass.SMALL
        totalMemBytes < LARGE_FROM_BYTES -> RamClass.MID
        else -> RamClass.LARGE
    }
}
