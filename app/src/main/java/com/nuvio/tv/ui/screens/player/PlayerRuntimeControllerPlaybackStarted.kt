package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import com.nuvio.tv.core.util.TtffTrace

// Fork-owned wiring for PlaybackStartedStage, so each upstream site needs one statement.

/** Call after the ExoPlayer first-frame diagnostics (normal and tunneled). */
internal fun PlayerRuntimeController.onPlaybackStartedFirstFrame() {
    playbackStartedStage.onFirstFrame(
        nowMs = SystemClock.elapsedRealtime(),
        clickAtMs = launchStartedAtElapsedMs,
        afrTrackHeld = afrTrackSwitchInFlight,
        pausedAtFirstFrame = userPausedManually
    )?.let { emitPlaybackStartedLine(it) }
}

/** Call from ExoPlayer's onIsPlayingChanged. */
internal fun PlayerRuntimeController.onPlaybackStartedIsPlaying(isPlaying: Boolean) {
    playbackStartedStage.onIsPlaying(
        nowMs = SystemClock.elapsedRealtime(),
        clickAtMs = launchStartedAtElapsedMs,
        isPlaying = isPlaying
    )?.let { emitPlaybackStartedLine(it) }
}

private fun PlayerRuntimeController.emitPlaybackStartedLine(line: String) {
    playbackAnalyticsDiagnostics.recordRawEventLine(line)
    TtffTrace.mirror(line)
}
