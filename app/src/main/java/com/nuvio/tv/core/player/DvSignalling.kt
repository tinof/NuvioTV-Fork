package com.nuvio.tv.core.player

import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** What the signalling decision table (ROADMAP DV2.3, `docs/dv-conversion/DESIGN.md` 2.5) decides. */
@UnstableApi
internal sealed interface DvSignallingDecision {
    /** The track plays as it is. */
    data class Native(val reason: String) : DvSignallingDecision

    /**
     * The track is signalled as Dolby Vision [target]: `video/dolby-vision` with [codecs]
     * (`dvhe.08.LL`) and [colorInfo], the base layer's colour from the contract. [untaggedAccepted]
     * names the colour fields neither the container nor the SPS states.
     */
    data class Convert(
        val target: Target,
        val level: Int,
        val codecs: String,
        val colorInfo: ColorInfo,
        val untaggedAccepted: List<String>,
    ) : DvSignallingDecision

    enum class Target(val label: String) {
        /** Profile 8.1 over a PQ (HDR10) base layer. */
        PROFILE_8_1("8.1"),

        /** Profile 8.4 over an HLG base layer. */
        PROFILE_8_4("8.4"),
    }
}

/**
 * The signalling decision table for a Matroska HEVC track that carries no Dolby Vision, at track init,
 * from container metadata and the first SPS only (the extractor publishes the Format before it reads a
 * frame). A track that carries Dolby Vision never comes here: the extractor keeps its existing path.
 * Pure: decides, logs nothing itself.
 */
@UnstableApi
internal object DvSignalling {

    /**
     * Dolby Vision levels 1 to 13: the maximum pixels per second and the maximum width, as FFmpeg's
     * `libavcodec/dovi_rpuenc.c` (`dv_levels`) has them. Index 0 is unused.
     */
    private val LEVEL_MAX_PPS = longArrayOf(
        0L,
        1280L * 720 * 24, 1280L * 720 * 30, 1920L * 1080 * 24, 1920L * 1080 * 30, 1920L * 1080 * 60,
        3840L * 2160 * 24, 3840L * 2160 * 30, 3840L * 2160 * 48, 3840L * 2160 * 60, 3840L * 2160 * 120,
        3840L * 2160 * 120, 7680L * 4320 * 60, 7680L * 4320 * 120,
    )
    private val LEVEL_MAX_WIDTH = intArrayOf(0, 1280, 1280, 1920, 2560, 3840, 3840, 3840, 3840, 3840, 3840, 7680, 7680, 7680)

    /** The rate assumed without a DefaultDuration (ROADMAP DV2.3). */
    const val FALLBACK_FRAME_RATE = 60.0

    // DefaultDuration is whole nanoseconds, so 30 fps is coded as 33333333 ns, a hair above 30. A
    // relative slack this small never moves a stream across a level (the steps are 20 % or more).
    private const val PPS_SLACK = 1e-4

    @JvmStatic
    fun decide(
        encrypted: Boolean,
        container: ContainerColour,
        vui: HevcVuiColour?,
        hlg: HlgDecision,
        width: Int,
        height: Int,
        defaultSampleDurationNs: Long,
    ): DvSignallingDecision {
        if (encrypted) return DvSignallingDecision.Native("protected content")
        val target: DvSignallingDecision.Target
        val untagged: List<String>
        val colorInfo: ColorInfo
        when (hlg) {
            is HlgDecision.Hlg84 -> {
                target = DvSignallingDecision.Target.PROFILE_8_4
                untagged = hlg.untaggedAccepted
                colorInfo = hlg.baseLayerColorInfo
            }
            is HlgDecision.Refused -> return DvSignallingDecision.Native("HLG refused: ${hlg.reason}")
            // HLG signalled only in an alternative-transfer SEI lands here: native (DESIGN.md 2.5).
            HlgDecision.TransferUnknown -> return DvSignallingDecision.Native("transfer unknown at track init")
            HlgDecision.NotHlg -> when (val pq = HlgContract.resolvePq(container, vui)) {
                is PqDecision.Pq81 -> {
                    target = DvSignallingDecision.Target.PROFILE_8_1
                    untagged = pq.untaggedAccepted
                    colorInfo = pq.baseLayerColorInfo
                }
                is PqDecision.Refused -> return DvSignallingDecision.Native("PQ refused: ${pq.reason}")
                PqDecision.NotPq -> return DvSignallingDecision.Native("transfer neither PQ nor HLG")
                PqDecision.TransferUnknown -> return DvSignallingDecision.Native("transfer unknown at track init")
            }
        }
        val frameRate = levelFrameRate(defaultSampleDurationNs)
        val level = level(width, height, frameRate)
            ?: return DvSignallingDecision.Native(
                "no Dolby Vision level for ${width}x$height at ${String.format(Locale.ROOT, "%.3f", frameRate)} fps"
            )
        return DvSignallingDecision.Convert(
            target = target,
            level = level,
            codecs = String.format(Locale.ROOT, "dvhe.08.%02d", level),
            colorInfo = colorInfo,
            untaggedAccepted = untagged,
        )
    }

    /**
     * The rate the level is taken at: the one any positive DefaultDuration declares, even outside the
     * bounds of `Format.frameRate`, or [FALLBACK_FRAME_RATE] without a DefaultDuration.
     */
    @JvmStatic
    fun levelFrameRate(defaultSampleDurationNs: Long): Double =
        if (defaultSampleDurationNs > 0) 1_000_000_000.0 / defaultSampleDurationNs else FALLBACK_FRAME_RATE

    /**
     * The frame rate a Matroska DefaultDuration declares, with the bounds the extractor uses for
     * `Format.frameRate` (5 to 121 fps), or null without a usable value.
     */
    @JvmStatic
    fun declaredFrameRate(defaultSampleDurationNs: Long): Double? {
        if (defaultSampleDurationNs <= 0) return null
        val rate = 1_000_000_000.0 / defaultSampleDurationNs
        return rate.takeIf { it in 5.0..121.0 }
    }

    /**
     * The lowest Dolby Vision level whose pixel rate and width hold [width] x [height] at [frameRate],
     * as FFmpeg's `dovi_configure_ext` picks it, or null for an unknown frame size or a stream above
     * level 13 (native playback: no level describes it; FFmpeg would write 13 anyway).
     */
    @JvmStatic
    fun level(width: Int, height: Int, frameRate: Double): Int? {
        if (width <= 0 || height <= 0 || frameRate <= 0.0) return null
        val pps = width.toDouble() * height * frameRate
        for (level in 1 until LEVEL_MAX_PPS.size) {
            if (pps > LEVEL_MAX_PPS[level] * (1 + PPS_SLACK)) continue
            if (width > LEVEL_MAX_WIDTH[level]) continue
            return level
        }
        return null
    }

    /** The one log line for a track's decision. */
    @JvmStatic
    fun logLine(trackNumber: Int, decision: DvSignallingDecision): String {
        val what = when (decision) {
            is DvSignallingDecision.Native -> "native, ${decision.reason}"
            is DvSignallingDecision.Convert -> {
                val untagged = if (decision.untaggedAccepted.isEmpty()) {
                    ""
                } else {
                    ", untagged accepted: ${decision.untaggedAccepted.joinToString()}"
                }
                "Dolby Vision ${decision.target.label} as ${decision.codecs}$untagged"
            }
        }
        return "DV signalling, track $trackNumber: $what"
    }
}
