package com.nuvio.tv.core.player

import com.nuvio.tv.core.performance.DeviceProfile
import com.nuvio.tv.core.util.DebugSwitches

/**
 * Whether 25 and 29.97/30 fps take the exact refresh rate before the doubled one (ROADMAP Q2.8).
 *
 * Upstream prefers 50 and 59.94/60 Hz for them. On Nvidia the exact rate goes first: on the Shield
 * a 25 fps title converted to Dolby Vision at 3840x2160 50 Hz dropped the HDMI link every few
 * seconds, and 25 Hz played it cleanly. A display without the exact rate still gets the doubled one.
 * `adb shell setprop debug.nuvio.afr_exact_first 0` gives upstream's order back; `1` turns the
 * exact-first order on for any box.
 */
internal object AfrExactFirst {
    const val SWITCH = "debug.nuvio.afr_exact_first"

    fun enabled(): Boolean = enabled(DeviceProfile.isNvidia(), DebugSwitches.flag(SWITCH))

    fun enabled(isNvidia: Boolean, switch: Boolean?): Boolean = switch ?: isNvidia
}
