package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStartedStageTest {

    private val click = 100L

    private fun started(clickToPlayingMs: Long, firstFrameToPlayingMs: Long, afrTrackHeld: Boolean = false,
                        pausedAtFirstFrame: Boolean = false, seq: Int = 1) =
        PlaybackStartedStage.line(clickToPlayingMs, firstFrameToPlayingMs, afrTrackHeld, pausedAtFirstFrame, seq)

    @Test
    fun emitsOnceWhenPlayingFollowsFirstFrame() {
        val stage = PlaybackStartedStage()
        assertNull(stage.onFirstFrame(1_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false))
        assertEquals(started(950L, 50L), stage.onIsPlaying(1_050L, click, isPlaying = true))
        assertNull(stage.onIsPlaying(1_100L, click, isPlaying = true))
    }

    @Test
    fun trackAfrHeldStartReportsGap() {
        val stage = PlaybackStartedStage()
        assertNull(stage.onFirstFrame(1_000L, click, afrTrackHeld = true, pausedAtFirstFrame = false))
        assertEquals(started(3_100L, 2_200L, afrTrackHeld = true), stage.onIsPlaying(3_200L, click, isPlaying = true))
    }

    @Test
    fun playingBeforeFirstFrameEmitsAtFirstFrameWithZeroGap() {
        // The normal start: play() in the first-frame callback reports isPlaying before the diagnostics run.
        val stage = PlaybackStartedStage()
        assertNull(stage.onIsPlaying(990L, click, isPlaying = true))
        assertEquals(
            started(900L, 0L),
            stage.onFirstFrame(1_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false)
        )
    }

    @Test
    fun playingStoppedBeforeFirstFrameDoesNotEmit() {
        val stage = PlaybackStartedStage()
        assertNull(stage.onIsPlaying(500L, click, isPlaying = true))
        assertNull(stage.onIsPlaying(600L, click, isPlaying = false))
        assertNull(stage.onFirstFrame(1_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false))
        assertEquals(started(1_100L, 200L), stage.onIsPlaying(1_200L, click, isPlaying = true))
    }

    @Test
    fun noEmitWithoutFirstFrame() {
        val stage = PlaybackStartedStage()
        assertNull(stage.onIsPlaying(500L, click, isPlaying = true))
        assertNull(stage.onIsPlaying(700L, click, isPlaying = true))
    }

    @Test
    fun rebufferAfterStartDoesNotReemit() {
        val stage = PlaybackStartedStage()
        stage.onFirstFrame(1_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false)
        assertEquals(started(1_000L, 100L), stage.onIsPlaying(1_100L, click, isPlaying = true))
        assertNull(stage.onIsPlaying(2_000L, click, isPlaying = false))
        assertNull(stage.onIsPlaying(2_500L, click, isPlaying = true))
        assertNull(stage.onFirstFrame(2_600L, click, afrTrackHeld = false, pausedAtFirstFrame = false))
    }

    @Test
    fun repeatedFirstFrameKeepsFirstTimestamp() {
        val stage = PlaybackStartedStage()
        stage.onFirstFrame(1_000L, click, afrTrackHeld = true, pausedAtFirstFrame = false)
        assertNull(stage.onFirstFrame(1_500L, click, afrTrackHeld = false, pausedAtFirstFrame = false))
        assertEquals(started(1_900L, 1_000L, afrTrackHeld = true), stage.onIsPlaying(2_000L, click, isPlaying = true))
    }

    @Test
    fun rearmBeforeEmitKeepsClick() {
        // A failover during startup: the viewer still waits from the click.
        val stage = PlaybackStartedStage()
        stage.onFirstFrame(1_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false)
        stage.arm()
        assertNull(stage.onFirstFrame(4_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false))
        assertEquals(started(4_000L, 100L), stage.onIsPlaying(4_100L, click, isPlaying = true))
    }

    @Test
    fun rearmAfterEmitReportsClickMinusOneAndSeqTwo() {
        // Next episode on the same controller: the old click is not this start's click.
        val stage = PlaybackStartedStage()
        stage.onFirstFrame(1_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false)
        stage.onIsPlaying(1_100L, click, isPlaying = true)
        stage.arm()
        stage.onFirstFrame(60_000L, click, afrTrackHeld = false, pausedAtFirstFrame = false)
        assertEquals(started(-1L, 300L, seq = 2), stage.onIsPlaying(60_300L, click, isPlaying = true))
    }

    @Test
    fun nullClickGivesMinusOne() {
        val stage = PlaybackStartedStage()
        stage.onFirstFrame(1_000L, null, afrTrackHeld = false, pausedAtFirstFrame = false)
        assertEquals(started(-1L, 20L), stage.onIsPlaying(1_020L, null, isPlaying = true))
    }

    @Test
    fun negativeDeltaClampsToZero() {
        assertEquals(0L, PlaybackStartedStage.elapsedSince(2_000L, 1_000L))
        assertEquals(-1L, PlaybackStartedStage.elapsedSince(null, 1_000L))
        assertEquals(500L, PlaybackStartedStage.elapsedSince(500L, 1_000L))
    }

    @Test
    fun pausedAtFirstFrameIsTagged() {
        val stage = PlaybackStartedStage()
        stage.onFirstFrame(1_000L, click, afrTrackHeld = false, pausedAtFirstFrame = true)
        assertEquals(
            started(9_900L, 9_000L, pausedAtFirstFrame = true),
            stage.onIsPlaying(10_000L, click, isPlaying = true)
        )
    }

    @Test
    fun lineFormatMatchesParserContract() {
        assertEquals(
            "PLAYBACK_STARTED: clickToPlayingMs=3100 firstFrameToPlayingMs=2200 afrTrackHeld=true " +
                "pausedAtFirstFrame=false seq=1",
            PlaybackStartedStage.line(3_100L, 2_200L, afrTrackHeld = true, pausedAtFirstFrame = false, seq = 1)
        )
    }
}
