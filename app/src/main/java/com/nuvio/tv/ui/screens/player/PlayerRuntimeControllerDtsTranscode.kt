package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.player.AacToDtsRouting
import com.nuvio.tv.core.util.DebugSwitches

/** debug.nuvio.aac_dts_target (D6): a forced target, or null for automatic. */
internal fun aacToDtsTargetOverride(): AacToDtsRouting.Target? =
    AacToDtsRouting.parseTargetOverride(DebugSwitches.property(AacToDtsRouting.TARGET_OVERRIDE_PROPERTY))

/** Whether this stream may get DTS-HD MA (see [AacToDtsRouting.hdAllowed]). */
internal fun PlayerRuntimeController.aacToDtsHdAllowed(url: String, override: AacToDtsRouting.Target?): Boolean =
    AacToDtsRouting.hdAllowed(
        streamSteppedDown = url in aacToDtsHdOffStreamUrls,
        override = override,
        rendererWritesHd = FfmpegRendererDtsHd.writesHd
    )

/** One step down the AAC-to-DTS fallback chain (D5): DTS-HD MA, then the DTS core, then PCM. */
internal enum class AacToDtsStepDown {
    /** DTS-HD MA refused: the stream transcodes to the DTS core. */
    HD_OFF,
    /** DTS core refused: the stream plays the AAC as PCM. */
    ALL_OFF
}

/**
 * The step for a refused AudioTrack of [sinkMime] fed from [sourceMime], or null when it is not
 * a transcode (a real DTS or DTS-HD track is learned as before) or a same-configuration retry is
 * scheduled (a transient refusal is retried with the same target first).
 */
internal fun aacToDtsStepDown(sourceMime: String?, sinkMime: String?, retryScheduled: Boolean = false): AacToDtsStepDown? =
    when {
        retryScheduled || !isDtsTranscode(sourceMime, sinkMime) -> null
        sinkMime == MimeTypes.AUDIO_DTS_HD -> AacToDtsStepDown.HD_OFF
        else -> AacToDtsStepDown.ALL_OFF
    }

/**
 * Steps the stream down after a refused transcode AudioTrack and rebuilds the player at the
 * current position, before the generic recovery that forces PCM for everything. Runs only when
 * no same-configuration retry was scheduled for this error. A step that was taken already falls
 * through to the generic recovery, so the chain cannot loop.
 */
internal fun PlayerRuntimeController.tryAacToDtsStepDown(error: PlaybackException): Boolean {
    if (!error.isAudioTrackFailure()) return false
    val mime = failedAudioTrackInputFormat(error)?.sampleMimeType
    val sourceMime = lastAudioInputMime?.takeIf { it.first === _exoPlayer }?.second
    val step = aacToDtsStepDown(sourceMime, mime) ?: return false
    val url = currentStreamUrl
    val taken = when (step) {
        AacToDtsStepDown.HD_OFF -> aacToDtsHdOffStreamUrls.add(url)
        AacToDtsStepDown.ALL_OFF -> aacToDtsOffStreamUrls.add(url)
    }
    if (!taken) return false
    val player = _exoPlayer ?: return false
    Log.w(PlayerRuntimeController.TAG, "AAC_DTS_STEP_DOWN $step after a refused $mime AudioTrack (source $sourceMime)")
    queuePlaybackRawEventLine("aac_dts_step_down step=$step refused=$mime source=$sourceMime")
    scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
    return true
}

/**
 * Whether the FFmpeg renderer's decoder must be re-created because its output (DTS-HD MA, the
 * DTS core or PCM) no longer matches the target the sink allows now.
 *
 * When FFmpeg comes first it keeps the AAC track in every case. Otherwise MediaCodec takes the
 * track when the target turns to or from PCM, so only a change between DTS-HD MA and the core,
 * where MediaCodec still declines, needs the re-creation.
 */
internal fun dtsTranscodeNeedsDecoderRecreate(
    ffmpegActive: Boolean,
    current: AacToDtsRouting.Target,
    wanted: AacToDtsRouting.Target,
    ffmpegFirst: Boolean
): Boolean = ffmpegActive && current != wanted &&
    (ffmpegFirst || (current != AacToDtsRouting.Target.NONE && wanted != AacToDtsRouting.Target.NONE))

/**
 * Whether the FFmpeg audio renderer comes before MediaCodec, so it keeps an AAC track when DTS
 * is allowed or refused: "Prefer app decoders", Force AC-3 or a stream that prefers FFmpeg audio.
 */
internal fun ffmpegAudioRendererFirst(fingerprint: ExoConstructionFingerprint?): Boolean =
    fingerprint != null && (
        fingerprint.extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER ||
            fingerprint.forceOpticalPassthroughActive ||
            fingerprint.preferFfmpegAudio
        )

/**
 * A renderer that keeps the AAC track keeps its decoder, so its output never follows a change of
 * Bluetooth, speed, passthrough policy or output: FFmpeg first keeps the track in every case, and
 * with MediaCodec first FFmpeg keeps it between DTS-HD MA and the core. Turning the audio track
 * off and on again makes the renderer build a new decoder, which picks the target from the sink's
 * current answer. Main thread.
 */
internal fun PlayerRuntimeController.recreateDtsTranscodeDecoderIfNeeded() {
    val player = _exoPlayer ?: return
    val fingerprint = lastExoConstructionFingerprint ?: return
    if (!fingerprint.transcodeAacToDtsActive) return
    val ffmpegFirst = ffmpegAudioRendererFirst(fingerprint)
    val renderer = ffmpegAudioRenderer ?: return
    val sink = playbackSpeedAwareAudioSink ?: return
    val source = player.audioFormat ?: return
    // The sink is asked on the playback thread, where Media3 uses it; the message runs after the
    // capability change that triggered this check.
    player.createMessage { _, _ ->
        val current = FfmpegRendererDtsHd.target(renderer)
        val wanted = sink.dtsTranscodeTarget(source)
        if (dtsTranscodeNeedsDecoderRecreate(renderer.isAudioPathActive(), current, wanted, ffmpegFirst)) {
            Handler(Looper.getMainLooper()).post { cycleAudioTrack(player, current, wanted) }
        }
    }.setLooper(player.playbackLooper).send()
}

private fun PlayerRuntimeController.cycleAudioTrack(
    player: ExoPlayer,
    current: AacToDtsRouting.Target,
    wanted: AacToDtsRouting.Target
) {
    if (_exoPlayer !== player || afrQuiesce != TrackAfrQuiesce.NONE) return
    if (C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes) return
    Log.i(PlayerRuntimeController.TAG, "DTS_TRANSCODE_REINIT: target=$current -> $wanted")
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .build()
    // A message to the main looper goes through the playback thread's queue, so it runs after the
    // player has acted on the disable above.
    player.createMessage { _, _ ->
        if (_exoPlayer !== player || afrQuiesce != TrackAfrQuiesce.NONE) return@createMessage
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }.setLooper(Looper.getMainLooper()).send()
}
