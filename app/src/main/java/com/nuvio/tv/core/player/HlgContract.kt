package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi

/**
 * The raw Matroska Colour values of a track (Matroska spec, Video\Colour): H.273 code points for
 * [primaries], [transfer] and [matrix], the Matroska Range code for [range] (0 unspecified,
 * 1 broadcast/limited, 2 full, 3 defined by matrix and transfer), null for an element the track does
 * not have. [hdrStaticInfo] is the CTA-861.3 block the extractor builds from MasteringMetadata, MaxCLL
 * and MaxFALL, or null.
 */
@UnstableApi
internal class ContainerColour(
    val primaries: Int?,
    val transfer: Int?,
    val matrix: Int?,
    val range: Int?,
    val hdrStaticInfo: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ContainerColour &&
            primaries == other.primaries &&
            transfer == other.transfer &&
            matrix == other.matrix &&
            range == other.range &&
            hdrStaticInfo.contentEquals(other.hdrStaticInfo)

    override fun hashCode(): Int =
        listOf(primaries, transfer, matrix, range, hdrStaticInfo.contentHashCode()).hashCode()

    override fun toString(): String =
        "ContainerColour(primaries=$primaries, transfer=$transfer, matrix=$matrix, range=$range, " +
            "hdrStaticInfo=${hdrStaticInfo != null})"
}

/** What the HLG contract (ROADMAP DV2.4, `docs/dv-conversion/DESIGN.md` 2.5) decides for a track. */
@UnstableApi
internal sealed interface HlgDecision {
    /** A transfer other than HLG is stated: the HLG rows do not apply. */
    data object NotHlg : HlgDecision

    /** No transfer is stated at track init, for example HLG signalled only in an SEI. */
    data object TransferUnknown : HlgDecision

    /** HLG, but a tag or the stream contradicts the 8.4 base layer: native playback. */
    data class Refused(val reason: String) : HlgDecision

    /**
     * HLG that a Profile 8.4 RPU describes. [untaggedAccepted] names the fields neither the container
     * nor the SPS states, for the caller to log. [baseLayerColorInfo] is the colour the base layer is
     * signalled with: BT.2020, limited range, HLG, never PQ or the HDR10 defaults.
     */
    data class Hlg84(val untaggedAccepted: List<String>, val baseLayerColorInfo: ColorInfo) : HlgDecision
}

/**
 * The HLG contract: decides from the container Colour values and the SPS VUI, at track init, whether
 * an HEVC track is HLG an 8.4 RPU can describe. The 8.4 composer describes only limited-range BT.2020
 * NCL input with BT.2020 primaries, and retagging cannot repair a source that says otherwise, so a
 * stated contradiction refuses; a field no source states is accepted. Pure: reads, decides, logs
 * nothing itself.
 */
@UnstableApi
internal object HlgContract {

    // ISO/IEC 23091-2 (H.273) code points.
    const val TRANSFER_UNSPECIFIED = 2
    const val TRANSFER_PQ = 16
    const val TRANSFER_HLG = 18
    const val PRIMARIES_UNSPECIFIED = 2
    const val PRIMARIES_BT2020 = 9
    const val MATRIX_UNSPECIFIED = 2
    const val MATRIX_BT2020_NCL = 9
    const val MATRIX_BT2020_CL = 10

    // Matroska Colour\Range.
    const val RANGE_BROADCAST = 1
    const val RANGE_FULL = 2

    private const val HEVC_PROFILE_MAIN = 1
    private const val HEVC_PROFILE_MAIN_10 = 2
    private const val HEVC_PROFILE_RANGE_EXTENSIONS = 4

    @JvmStatic
    fun resolve(container: ContainerColour, vui: HevcVuiColour?): HlgDecision {
        val containerTransfer = container.transfer?.takeIf { it != TRANSFER_UNSPECIFIED }
        val vuiTransfer = vui?.transferCharacteristics?.takeIf { it != TRANSFER_UNSPECIFIED }
        if (containerTransfer != TRANSFER_HLG && vuiTransfer != TRANSFER_HLG) {
            return if (containerTransfer != null || vuiTransfer != null) {
                HlgDecision.NotHlg
            } else {
                HlgDecision.TransferUnknown
            }
        }
        if (containerTransfer != null && containerTransfer != TRANSFER_HLG) {
            return HlgDecision.Refused("transfer $containerTransfer in the container, HLG in the SPS")
        }
        if (vuiTransfer != null && vuiTransfer != TRANSFER_HLG) {
            return HlgDecision.Refused("transfer $vuiTransfer in the SPS, HLG in the container")
        }
        if (vui == null) return HlgDecision.Refused("no readable SPS")
        if (vui.generalProfileIdc != HEVC_PROFILE_MAIN &&
            vui.generalProfileIdc != HEVC_PROFILE_MAIN_10 &&
            vui.generalProfileIdc != HEVC_PROFILE_RANGE_EXTENSIONS
        ) {
            return HlgDecision.Refused("HEVC profile ${vui.generalProfileIdc}, not Main 10")
        }
        if (vui.bitDepthLuma != 10 || vui.bitDepthChroma != 10) {
            return HlgDecision.Refused("bit depth ${vui.bitDepthLuma}/${vui.bitDepthChroma}, not 10")
        }

        val untagged = mutableListOf<String>()
        val containerRange = when (container.range) {
            RANGE_BROADCAST -> C.COLOR_RANGE_LIMITED
            RANGE_FULL -> C.COLOR_RANGE_FULL
            else -> null // 0 unspecified, 3 defined by matrix and transfer
        }
        val vuiRange = when {
            !vui.videoSignalTypePresent -> null
            vui.videoFullRange -> C.COLOR_RANGE_FULL
            else -> C.COLOR_RANGE_LIMITED
        }
        checkField("range", containerRange, vuiRange, C.COLOR_RANGE_LIMITED, untagged, ::rangeName)
            ?.let { return it }
        checkField(
            "matrix",
            container.matrix?.takeIf { it != MATRIX_UNSPECIFIED },
            vui.matrixCoeffs?.takeIf { it != MATRIX_UNSPECIFIED },
            MATRIX_BT2020_NCL,
            untagged,
            Int::toString
        )?.let { return it }
        checkField(
            "primaries",
            container.primaries?.takeIf { it != PRIMARIES_UNSPECIFIED },
            vui.colourPrimaries?.takeIf { it != PRIMARIES_UNSPECIFIED },
            PRIMARIES_BT2020,
            untagged,
            Int::toString
        )?.let { return it }

        val baseLayerColorInfo = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .setHdrStaticInfo(container.hdrStaticInfo)
            .setLumaBitdepth(vui.bitDepthLuma)
            .setChromaBitdepth(vui.bitDepthChroma)
            .build()
        return HlgDecision.Hlg84(untagged, baseLayerColorInfo)
    }

    /**
     * Refuses a field the two sources state differently, or that either states as something other
     * than [required]; names it in [untagged] when neither states it.
     */
    private fun checkField(
        name: String,
        containerValue: Int?,
        vuiValue: Int?,
        required: Int,
        untagged: MutableList<String>,
        describe: (Int) -> String,
    ): HlgDecision.Refused? {
        if (containerValue != null && vuiValue != null && containerValue != vuiValue) {
            return HlgDecision.Refused(
                "$name ${describe(containerValue)} in the container, ${describe(vuiValue)} in the SPS"
            )
        }
        if (containerValue != null && containerValue != required) {
            return HlgDecision.Refused("$name ${describe(containerValue)} in the container")
        }
        if (vuiValue != null && vuiValue != required) {
            return HlgDecision.Refused("$name ${describe(vuiValue)} in the SPS")
        }
        if (containerValue == null && vuiValue == null) untagged += name
        return null
    }

    private fun rangeName(range: Int): String = if (range == C.COLOR_RANGE_FULL) "full" else "limited"

    /**
     * The one log line for a track's decision, with the alternative-transfer SEI's preferred
     * transfer ([preferredTransferSei], null when the hvcC has none), which never changes the decision.
     */
    @JvmStatic
    fun logLine(trackNumber: Int, decision: HlgDecision, preferredTransferSei: Int?): String {
        val what = when (decision) {
            HlgDecision.NotHlg -> "not HLG"
            HlgDecision.TransferUnknown -> "transfer unknown"
            is HlgDecision.Refused -> "HLG refused: ${decision.reason}"
            is HlgDecision.Hlg84 ->
                if (decision.untaggedAccepted.isEmpty()) {
                    "HLG for 8.4"
                } else {
                    "HLG for 8.4, untagged accepted: ${decision.untaggedAccepted.joinToString()}"
                }
        }
        val sei = preferredTransferSei?.let { "; alternative transfer SEI $it (not acted on)" } ?: ""
        return "HLG contract, track $trackNumber: $what$sei"
    }
}
