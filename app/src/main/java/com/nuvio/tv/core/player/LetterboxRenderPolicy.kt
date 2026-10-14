package com.nuvio.tv.core.player

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.nuvio.tv.core.performance.DeviceProfile

object LetterboxRenderPolicy {

    fun defaultTransparentLetterbox(manufacturer: String? = Build.MANUFACTURER): Boolean {
        return !manufacturer.orEmpty().trim().equals("Amazon", ignoreCase = true)
    }

    /**
     * Opaque black window background outside playback, so the app layer is opaque to the compositor. On Nvidia.
     * [switch] (debug.nuvio.opaque_window): true on any box, false keeps the transparent window everywhere.
     */
    fun opaqueWindowOutsidePlayback(switch: Boolean?, manufacturer: String? = Build.MANUFACTURER): Boolean =
        switch ?: DeviceProfile.isNvidia(manufacturer)

    /** The window stays transparent while the player asks for a transparent letterbox. */
    fun opaqueWindowNow(opaqueOutsidePlayback: Boolean, transparentRequested: Boolean): Boolean =
        opaqueOutsidePlayback && !transparentRequested
}

object PlayerWindowBackdrop {

    private var transparentRequests by mutableIntStateOf(0)

    val isTransparentRequested: Boolean
        get() = transparentRequests > 0

    fun acquireTransparent() {
        transparentRequests++
    }

    fun releaseTransparent() {
        transparentRequests = (transparentRequests - 1).coerceAtLeast(0)
    }
}
