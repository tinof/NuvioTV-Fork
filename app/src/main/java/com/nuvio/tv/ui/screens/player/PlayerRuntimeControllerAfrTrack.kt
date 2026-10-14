@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.nuvio.tv.core.performance.DeviceProfile
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.core.util.DebugSwitches
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Absolute ceiling on the whole track-AFR step (mode switch wait + settle
// hold). Timer-released, never event-gated, so it cannot deadlock startup.
// 8 s covers the worst case (~4 s mode-switch wait + 2 s settle hold)
// with margin; 7 s could clip a slow HDMI mode change mid-settle.
private const val TRACK_AFR_ABSOLUTE_DEADLINE_MS = 8_000L

// Settle hold after a real display-mode switch, mirroring
// AFR_EXO_SETTLE_HOLD_MS on the preflight path: don't start the A/V pipeline
// inside the mode transition.
private const val TRACK_AFR_SETTLE_HOLD_MS = 2_000L

// Shorter hold on Nvidia boxes, so the Shield starts sooner after a switch.
private const val TRACK_AFR_SETTLE_HOLD_NVIDIA_MS = 1_200L

// Ceiling for the debug.nuvio.afr_settle_ms test switch: the absolute
// deadline above must still cover the mode-switch wait before the hold.
private const val TRACK_AFR_SETTLE_HOLD_MAX_MS = 4_000L

/**
 * Settle hold after a real switch: 1.2 s on Nvidia, 2 s elsewhere.
 * `adb shell setprop debug.nuvio.afr_settle_ms <ms>` overrides it for A/B tests.
 */
internal fun trackAfrSettleHoldMs(overrideMs: Int?, manufacturer: String? = Build.MANUFACTURER): Long =
    overrideMs?.toLong()?.coerceIn(0L, TRACK_AFR_SETTLE_HOLD_MAX_MS)
        ?: if (DeviceProfile.isNvidia(manufacturer)) TRACK_AFR_SETTLE_HOLD_NVIDIA_MS else TRACK_AFR_SETTLE_HOLD_MS

/** What the track-AFR step takes down across the display-mode switch. */
internal enum class TrackAfrQuiesce {
    NONE,
    /** Audio only: the live AudioTrack is released before HDMI tears the output down. */
    AUDIO,
    /** Audio and video in one step: both renderers leave the tunnel and rejoin it together. */
    AUDIO_AND_VIDEO
}

/**
 * What to quiesce across a track-AFR display-mode switch. Without tunnelling, audio alone.
 * Under tunnelling, audio alone would move the video renderer out of the tunnel and back, so
 * nothing is touched, except a TrueHD / DTS-HD track on Nvidia, where audio and video go down
 * together (debug.nuvio.afr_quiesce_av=1/0 forces that either way). A stream already playing
 * without audio is never touched.
 */
internal fun trackAfrQuiesce(
    audioDisabled: Boolean,
    tunnelingApplied: Boolean,
    losslessAudio: Boolean,
    avSwitch: Boolean?,
    manufacturer: String? = Build.MANUFACTURER
): TrackAfrQuiesce = when {
    audioDisabled -> TrackAfrQuiesce.NONE
    !tunnelingApplied -> TrackAfrQuiesce.AUDIO
    losslessAudio && (avSwitch ?: DeviceProfile.isNvidia(manufacturer)) -> TrackAfrQuiesce.AUDIO_AND_VIDEO
    else -> TrackAfrQuiesce.NONE
}

/** Sample MIME type of the selected audio track, or null. */
internal fun selectedAudioMimeType(tracks: Tracks): String? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) return group.getTrackFormat(i).sampleMimeType
        }
    }
    return null
}

/**
 * True while track-AFR has audio and video both off. Track and READY callbacks in that
 * window see a player without its A/V renderers and must not act on it.
 */
internal val PlayerRuntimeController.afrAvQuiesceInFlight: Boolean
    get() = afrQuiesce == TrackAfrQuiesce.AUDIO_AND_VIDEO

/** Forgets the quiesce without touching the player (its track parameters are being rebuilt). */
internal fun PlayerRuntimeController.dropTrackAfrQuiesce() {
    afrQuiesce = TrackAfrQuiesce.NONE
    afrQuiescePlayer = null
    afrQuiesceGeneration = -1
}

/**
 * Re-enables what the track-AFR quiesce disabled, on the player it was disabled on; with
 * [generation], only when that stream's coroutine set it. After an audio-and-video quiesce,
 * [reseek] seeks to the current position: the renderers rejoin from a clean position and the
 * player passes through BUFFERING to a READY that the start path can act on. Main thread.
 */
internal fun PlayerRuntimeController.endTrackAfrQuiesce(generation: Int? = null, reseek: Boolean = true) {
    val mode = afrQuiesce
    if (mode == TrackAfrQuiesce.NONE) return
    if (generation != null && generation != afrQuiesceGeneration) return
    val player = afrQuiescePlayer
    if (player == null || _exoPlayer !== player) {
        dropTrackAfrQuiesce()
        return
    }
    val builder = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
    if (mode == TrackAfrQuiesce.AUDIO_AND_VIDEO) builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
    player.trackSelectionParameters = builder.build()
    // The seek's callbacks run inside seekTo, while isTunnelingEnabled still reads the
    // both-off selection; the quiesce stays marked until it returns so they stand down.
    if (mode == TrackAfrQuiesce.AUDIO_AND_VIDEO && reseek) player.seekTo(player.currentPosition)
    dropTrackAfrQuiesce()
}

/**
 * Frame-rate matching from ExoPlayer's reported track format instead of a
 * network probe. Called from the tracks-changed handler as soon as the selected
 * video format's frameRate is known, before the first frame renders.
 *
 * The player is built with playWhenReady true, so arming the gate also pauses
 * it for the switch window. While a switch is in flight the start sites stand
 * down (afrTrackSwitchInFlight); when it settles,
 * resumePlaybackAfterTrackAfrIfHeld() starts playback if the first frame
 * arrived during the hold. The finally block releases the gate at the deadline.
 */
internal fun PlayerRuntimeController.maybeRunTrackFormatAfr(rawFps: Float, format: Format) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (currentInternalPlayerEngine != InternalPlayerEngine.EXOPLAYER) return
    if (trackAfrAttemptedForCurrentStream) return
    if (afrModeAppliedPreStart) {
        // Validate and correct: if the applied mode came from a provisional
        // head seed, check ExoPlayer's real reported rate against it. Match ->
        // promote the seed to the persistent cache so the next play is a real
        // hit even unwarmed. Mismatch -> clear the pre-start flags and fall
        // through into the switch body below to correct the panel (the clean
        // pre-prepare switch to the wrong rate is discarded; the correction is
        // the normal miss-path switch). rawFps <= 0 / < 20 is unvalidatable: keep
        // the seed rather than tear down a plausibly-correct mode.
        val seeded = afrSeededRateRaw
        if (seeded <= 0f) return
        if (rawFps < 20f) {
            Log.w(PlayerRuntimeController.TAG, "AFR seed: cannot validate (track rawFps=$rawFps); keeping seeded mode")
            return
        }
        val snappedSeed = FrameRateUtils.snapToStandardRate(seeded)
        val snappedTrack = FrameRateUtils.snapToStandardRate(rawFps)
        if (snappedSeed == snappedTrack) {
            Log.i(PlayerRuntimeController.TAG, "AFR seed validated: seeded=$seeded track=$rawFps (promoting to cache)")
            FrameRateUtils.cacheFrameRate(
                currentStreamUrl,
                currentHeaders,
                FrameRateUtils.FrameRateDetection(
                    raw = rawFps,
                    snapped = snappedTrack,
                    videoWidth = format.width.takeIf { it > 0 },
                    videoHeight = format.height.takeIf { it > 0 }
                ),
                currentFilename
            )
            return
        }
        Log.w(
            PlayerRuntimeController.TAG,
            "AFR seed MISMATCH: seeded=$seeded ($snappedSeed) track=$rawFps ($snappedTrack); correcting via track-format switch"
        )
        afrModeAppliedPreStart = false
        afrSeededRateRaw = 0f
    }
    if (rawFps <= 0f) return
    // Mirror of the FrameRateUtils floor, applied before the cache write below so a
    // bogus rate from a broken source is never persisted for the next play.
    if (rawFps < 20f) {
        Log.w(PlayerRuntimeController.TAG, "Track AFR: ignoring implausible frame rate ${rawFps}fps")
        trackAfrAttemptedForCurrentStream = true
        return
    }
    if (_uiState.value.frameRateMatchingMode == FrameRateMatchingMode.OFF) return
    if (_uiState.value.afrProbeRunning) return
    // Too late: playback is already running. A mid-playback mode switch is the
    // known black-screen/stutter risk this design avoids; skip rather than
    // switch under a live pipeline.
    if (hasRenderedFirstFrame && _exoPlayer?.isPlaying == true) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Track AFR: playback already running, skipping display-mode switch (fps=$rawFps)"
        )
        trackAfrAttemptedForCurrentStream = true
        return
    }
    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "Track AFR skipped: host activity unavailable")
        return
    }

    trackAfrAttemptedForCurrentStream = true
    afrTrackSwitchInFlight = true
    // Engage the gate for real: playWhenReady is true from player build on
    // the normal path, so without this the player auto-starts at STATE_READY
    // and the stand-down at the start sites gates nothing. The guards above
    // ensure playback is not already running; setting an already-false value
    // is a no-op on the paused/startPaused flows.
    _exoPlayer?.playWhenReady = false
    // Stamp this attempt with the current stream's generation; the
    // finally block stands down if a per-stream reset happened meanwhile.
    val startGeneration = afrTrackGeneration
    // What was disabled for the switch, for the settle log. afrQuiesce holds
    // the same for the finally, which re-enables exactly that and can never
    // latch a muted state on an unrelated exit path.
    var quiesced = TrackAfrQuiesce.NONE
    var displayModeSwitched = false
    var switchStartElapsedMs = 0L

    scope.launch {
        try {
            withTimeoutOrNull(TRACK_AFR_ABSOLUTE_DEADLINE_MS) {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                val snapped = FrameRateUtils.snapToStandardRate(rawFps)
                val prefer23976TrackBias = rawFps in 23.95f..23.999f
                val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
                    activity = activity,
                    detectedFps = snapped,
                    prefer23976Near24 = prefer23976TrackBias
                )
                val initialDisplayModeId = withContext(Dispatchers.Main) {
                    activity.window?.decorView?.display?.mode?.modeId
                }

                Log.d(
                    PlayerRuntimeController.TAG,
                    "Track AFR: raw=$rawFps snapped=$snapped target=$targetFrameRate " +
                        "size=${format.width}x${format.height}"
                )

                // The ExoPlayer preflight only reads the frame-rate cache, so
                // without this write the cache is never populated on that
                // engine and every play pays the full post-prepare mode switch
                // and settle.
                //
                // Writing here costs nothing on this play and lets the NEXT
                // play of the same title take the preflight's hit path, where
                // the switch happens before prepare and the settle overlaps
                // loading instead of following it. Dimensions come from the
                // same format used for the switch below, so a later hit
                // preflights with the size that actually applied.
                FrameRateUtils.cacheFrameRate(
                    currentStreamUrl,
                    currentHeaders,
                    FrameRateUtils.FrameRateDetection(
                        raw = rawFps,
                        snapped = snapped,
                        videoWidth = format.width.takeIf { it > 0 },
                        videoHeight = format.height.takeIf { it > 0 }
                    ),
                    currentFilename
                )

                // Quiesce audio across the HDMI mode switch. On the
                // Shield, switching display mode tears the audio output down
                // under a live AudioTrack, producing AudioSink write failures
                // (-6) that media3 recovers from but which are the fragile
                // window for passthrough corruption. Releasing the AudioTrack
                // before the switch removes the live-track teardown entirely.
                // Skip under an already-audio-disabled stream (don't fight the
                // existing state machine) and under tunneling (the tunnel
                // clock is the audio track). The player's applied state, not the
                // preference: track selection can turn tunneling off for a stream.
                // Tunnelled TrueHD / DTS-HD on Nvidia takes audio and video down
                // together instead (trackAfrQuiesce).
                quiesced = withContext(Dispatchers.Main) {
                    val p = _exoPlayer ?: return@withContext TrackAfrQuiesce.NONE
                    val mode = trackAfrQuiesce(
                        audioDisabled = isAudioDisabledForCurrentPlayback,
                        tunnelingApplied = p.isTunnelingEnabled,
                        losslessAudio = PassthroughWaterLevelPacer.isHbrMime(selectedAudioMimeType(p.currentTracks)),
                        avSwitch = DebugSwitches.flag("debug.nuvio.afr_quiesce_av")
                    )
                    if (mode == TrackAfrQuiesce.NONE || afrTrackGeneration != startGeneration) {
                        return@withContext TrackAfrQuiesce.NONE
                    }
                    afrQuiesce = mode
                    afrQuiescePlayer = p
                    afrQuiesceGeneration = startGeneration
                    val builder = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    if (mode == TrackAfrQuiesce.AUDIO_AND_VIDEO) builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    p.trackSelectionParameters = builder.build()
                    mode
                }
                if (quiesced != TrackAfrQuiesce.NONE) {
                    switchStartElapsedMs = android.os.SystemClock.elapsedRealtime()
                }

                val switchRequestedAtMs = android.os.SystemClock.elapsedRealtime()
                val result = FrameRateUtils.matchFrameRateAndWait(
                    activity = activity,
                    frameRate = targetFrameRate,
                    videoWidth = format.width.takeIf { it > 0 },
                    videoHeight = format.height.takeIf { it > 0 },
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                )

                if (result != null) {
                    val switchedDisplayMode = initialDisplayModeId != null &&
                        initialDisplayModeId != result.appliedMode.modeId
                    _uiState.update {
                        it.copy(
                            displayModeInfo = DisplayModeInfo(
                                width = result.appliedMode.physicalWidth,
                                height = result.appliedMode.physicalHeight,
                                refreshRate = result.appliedMode.refreshRate
                            ),
                            showDisplayModeInfo = true
                        )
                    }
                    if (switchedDisplayMode) {
                        displayModeSwitched = true
                        val switchToStableMs = android.os.SystemClock.elapsedRealtime() - switchRequestedAtMs
                        val settleHoldMs = trackAfrSettleHoldMs(DebugSwitches.int("debug.nuvio.afr_settle_ms"))
                        Log.d(
                            PlayerRuntimeController.TAG,
                            "Track AFR: display mode switched; switchToStableMs=$switchToStableMs " +
                                "holding playback start ${settleHoldMs}ms"
                        )
                        delay(settleHoldMs)
                        val sinceSwitchMs = if (switchStartElapsedMs > 0L) {
                            android.os.SystemClock.elapsedRealtime() - switchStartElapsedMs
                        } else {
                            -1L
                        }
                        Log.d(
                            PlayerRuntimeController.TAG,
                            "Track AFR settle complete: elapsedSinceSwitchMs=" + sinceSwitchMs +
                                " quiesced=" + quiesced +
                                " firstFrame=" + hasRenderedFirstFrame
                        )
                    }
                }
            } ?: Log.w(
                PlayerRuntimeController.TAG,
                "Track AFR exceeded absolute deadline (${TRACK_AFR_ABSOLUTE_DEADLINE_MS}ms); releasing start gate"
            )
        } finally {
            // Act only if this coroutine still belongs to the current
            // stream: an old stream's finally otherwise collapses the new
            // stream's start-hold (and could start held playback early).
            if (afrTrackGeneration == startGeneration) {
                afrTrackSwitchInFlight = false
                if (displayModeSwitched) noteOutputChangeForTunnelWatch()
                // Symmetric re-enable. Only touch the tracks we disabled
                // above (afrQuiesce, set together with the disable, so a
                // deadline between the two cannot leave them off); runs on every
                // exit path (success, deadline, reset). A per-stream reset
                // already dropped or ended the quiesce.
                withContext(Dispatchers.Main) { endTrackAfrQuiesce(startGeneration) }
                resumePlaybackAfterTrackAfrIfHeld()
            }
        }
    }
}

/**
 * If the first frame rendered (or tunneled READY fired) while the track-AFR
 * gate held playback paused, start playback now with the same guards the
 * normal start sites use. If the frame hasn't arrived yet, do nothing: the
 * normal onRenderedFirstFrame / tunneled READY path starts playback, its gate
 * now open.
 */
internal fun PlayerRuntimeController.resumePlaybackAfterTrackAfrIfHeld() {
    val player = _exoPlayer ?: return
    if (!hasRenderedFirstFrame) return
    if (userPausedManually) return
    if (isReleasingPlayer) return
    if (player.playWhenReady) return
    Log.d(PlayerRuntimeController.TAG, "Track AFR: settle complete; starting held playback")
    player.playWhenReady = true
    player.play()
}
