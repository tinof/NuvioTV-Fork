package com.nuvio.tv.data.trailer

import android.content.Context
import android.os.Build
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.performance.DeviceProfile
import com.nuvio.tv.core.player.VideoDecoderCapabilities
import com.nuvio.tv.core.util.DebugSwitches

/** Trailers play on top of the UI through a TextureView; this cap holds on every device. */
internal const val TRAILER_MAX_VIDEO_HEIGHT = 1080
internal const val TRAILER_MAX_VIDEO_WIDTH = TRAILER_MAX_VIDEO_HEIGHT * 16 / 9

internal enum class TrailerVideoCodec {
    H264,
    VP9,
    AV1,
    OTHER
}

/** What this device can show, as far as picking a trailer stream is concerned. */
internal data class TrailerVideoLimits(
    val maxHeight: Int = TRAILER_MAX_VIDEO_HEIGHT,
    val maxWidth: Int = TRAILER_MAX_VIDEO_WIDTH,
    // Same question the player's video renderer asks: a decoder for this codec, profile, size and frame rate.
    val canDecode: (codecs: String, width: Int, height: Int, fps: Int) -> Boolean = { _, _, _, _ -> true },
    val hdrDisplay: Boolean = false
)

private val CODECS_REGEX = Regex("codecs=\"([^\"]+)\"")
private val HDR_TRANSFERS = setOf(
    "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084",
    "COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67"
)

internal fun trailerVideoCodecOf(mimeType: String): TrailerVideoCodec {
    val codecs = CODECS_REGEX.find(mimeType)?.groupValues?.get(1)?.lowercase().orEmpty()
    return when {
        codecs.startsWith("avc1") || codecs.startsWith("avc3") -> TrailerVideoCodec.H264
        codecs.startsWith("vp9") || codecs.startsWith("vp09") -> TrailerVideoCodec.VP9
        codecs.startsWith("av01") -> TrailerVideoCodec.AV1
        else -> TrailerVideoCodec.OTHER
    }
}

internal fun isHdrTrailerFormat(mimeType: String, qualityLabel: String?, transferCharacteristics: String?): Boolean {
    val codecs = CODECS_REGEX.find(mimeType)?.groupValues?.get(1)?.lowercase().orEmpty()
    return transferCharacteristics in HDR_TRANSFERS ||
        qualityLabel?.contains("HDR", ignoreCase = true) == true ||
        codecs.startsWith("vp09.02")
}

/**
 * The video streams a trailer may use on this device. A stream the device has no decoder for
 * is never used: it would play as audio over a black screen, while the HLS manifest or a
 * progressive file still works. The other limits are given up one at a time (HDR, then size)
 * so a trailer still plays.
 */
internal fun trailerVideoCandidates(
    candidates: List<StreamCandidate>,
    limits: TrailerVideoLimits
): List<StreamCandidate> {
    val decodable = candidates.filter { candidate ->
        val codecs = candidate.codecs ?: return@filter true
        limits.canDecode(codecs, candidate.width, candidate.height, candidate.fps)
    }
    fun allowed(size: Boolean, hdr: Boolean) = decodable.filter { candidate ->
        (!size || (candidate.height <= limits.maxHeight && candidate.width <= limits.maxWidth)) &&
            (!hdr || limits.hdrDisplay || !candidate.isHdr)
    }
    return allowed(size = true, hdr = true)
        .ifEmpty { allowed(size = true, hdr = false) }
        .ifEmpty { allowed(size = false, hdr = true) }
        .ifEmpty { decodable }
}

internal fun trailerVideoCodecRank(codec: TrailerVideoCodec?): Int = when (codec) {
    TrailerVideoCodec.H264 -> 0
    TrailerVideoCodec.VP9 -> 1
    TrailerVideoCodec.AV1 -> 2
    TrailerVideoCodec.OTHER -> 3
    null -> 0
}

/**
 * Trailers draw into a TextureView, which composes in SDR whatever the display supports, so an HDR trailer
 * would only show washed out: they always pick an SDR stream.
 */
@Suppress("UNUSED_PARAMETER")
internal fun deviceTrailerVideoLimits(context: Context): TrailerVideoLimits = TrailerVideoLimits(
    canDecode = trailerCanDecode(
        av1HardwareOnly = trailerAv1HardwareOnly(DebugSwitches.flag("debug.nuvio.trailer_av1_hw")),
        hasHardwareAv1 = { VideoDecoderCapabilities.hasHardwareDecoder(MimeTypes.VIDEO_AV1) },
    ),
    hdrDisplay = false
)

/**
 * On Nvidia an AV1 trailer needs a hardware decoder, as before NuvioTV 1.1.0-beta.7: the Shield has none, and
 * Android's software AV1 decoder would run beside the UI. [switch] (debug.nuvio.trailer_av1_hw): false accepts any decoder that claims the
 * format, true asks for hardware on any box.
 */
internal fun trailerAv1HardwareOnly(switch: Boolean?, manufacturer: String? = Build.MANUFACTURER): Boolean =
    switch ?: DeviceProfile.isNvidia(manufacturer)

internal fun trailerCanDecode(
    av1HardwareOnly: Boolean,
    hasHardwareAv1: () -> Boolean,
    canDecode: (codecs: String, width: Int, height: Int, fps: Int) -> Boolean = VideoDecoderSupport::canDecode,
): (codecs: String, width: Int, height: Int, fps: Int) -> Boolean = { codecs, width, height, fps ->
    (!av1HardwareOnly || videoCodecMimeType(codecs) != MimeTypes.VIDEO_AV1 || hasHardwareAv1()) &&
        canDecode(codecs, width, height, fps)
}
