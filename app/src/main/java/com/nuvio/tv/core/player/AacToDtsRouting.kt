package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * Which tracks the "Transcode AAC to DTS" setting sends to the FFmpeg renderer, and the DTS
 * formats it then asks the sink for. The FFmpeg renderer writes DTS-HD Master Audio (the DTS core
 * plus a lossless XLL) when the output plays DTS-HD, else the DTS core, both at 48 kHz; mono is
 * carried as a 2-channel AudioTrack (dual mono for DTS-HD MA).
 */
object AacToDtsRouting {

    const val OUTPUT_SAMPLE_RATE = 48_000
    const val OUTPUT_CHANNEL_COUNT = 2

    /**
     * Format.id of the DTS-HD transcode format. Must equal FfmpegAudioRenderer
     * .DTS_HD_TRANSCODE_FORMAT_ID in ffmpeg-decoder-downmix (the app may run with an older AAR
     * that does not have the constant, so it is not referenced).
     */
    const val DTS_HD_TRANSCODE_FORMAT_ID = "nuvio:aac-dts-hd-transcode"

    /** debug.nuvio.aac_dts_target: hd or core forces the target for testing (D6). */
    const val TARGET_OVERRIDE_PROPERTY = "debug.nuvio.aac_dts_target"

    // xHE-AAC (USAC). FFmpeg 7.0 has no decoder for it.
    private const val XHE_AAC_CODECS_PREFIX = "mp4a.40.42"

    val DTS_OUTPUT_FORMAT: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS)
        .setChannelCount(OUTPUT_CHANNEL_COUNT)
        .setSampleRate(OUTPUT_SAMPLE_RATE)
        .build()

    /** The DTS-HD MA transcode's sink format, marked so the sink answers for DTS-HD itself. */
    val DTS_HD_OUTPUT_FORMAT: Format = Format.Builder()
        .setId(DTS_HD_TRANSCODE_FORMAT_ID)
        .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
        .setChannelCount(OUTPUT_CHANNEL_COUNT)
        .setSampleRate(OUTPUT_SAMPLE_RATE)
        .build()

    /** The transcode target; the ordinals match DtsTranscodeTargets NONE, CORE, HD in the module. */
    enum class Target { NONE, CORE, HD }

    /** Mono or stereo AAC that the FFmpeg decoder can open. */
    fun isEligibleSource(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_AAC &&
            format.channelCount in 1..2 &&
            format.cryptoType == C.CRYPTO_TYPE_NONE &&
            format.codecs?.startsWith(XHE_AAC_CODECS_PREFIX) != true

    /** Whether [format] is the DTS-HD transcode's own query, not a real DTS-HD track. */
    fun isDtsHdTranscodeOutput(format: Format): Boolean =
        format.id == DTS_HD_TRANSCODE_FORMAT_ID && format.sampleMimeType == MimeTypes.AUDIO_DTS_HD

    /** The forced target of the debug switch: hd, core, or null (automatic). */
    fun parseTargetOverride(value: String?): Target? = when (value?.trim()?.lowercase()) {
        "hd" -> Target.HD
        "core" -> Target.CORE
        else -> null
    }

    /**
     * Whether this player may write DTS-HD MA: the stream has not stepped down from it (D5), the
     * debug switch does not force the core, and the FFmpeg renderer in use can write it (the AAR
     * in app/libs may predate DTS-HD MA).
     */
    fun hdAllowed(streamSteppedDown: Boolean, override: Target?, rendererWritesHd: Boolean): Boolean =
        !streamSteppedDown && override != Target.CORE && rendererWritesHd

    /**
     * Picks the target. Same rule as DtsTranscodeTargets.choose in the FFmpeg module, which asks
     * the same sink, so both renderers agree: DTS-HD MA when it is allowed and DTS-HD plays as
     * itself, else the core when DTS plays, else PCM. A forced HD skips the DTS-HD check but
     * still needs DTS and an allowed HD.
     */
    fun chooseTarget(
        enabled: Boolean,
        eligible: Boolean,
        hdAllowed: Boolean,
        forced: Target?,
        hdDirect: () -> Boolean,
        coreDirect: () -> Boolean
    ): Target {
        if (!enabled || !eligible) return Target.NONE
        val hdPossible = hdAllowed && forced != Target.CORE
        if (hdPossible && (if (forced == Target.HD) coreDirect() || hdDirect() else hdDirect())) {
            return Target.HD
        }
        return if (coreDirect()) Target.CORE else Target.NONE
    }

    /** The sink format a target writes, or null for PCM. */
    fun outputFormatOf(target: Target): Format? = when (target) {
        Target.HD -> DTS_HD_OUTPUT_FORMAT
        Target.CORE -> DTS_OUTPUT_FORMAT
        Target.NONE -> null
    }
}
