package com.nuvio.tv.ui.screens.player

/**
 * The PLAYBACK_STARTED stage: when ExoPlayer first plays after the first frame of a
 * playback session, measured from the click. The first frame is not the start: a
 * track-AFR switch or the settle hold keeps playback paused after it, and the viewer
 * waits through that hold (ROADMAP M1.1).
 *
 * Pure: every time is a parameter (SystemClock.elapsedRealtime() terms), so the JVM
 * tests see real values. Main thread only, like the player listener that drives it.
 *
 * [onIsPlaying] may arrive before [onFirstFrame] (ExoPlayer can report isPlaying inside
 * the play() call that the first frame triggers, and the watchdog or an in-place switch
 * plays before the frame); the stage then emits at the first frame with a gap of 0.
 */
internal class PlaybackStartedStage {
    private var firstFrameAtMs: Long? = null
    private var pendingPlayingAtMs: Long? = null
    private var emitted = false
    private var afrTrackHeld = false
    private var pausedAtFirstFrame = false
    private var seq = 0

    /** A new playback session (new stream, failover, episode switch). The click is kept until the first emit. */
    fun arm() {
        firstFrameAtMs = null
        pendingPlayingAtMs = null
        emitted = false
        afrTrackHeld = false
        pausedAtFirstFrame = false
    }

    /** The first frame of this session. Returns the line to log, or null. */
    fun onFirstFrame(nowMs: Long, clickAtMs: Long?, afrTrackHeld: Boolean, pausedAtFirstFrame: Boolean): String? {
        if (emitted || firstFrameAtMs != null) return null
        firstFrameAtMs = nowMs
        this.afrTrackHeld = afrTrackHeld
        this.pausedAtFirstFrame = pausedAtFirstFrame
        if (pendingPlayingAtMs == null) return null
        return emit(nowMs, clickAtMs)
    }

    /** ExoPlayer's isPlaying changed. Returns the line to log, or null. */
    fun onIsPlaying(nowMs: Long, clickAtMs: Long?, isPlaying: Boolean): String? {
        if (emitted) return null
        if (firstFrameAtMs == null) {
            pendingPlayingAtMs = if (isPlaying) nowMs else null
            return null
        }
        if (!isPlaying) return null
        return emit(nowMs, clickAtMs)
    }

    private fun emit(nowMs: Long, clickAtMs: Long?): String {
        emitted = true
        seq += 1
        // The click belongs to the first session only: launchStartedAtMs never re-arms,
        // so a later episode or a recovery after the start would count the old click.
        val click = if (seq == 1) clickAtMs else null
        return line(
            clickToPlayingMs = elapsedSince(click, nowMs),
            firstFrameToPlayingMs = elapsedSince(firstFrameAtMs, nowMs),
            afrTrackHeld = afrTrackHeld,
            pausedAtFirstFrame = pausedAtFirstFrame,
            seq = seq
        )
    }

    companion object {
        /** Milliseconds from [startMs] to [nowMs], never negative; -1 without a start. */
        fun elapsedSince(startMs: Long?, nowMs: Long): Long =
            startMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: -1L

        /** The log line; scripts/shield_measure.py (M1.2) parses it. Keep the key names. */
        fun line(
            clickToPlayingMs: Long,
            firstFrameToPlayingMs: Long,
            afrTrackHeld: Boolean,
            pausedAtFirstFrame: Boolean,
            seq: Int
        ): String =
            "PLAYBACK_STARTED: clickToPlayingMs=$clickToPlayingMs " +
                "firstFrameToPlayingMs=$firstFrameToPlayingMs afrTrackHeld=$afrTrackHeld " +
                "pausedAtFirstFrame=$pausedAtFirstFrame seq=$seq"
    }
}
