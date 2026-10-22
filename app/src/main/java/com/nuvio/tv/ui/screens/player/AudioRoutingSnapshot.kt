package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.ui.screens.player.iec.HbrPayload

internal enum class AudioRoutingMode {
    PASSTHROUGH_IEC,
    PASSTHROUGH_DIRECT,
    TRANSCODE_AC3,
    TRANSCODE_DTS,
    PCM
}

internal data class AudioRoutingSnapshot(
    val mode: AudioRoutingMode,
    val outputFormat: String,
    val isFallback: Boolean
)

internal fun isSurroundOrHbrMime(mime: String?, channelCount: Int): Boolean {
    if (mime == null) return false
    return when (mime) {
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_X,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_AC4 -> true
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_EXPRESS -> channelCount > 2
        else -> channelCount > 2
    }
}

internal fun resolveAudioRoutingSnapshot(
    sourceMime: String?,
    sourceChannelCount: Int,
    isIecActive: Boolean,
    isTranscodingAc3: Boolean,
    isAudioPathActive: Boolean,
    sinkMime: String?,
    sinkChannelCount: Int,
    isTunnelled: Boolean = false,
    iecPayload: HbrPayload? = null,
    isTranscodingDts: Boolean = false
): AudioRoutingSnapshot? {
    if (sourceMime == null) return null
    val isSourceSurroundOrHbr = isSurroundOrHbrMime(sourceMime, sourceChannelCount)

    val snapshot = when {
        // Chosen by the "Transcode AAC to DTS" setting, so not a fallback. Checked before the
        // own IEC engine, which also carries a DTS-HD MA transcode: that is not passthrough.
        isTranscodingDts -> {
            val target = if (sinkMime == MimeTypes.AUDIO_DTS_HD) "transcode DTS-HD MA" else "transcode DTS"
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.TRANSCODE_DTS,
                outputFormat = if (isIecActive) "$target (IEC)" else target,
                isFallback = false
            )
        }
        isIecActive -> {
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.PASSTHROUGH_IEC,
                outputFormat = when (iecPayload) {
                    HbrPayload.IEC_BURST -> "passthrough · IEC"
                    HbrPayload.MAT -> "passthrough · MAT"
                    null -> "passthrough (IEC)"
                },
                isFallback = false
            )
        }
        isTranscodingAc3 -> {
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.TRANSCODE_AC3,
                outputFormat = "transcode AC-3 5.1",
                isFallback = true
            )
        }
        sinkMime != null && sinkMime != MimeTypes.AUDIO_RAW && PassthroughWaterLevelPacer.isPassthroughMime(sinkMime) -> {
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.PASSTHROUGH_DIRECT,
                outputFormat = "passthrough (direct)",
                isFallback = false
            )
        }
        sinkMime == MimeTypes.AUDIO_RAW || sinkMime != null || isAudioPathActive -> {
            val pcmChannels = sinkChannelCount
            val channelText = when (pcmChannels) {
                1 -> "mono"
                2 -> "2.0"
                6 -> "5.1"
                8 -> "7.1"
                in 3..Int.MAX_VALUE -> "$pcmChannels ch"
                else -> ""
            }
            val baseLabel = if (channelText.isNotEmpty()) "PCM $channelText" else "PCM"
            val isFallback = if (pcmChannels in 1 until sourceChannelCount) {
                true
            } else {
                isSourceSurroundOrHbr && pcmChannels <= 2
            }
            val displayLabel = if (isFallback) "$baseLabel (fallback)" else baseLabel
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.PCM,
                outputFormat = displayLabel,
                isFallback = isFallback
            )
        }
        else -> null
    }
    if (!isTunnelled || snapshot == null || snapshot.mode == AudioRoutingMode.PASSTHROUGH_IEC) return snapshot
    return snapshot.copy(outputFormat = withTunnelNote(snapshot.outputFormat))
}

internal fun withTunnelNote(label: String): String =
    if (label.endsWith(")")) "${label.dropLast(1)}, tunnelled)" else "$label (tunnelled)"

internal fun PlayerRuntimeController.getAudioRoutingSnapshot(isTunnelled: Boolean = false): AudioRoutingSnapshot? {
    val player = _exoPlayer ?: return null
    val sourceFormat = player.audioFormat ?: return null
    val sourceMime = sourceFormat.sampleMimeType ?: return null

    val sink = playbackSpeedAwareAudioSink
    val sinkFormat = sink?.activeInputFormat
    val ffmpeg = ffmpegAudioRenderer

    val isTranscodingAc3 = ffmpeg?.isTranscodingToAc3() == true ||
        (sourceMime != MimeTypes.AUDIO_AC3 && sinkFormat?.sampleMimeType == MimeTypes.AUDIO_AC3)
    val isTranscodingDts = isDtsTranscode(sourceMime, sinkFormat?.sampleMimeType)

    return resolveAudioRoutingSnapshot(
        sourceMime = sourceMime,
        sourceChannelCount = sourceFormat.channelCount,
        isIecActive = sink?.isIecHbrActive() == true,
        isTranscodingAc3 = isTranscodingAc3,
        isAudioPathActive = ffmpeg?.isAudioPathActive() == true,
        sinkMime = sinkFormat?.sampleMimeType,
        sinkChannelCount = sinkFormat?.channelCount ?: -1,
        isTunnelled = isTunnelled,
        iecPayload = sink?.iecHbrPayload(),
        isTranscodingDts = isTranscodingDts
    )
}

/**
 * The Audio path row's name for a transcode: the sink opened [encoding] for a source of another
 * codec. Null when it is not a transcode.
 */
internal fun transcodeSinkPathLabel(encoding: Int, sourceMime: String?): String? = when {
    sourceMime == null -> null
    encoding == androidx.media3.common.C.ENCODING_AC3 && sourceMime != MimeTypes.AUDIO_AC3 -> "AC-3 transcode"
    encoding == androidx.media3.common.C.ENCODING_DTS_HD && isDtsTranscode(sourceMime, MimeTypes.AUDIO_DTS_HD) ->
        "DTS-HD MA transcode"
    encoding == androidx.media3.common.C.ENCODING_DTS && isDtsTranscode(sourceMime, MimeTypes.AUDIO_DTS) ->
        "DTS transcode"
    else -> null
}

/**
 * A DTS or DTS-HD sink format for a source that is not DTS: the FFmpeg AAC-to-DTS transcode. The
 * prefix test keeps every real DTS source out (DTS, DTS-HD, DTS Express, DTS:X).
 */
internal fun isDtsTranscode(sourceMime: String?, sinkMime: String?): Boolean =
    sourceMime != null &&
        (sinkMime == MimeTypes.AUDIO_DTS || sinkMime == MimeTypes.AUDIO_DTS_HD) &&
        !sourceMime.startsWith(MimeTypes.AUDIO_DTS)
