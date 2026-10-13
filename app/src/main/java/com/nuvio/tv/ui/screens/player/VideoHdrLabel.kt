package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

/**
 * Human-friendly HDR/output type for the diagnostics card — reflects what is
 * actually output, not just the source track mime. When DV7 is stripped to the
 * HDR10 base layer the output is HDR10/SDR even though the track mime is DV.
 * [dvDecoderActive] is whether the active video decoder is a Dolby Vision one (null
 * when unknown): a DV stream on the plain HEVC decoder plays its base layer only.
 */
internal fun friendlyVideoHdrType(
    mimeType: String?,
    colorTransfer: Int?,
    effectiveModeName: String?,
    dvConversionOccurred: Boolean,
    dvDecoderActive: Boolean? = null
): String? {
    val isDolbyVisionMime = mimeType?.lowercase() == MimeTypes.VIDEO_DOLBY_VISION
    fun fromTransfer(): String? = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
    fun dolbyVision(): String =
        if (dvDecoderActive == false) "${fromTransfer() ?: "HDR10"} (DV decoder not used)" else "Dolby Vision"
    return when {
        // Ignore DV data: output is HDR10/SDR, never Dolby Vision.
        effectiveModeName == "HDR10_BASE_LAYER" -> fromTransfer() ?: "HDR10"
        // DV RPU stripped: output is HDR10 base layer, never Dolby Vision.
        effectiveModeName == "STRIP_DV" -> fromTransfer() ?: "HDR10"
        // DV8.1 conversion, but only label it DV if a conversion actually ran. AUTO arms
        // this mode for every file on a DV display, so plain SDR/HDR10 lands here too.
        effectiveModeName == "DV81_LIBDOVI" && dvConversionOccurred -> dolbyVision()
        effectiveModeName == "DV81_LIBDOVI" -> fromTransfer()
        // Native DV passthrough.
        isDolbyVisionMime -> dolbyVision()
        else -> fromTransfer()
    }
}
