package com.nuvio.tv.core.player

/**
 * Whether HEVC tracks that carry no Dolby Vision may be signalled as Dolby Vision in this playback
 * (ROADMAP DV2.3). Resolved once per playback in the player and carried to the Matroska extractor on
 * [DolbyVisionConversionConfig.convertNonDolbyVision]. Off unless every input allows it; [Result.reason]
 * names the first one that does not, in a fixed order.
 */
internal object DvConversionGate {

    /** The revert switch: `adb shell setprop debug.nuvio.dv_convert 0` turns conversion off. */
    const val REVERT_SWITCH = "debug.nuvio.dv_convert"

    data class Result(val enabled: Boolean, val reason: String)

    /**
     * [revertSwitch] is the [REVERT_SWITCH] flag: false turns conversion off, true and unset change
     * nothing (a Dolby Vision Format without RPUs must never reach the decoder, so the switch cannot
     * force conversion on). [streamDisabled] is set for a stream whose converted playback failed to
     * decode; [stripOrBaseLayerMode] and [mapToHevcMode] are the Dolby Vision strip and HDR10
     * base-layer modes, whose players skip the Dolby Vision decoder-failure fallback.
     */
    fun resolve(
        setting: Boolean,
        revertSwitch: Boolean?,
        isNvidia: Boolean,
        dolbyVisionDecoderPresent: Boolean,
        displayDolbyVision: Boolean,
        rpuBuilderAvailable: Boolean,
        streamDisabled: Boolean,
        stripOrBaseLayerMode: Boolean,
        mapToHevcMode: Boolean,
    ): Result = when {
        !setting -> Result(false, "setting off")
        revertSwitch == false -> Result(false, "$REVERT_SWITCH=0")
        !isNvidia -> Result(false, "not an Nvidia device")
        !dolbyVisionDecoderPresent -> Result(false, "no Dolby Vision decoder")
        !displayDolbyVision -> Result(false, "display without Dolby Vision")
        !rpuBuilderAvailable -> Result(false, "no RPU builder (libnuvio_rpu)")
        streamDisabled -> Result(false, "converted playback of this stream failed before")
        stripOrBaseLayerMode -> Result(false, "Dolby Vision strip or HDR10 base-layer mode")
        mapToHevcMode -> Result(false, "Dolby Vision mapped to HEVC")
        else -> Result(true, "on")
    }

    fun logLine(result: Result): String =
        if (result.enabled) "DV_CONVERT: on" else "DV_CONVERT: off (${result.reason})"
}
