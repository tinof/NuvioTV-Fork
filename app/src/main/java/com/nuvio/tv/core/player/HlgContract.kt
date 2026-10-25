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

    /**
     * HLG, but a tag or the stream contradicts the 8.4 base layer: native playback. [transferFromSei]:
     * HLG came from an alternative-transfer SEI (DV2.10).
     */
    data class Refused(val reason: String, val transferFromSei: Boolean = false) : HlgDecision

    /**
     * HLG that a Profile 8.4 RPU describes. [untaggedAccepted] names the fields neither the container
     * nor the SPS states, for the caller to log. [baseLayerColorInfo] is the colour the base layer is
     * signalled with: BT.2020, limited range, HLG, never PQ or the HDR10 defaults. [transferFromSei]:
     * HLG comes from an alternative-transfer SEI over an HLG-compatible stated transfer (DV2.10).
     */
    data class Hlg84(
        val untaggedAccepted: List<String>,
        val baseLayerColorInfo: ColorInfo,
        val transferFromSei: Boolean = false,
    ) : HlgDecision
}

/**
 * What the PQ contract (ROADMAP DV2.3, `docs/dv-conversion/DESIGN.md` 2.5) decides for a track: the
 * HLG contract's rules with transfer 16, for a Profile 8.1 RPU over an HDR10 base layer.
 */
@UnstableApi
internal sealed interface PqDecision {
    /** A transfer other than PQ is stated: the PQ row does not apply. */
    data object NotPq : PqDecision

    /** No transfer is stated at track init. */
    data object TransferUnknown : PqDecision

    /** PQ, but a tag or the stream contradicts the 8.1 base layer: native playback. */
    data class Refused(val reason: String) : PqDecision

    /**
     * PQ that a Profile 8.1 RPU describes. [untaggedAccepted] names the fields neither the container
     * nor the SPS states. [baseLayerColorInfo] is BT.2020, limited range, PQ.
     */
    data class Pq81(val untaggedAccepted: List<String>, val baseLayerColorInfo: ColorInfo) : PqDecision
}

/**
 * The HLG contract: decides from the container Colour values and the SPS VUI, at track init, whether
 * an HEVC track is HLG an 8.4 RPU can describe. The 8.4 composer describes only 4:2:0, limited-range
 * BT.2020 NCL input with BT.2020 primaries, and retagging cannot repair a source that says otherwise,
 * so a stated contradiction refuses; a field no source states is accepted. [resolvePq] applies the
 * same rules to PQ for Profile 8.1 (DV2.3). Pure: reads, decides, logs nothing itself.
 */
@UnstableApi
internal object HlgContract {

    // ISO/IEC 23091-2 (H.273) code points.
    const val TRANSFER_UNSPECIFIED = 2
    const val TRANSFER_PQ = 16
    const val TRANSFER_HLG = 18

    /**
     * BT.709 (1), BT.601 (6) and BT.2020 10- and 12-bit (14, 15): H.273 gives them one curve, the SDR
     * curve HLG is backward compatible with. Broadcast HLG states one of them in the VUI and HLG (18)
     * in an alternative-transfer SEI (H.265 D.2.38), so a decoder that ignores the SEI shows SDR;
     * BBC broadcast HLG states 14, and hdr-analyze met 1 in broadcast cuts as well.
     */
    @JvmField
    val HLG_COMPATIBLE_TRANSFERS: Set<Int> = setOf(1, 6, 14, 15)
    const val PRIMARIES_UNSPECIFIED = 2
    const val PRIMARIES_BT2020 = 9
    const val MATRIX_UNSPECIFIED = 2
    const val MATRIX_BT2020_NCL = 9
    const val MATRIX_BT2020_CL = 10

    // Matroska Colour\Range.
    const val RANGE_UNSPECIFIED = 0
    const val RANGE_BROADCAST = 1
    const val RANGE_FULL = 2
    const val RANGE_DEFINED_BY_MATRIX_AND_TRANSFER = 3

    private const val CHROMA_420 = 1

    private const val HEVC_PROFILE_MAIN = 1
    private const val HEVC_PROFILE_MAIN_10 = 2
    private const val HEVC_PROFILE_RANGE_EXTENSIONS = 4

    @JvmStatic
    fun resolve(container: ContainerColour, vui: HevcVuiColour?): HlgDecision =
        when (val result = resolveBaseLayer(TRANSFER_HLG, container, vui)) {
            Contract.OtherTransfer -> HlgDecision.NotHlg
            Contract.TransferUnknown -> HlgDecision.TransferUnknown
            is Contract.Refused -> HlgDecision.Refused(result.reason)
            is Contract.Accepted -> HlgDecision.Hlg84(result.untaggedAccepted, result.colorInfo)
        }

    /**
     * [resolve] with the preferred transfer of an alternative-transfer SEI ([seiPreferredTransfer], null
     * without one), ROADMAP DV2.10. When the SEI says HLG and every transfer the container and the SPS
     * state is HLG, unspecified or in [HLG_COMPATIBLE_TRANSFERS], they are read as HLG and the other
     * field rules decide as before; a [HlgDecision.Hlg84] or [HlgDecision.Refused] that needed the SEI
     * has `transferFromSei`. A stated transfer outside that set (PQ, for example) keeps the decision
     * without the SEI.
     */
    @JvmStatic
    fun resolve(container: ContainerColour, vui: HevcVuiColour?, seiPreferredTransfer: Int?): HlgDecision {
        val stated = resolve(container, vui)
        if (seiPreferredTransfer != TRANSFER_HLG || stated is HlgDecision.Hlg84) return stated
        val statedTransfers = listOfNotNull(container.transfer, vui?.transferCharacteristics)
        if (statedTransfers.any { !liftsToHlg(it) }) return stated
        val lifted = resolve(
            ContainerColour(
                container.primaries,
                TRANSFER_HLG,
                container.matrix,
                container.range,
                container.hdrStaticInfo
            ),
            vui?.copy(transferCharacteristics = TRANSFER_HLG),
        )
        return when (lifted) {
            is HlgDecision.Hlg84 -> lifted.copy(transferFromSei = true)
            // Already refused as HLG without the SEI: the SEI changed nothing.
            is HlgDecision.Refused -> if (stated is HlgDecision.Refused) stated else lifted.copy(transferFromSei = true)
            else -> lifted
        }
    }

    private fun liftsToHlg(transfer: Int): Boolean =
        transfer == TRANSFER_HLG || transfer == TRANSFER_UNSPECIFIED || transfer in HLG_COMPATIBLE_TRANSFERS

    /** The same rules with transfer 16: whether a Profile 8.1 RPU describes the track (DV2.3). */
    @JvmStatic
    fun resolvePq(container: ContainerColour, vui: HevcVuiColour?): PqDecision =
        when (val result = resolveBaseLayer(TRANSFER_PQ, container, vui)) {
            Contract.OtherTransfer -> PqDecision.NotPq
            Contract.TransferUnknown -> PqDecision.TransferUnknown
            is Contract.Refused -> PqDecision.Refused(result.reason)
            is Contract.Accepted -> PqDecision.Pq81(result.untaggedAccepted, result.colorInfo)
        }

    /** One transfer's contract, before it is named as an HLG or a PQ decision. */
    private sealed interface Contract {
        data object OtherTransfer : Contract
        data object TransferUnknown : Contract
        data class Refused(val reason: String) : Contract
        data class Accepted(val untaggedAccepted: List<String>, val colorInfo: ColorInfo) : Contract
    }

    private fun resolveBaseLayer(transfer: Int, container: ContainerColour, vui: HevcVuiColour?): Contract {
        val name = if (transfer == TRANSFER_HLG) "HLG" else "PQ"
        val containerTransfer = container.transfer?.takeIf { it != TRANSFER_UNSPECIFIED }
        val vuiTransfer = vui?.transferCharacteristics?.takeIf { it != TRANSFER_UNSPECIFIED }
        if (containerTransfer != transfer && vuiTransfer != transfer) {
            return if (containerTransfer != null || vuiTransfer != null) {
                Contract.OtherTransfer
            } else {
                Contract.TransferUnknown
            }
        }
        if (containerTransfer != null && containerTransfer != transfer) {
            return Contract.Refused("transfer $containerTransfer in the container, $name in the SPS")
        }
        if (vuiTransfer != null && vuiTransfer != transfer) {
            return Contract.Refused("transfer $vuiTransfer in the SPS, $name in the container")
        }
        if (vui == null) return Contract.Refused("no readable SPS")
        if (vui.generalProfileIdc != HEVC_PROFILE_MAIN &&
            vui.generalProfileIdc != HEVC_PROFILE_MAIN_10 &&
            vui.generalProfileIdc != HEVC_PROFILE_RANGE_EXTENSIONS
        ) {
            return Contract.Refused("HEVC profile ${vui.generalProfileIdc}, not Main 10")
        }
        if (vui.bitDepthLuma != 10 || vui.bitDepthChroma != 10) {
            return Contract.Refused("bit depth ${vui.bitDepthLuma}/${vui.bitDepthChroma}, not 10")
        }
        // The 8.1 and 8.4 RPUs describe a 4:2:0 base layer.
        if (vui.chromaFormatIdc != CHROMA_420) {
            return Contract.Refused("chroma_format_idc ${vui.chromaFormatIdc}, not 4:2:0")
        }

        val untagged = mutableListOf<String>()
        val containerRange = when (container.range) {
            RANGE_BROADCAST -> C.COLOR_RANGE_LIMITED
            RANGE_FULL -> C.COLOR_RANGE_FULL
            null, RANGE_UNSPECIFIED, RANGE_DEFINED_BY_MATRIX_AND_TRANSFER -> null
            // Out of spec: a stated value that is not limited, never read as untagged.
            else -> return Contract.Refused(
                "range ${container.range} in the container, not a Matroska Range code"
            )
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
            .setColorTransfer(if (transfer == TRANSFER_HLG) C.COLOR_TRANSFER_HLG else C.COLOR_TRANSFER_ST2084)
            .setHdrStaticInfo(container.hdrStaticInfo)
            .setLumaBitdepth(vui.bitDepthLuma)
            .setChromaBitdepth(vui.bitDepthChroma)
            .build()
        return Contract.Accepted(untagged, baseLayerColorInfo)
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
    ): Contract.Refused? {
        if (containerValue != null && vuiValue != null && containerValue != vuiValue) {
            return Contract.Refused(
                "$name ${describe(containerValue)} in the container, ${describe(vuiValue)} in the SPS"
            )
        }
        if (containerValue != null && containerValue != required) {
            return Contract.Refused("$name ${describe(containerValue)} in the container")
        }
        if (vuiValue != null && vuiValue != required) {
            return Contract.Refused("$name ${describe(vuiValue)} in the SPS")
        }
        if (containerValue == null && vuiValue == null) untagged += name
        return null
    }

    private fun rangeName(range: Int): String = if (range == C.COLOR_RANGE_FULL) "full" else "limited"

    /**
     * The one log line for a track's decision, with the alternative-transfer SEI's preferred
     * transfer ([preferredTransferSei], null when neither the hvcC nor the first sample has one), which
     * never changes the decision.
     */
    @JvmStatic
    fun logLine(trackNumber: Int, decision: HlgDecision, preferredTransferSei: Int?): String {
        val what = when (decision) {
            HlgDecision.NotHlg -> "not HLG"
            HlgDecision.TransferUnknown -> "transfer unknown"
            is HlgDecision.Refused ->
                if (decision.transferFromSei) {
                    "HLG from the alternative transfer SEI refused: ${decision.reason}"
                } else {
                    "HLG refused: ${decision.reason}"
                }
            is HlgDecision.Hlg84 -> buildString {
                append("HLG for 8.4")
                if (decision.transferFromSei) append(" from the alternative transfer SEI")
                if (decision.untaggedAccepted.isNotEmpty()) {
                    append(", untagged accepted: ${decision.untaggedAccepted.joinToString()}")
                }
            }
        }
        val actedOn = (decision is HlgDecision.Hlg84 && decision.transferFromSei) ||
            (decision is HlgDecision.Refused && decision.transferFromSei)
        val sei = preferredTransferSei?.takeIf { !actedOn }?.let { "; alternative transfer SEI $it (not acted on)" } ?: ""
        return "HLG contract, track $trackNumber: $what$sei"
    }
}
