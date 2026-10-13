package com.nuvio.tv.core.player

import androidx.media3.common.C

/**
 * Upper bound for a passthrough AudioTrack buffer, per encoding. media3's default sizes
 * TrueHD / DTS-HD buffers for seconds of audio, which delays start and seek.
 */
object PassthroughBufferCaps {

    const val MAX_HBR_SCALE = 16

    /**
     * [hbrScale] multiplies the TrueHD and DTS-HD caps only (debug.nuvio.hbr_buffer_scale);
     * null or below 1 keeps the default, values above [MAX_HBR_SCALE] are clamped.
     */
    fun capBytes(encoding: Int, hbrScale: Int? = null): Int {
        val scale = hbrScale?.takeIf { it >= 1 }?.coerceAtMost(MAX_HBR_SCALE) ?: 1
        return when (encoding) {
            C.ENCODING_DOLBY_TRUEHD -> 2 * 61_440 * scale
            C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> 4 * 30_720 * scale
            C.ENCODING_DTS -> 16 * 2_012
            C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> 2 * 10_752
            C.ENCODING_AC3 -> 1_536 * 8
            C.ENCODING_AC4 -> 16_384
            else -> 16_384
        }
    }
}
