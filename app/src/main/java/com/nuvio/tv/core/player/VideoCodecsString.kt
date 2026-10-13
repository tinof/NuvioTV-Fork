package com.nuvio.tv.core.player

import java.util.Locale

/**
 * Reads RFC 6381 codecs strings as Media3 reports them in Format.codecs ("avc1.6E0028", "hvc1.2.4.L150",
 * "av01.0.13M.10"). Only the first codec of a list is read.
 */
internal object VideoCodecsString {

    /** H.264 High 10 (110), High 4:2:2 (122) and High 4:4:4 Predictive (244): may be more than 8 bits. */
    private val AVC_HIGH_BIT_DEPTH_PROFILES = setOf(110, 122, 244)

    private fun first(codecs: String?): String? =
        codecs?.substringBefore(',')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun isAvc(c: String): Boolean = c.startsWith("avc1") || c.startsWith("avc3")

    /** profile_idc of an H.264 codecs string (avc1.PPCCLL, hex), or null. */
    fun avcProfileIdc(codecs: String?): Int? {
        val c = first(codecs) ?: return null
        if (!isAvc(c)) return null
        return c.substringAfter('.', "").take(2).takeIf { it.length == 2 }?.toIntOrNull(16)
    }

    /** Hi10P and the 4:2:2 / 4:4:4 profiles, which most hardware H.264 decoders do not take. */
    fun isAvcHighBitDepthProfile(codecs: String?): Boolean =
        avcProfileIdc(codecs)?.let { it in AVC_HIGH_BIT_DEPTH_PROFILES } == true

    /** Luma bit depth the codecs string states or implies, or null when it does not say. */
    fun bitDepth(codecs: String?): Int? {
        val c = first(codecs) ?: return null
        return when {
            c.startsWith("dvhe") || c.startsWith("dvh1") -> 10
            c.startsWith("hvc1.2.") || c.startsWith("hev1.2.") -> 10
            c.startsWith("hvc1.1.") || c.startsWith("hev1.1.") -> 8
            isAvc(c) -> {
                val p = c.substringAfter('.', "").take(2).toIntOrNull(16)
                if (p == null || p in AVC_HIGH_BIT_DEPTH_PROFILES) null else 8
            }
            c.startsWith("av01") -> c.split('.').getOrNull(3)?.toIntOrNull()
            else -> null
        }
    }

    /** Chroma subsampling the codecs string implies ("4:2:0"), or null when it does not say. */
    fun chroma(codecs: String?): String? {
        val c = first(codecs) ?: return null
        return when {
            c.startsWith("dvhe") || c.startsWith("dvh1") -> "4:2:0"
            c.startsWith("hvc1.1.") || c.startsWith("hev1.1.") || c.startsWith("hvc1.2.") || c.startsWith("hev1.2.") -> "4:2:0"
            isAvc(c) -> {
                val p = c.substringAfter('.', "").take(2).toIntOrNull(16)
                if (p != null && p < 122) "4:2:0" else null
            }
            c.startsWith("av01.0") -> "4:2:0"
            else -> null
        }
    }
}
