package com.nuvio.tv.core.player

import android.os.Build
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.performance.DeviceProfile

object SurroundFormatResolver {

    fun routeKeyIsHdmiArc(routeKey: String?): Boolean {
        if (routeKey == null) return false
        val type = routeKey.substringAfter("type:", missingDelimiterValue = "")
            .substringBefore("|")
        return type == "hdmi_arc"
    }

    data class DirectSupport(
        val ac3: Boolean,
        val eac3: Boolean,
        val trueHd: Boolean,
        val dts: Boolean,
        val dtsHd: Boolean
    )

    data class ProbeInputs(val direct: DirectSupport?, val maxPcmChannels: Int?)

    /**
     * Whether the surround decision uses the probe union (see [decisionInputs]).
     * debug.nuvio.audio_probe_union=1/0 forces it; unset, on for Nvidia only.
     */
    fun probeUnionEnabled(switch: Boolean?, manufacturer: String? = Build.MANUFACTURER): Boolean =
        switch ?: DeviceProfile.isNvidia(manufacturer)

    /**
     * What the surround decision reads from the chain probe. Without [union] the 5.1 probe and
     * the API 31+ PCM channel read, as before. With [union] (Nvidia default, or
     * debug.nuvio.audio_probe_union=1, see [probeUnionEnabled]) a format counts when it opens
     * in any probed layout, and the HDMI plug intent's channel count fills a missing PCM read.
     */
    fun decisionInputs(
        direct: DirectSupport?,
        maxPcmChannels: Int?,
        directAnyLayout: DirectSupport?,
        plugMaxChannels: Int?,
        union: Boolean
    ): ProbeInputs {
        if (!union) return ProbeInputs(direct, maxPcmChannels)
        val merged = when {
            direct == null -> directAnyLayout
            directAnyLayout == null -> direct
            else -> DirectSupport(
                ac3 = direct.ac3 || directAnyLayout.ac3,
                eac3 = direct.eac3 || directAnyLayout.eac3,
                trueHd = direct.trueHd || directAnyLayout.trueHd,
                dts = direct.dts || directAnyLayout.dts,
                dtsHd = direct.dtsHd || directAnyLayout.dtsHd
            )
        }
        return ProbeInputs(merged, maxPcmChannels ?: plugMaxChannels)
    }

    data class Resolution(
        val policy: AudioPassthroughPolicy,
        val transcodePreferred: Boolean,
        val inferredChannelTarget: Int?
    ) {
        companion object {
            val INERT = Resolution(AudioPassthroughPolicy.ALLOW_ALL, false, null)
        }
    }

    private val groupRepresentativeMimes = listOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD
    )

    fun resolve(
        manualMode: Boolean,
        allowAc3: Boolean,
        allowEac3: Boolean,
        allowTrueHd: Boolean,
        allowDts: Boolean,
        allowDtsHd: Boolean,
        manualTranscodePreferred: Boolean,
        manualChannelTargetChannels: Int?,
        direct: DirectSupport?,
        rawMaxPcmChannels: Int?,
        routeIsBluetooth: Boolean,
        routeIsHdmiArc: Boolean,
        softwareDecodersAvailable: Boolean,
        forceOpticalActive: Boolean,
        learnedDeniedGroups: Set<AudioPassthroughPolicy.Group>,
        tvArcSoundbar: Boolean = false
    ): Resolution {
        if (routeIsBluetooth) return Resolution.INERT

        if (!manualMode && forceOpticalActive) return Resolution.INERT

        // Plain ARC carries 2-ch PCM and compressed 5.1 only, whatever the HAL claims.
        val arcSoundbar = !manualMode && (tvArcSoundbar || routeIsHdmiArc)
        val maxPcmChannels = if (arcSoundbar) 2 else rawMaxPcmChannels

        val policy = if (manualMode) {
            AudioPassthroughPolicy(
                allowAc3 = allowAc3,
                allowEac3 = allowEac3,
                allowTrueHd = allowTrueHd,
                allowDts = allowDts,
                allowDtsHd = allowDtsHd,
                softwareDecodersAvailable = softwareDecodersAvailable,
                learnedDeniedGroups = learnedDeniedGroups
            )
        } else if (direct == null) {
            AudioPassthroughPolicy(
                allowTrueHd = !arcSoundbar,
                allowDtsHd = !arcSoundbar,
                softwareDecodersAvailable = softwareDecodersAvailable,
                learnedDeniedGroups = learnedDeniedGroups
            )
        } else {
            val dtsHdAllowed = when {
                arcSoundbar -> direct.dts && !direct.dtsHd
                direct.dtsHd -> true
                direct.dts -> !(maxPcmChannels != null && maxPcmChannels > 2)
                else -> false
            }
            AudioPassthroughPolicy(
                allowAc3 = direct.ac3,
                allowEac3 = direct.eac3,
                allowTrueHd = direct.trueHd && !arcSoundbar,
                allowDts = direct.dts,
                allowDtsHd = dtsHdAllowed,
                softwareDecodersAvailable = softwareDecodersAvailable,
                learnedDeniedGroups = learnedDeniedGroups
            )
        }

        val anythingDenied = groupRepresentativeMimes.any { policy.deniesPassthrough(it) }

        val transcodePreferred = when {
            !anythingDenied -> false
            manualMode -> manualTranscodePreferred
            maxPcmChannels != null && maxPcmChannels > 2 -> false
            maxPcmChannels == 2 -> direct?.ac3 ?: arcSoundbar
            maxPcmChannels == null && routeIsHdmiArc -> direct?.ac3 == true
            else -> false
        }

        val inferredChannelTarget = when {
            manualChannelTargetChannels != null -> manualChannelTargetChannels
            !anythingDenied -> null
            maxPcmChannels != null -> when {
                maxPcmChannels >= 8 -> 8
                maxPcmChannels >= 6 -> 6
                else -> 2
            }
            routeIsHdmiArc -> 2
            else -> null
        }

        return Resolution(policy, transcodePreferred, inferredChannelTarget)
    }
}
