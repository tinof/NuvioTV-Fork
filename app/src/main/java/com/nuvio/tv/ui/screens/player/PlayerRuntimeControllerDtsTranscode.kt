package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

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
 * Whether the FFmpeg renderer's decoder must be re-created because its output (DTS or PCM) no
 * longer matches what the sink allows for the AAC-to-DTS transcode.
 */
internal fun dtsTranscodeNeedsDecoderRecreate(
    ffmpegActive: Boolean,
    transcodingDts: Boolean,
    wantsDts: Boolean
): Boolean = ffmpegActive && transcodingDts != wantsDts

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
 * When the FFmpeg renderer comes first, it stays selected for AAC when Bluetooth, the speed or the
 * passthrough policy changes, so Media3 keeps its decoder and the output never switches between
 * DTS and PCM. Turning the audio track off and on again makes the renderer build a new decoder,
 * which picks the output from the sink's current answer. Otherwise MediaCodec takes the track
 * instead, so nothing is needed. Main thread.
 */
internal fun PlayerRuntimeController.recreateDtsTranscodeDecoderIfNeeded() {
    val player = _exoPlayer ?: return
    if (!lastExoConstructionFingerprint.let { it?.transcodeAacToDtsActive == true && ffmpegAudioRendererFirst(it) }) {
        return
    }
    val renderer = ffmpegAudioRenderer ?: return
    val sink = playbackSpeedAwareAudioSink ?: return
    val source = player.audioFormat ?: return
    // The sink is asked on the playback thread, where Media3 uses it; the message runs after the
    // capability change that triggered this check.
    player.createMessage { _, _ ->
        val transcodingDts = renderer.isTranscodingToDts()
        val wantsDts = sink.prefersDtsTranscode(source)
        if (dtsTranscodeNeedsDecoderRecreate(renderer.isAudioPathActive(), transcodingDts, wantsDts)) {
            Handler(Looper.getMainLooper()).post { cycleAudioTrack(player, transcodingDts, wantsDts) }
        }
    }.setLooper(player.playbackLooper).send()
}

private fun PlayerRuntimeController.cycleAudioTrack(player: ExoPlayer, transcodingDts: Boolean, wantsDts: Boolean) {
    if (_exoPlayer !== player || afrQuiesce != TrackAfrQuiesce.NONE) return
    if (C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes) return
    Log.i(PlayerRuntimeController.TAG, "DTS_TRANSCODE_REINIT: dts=$transcodingDts -> $wantsDts")
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
