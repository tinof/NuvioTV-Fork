package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.DecoderCounters
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PlaybackDecoderCounterFieldsTest {

    private fun counters(dropped: Int, maxConsecutive: Int = 0, skipped: Int = 0) = DecoderCounters().apply {
        droppedBufferCount = dropped
        maxConsecutiveDroppedBufferCount = maxConsecutive
        skippedOutputBufferCount = skipped
    }

    @Before fun reset() = PlaybackDecoderCounterFields.resetForTest()

    @Test fun `a single drop reaches the line below the callback threshold`() {
        val counter = counters(0)
        assertEquals(", dropped=0, maxConsecDropped=0, skipped=0, counterScope=1",
            PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.MEDIA_CODEC, counter))
        counter.droppedBufferCount = 1
        counter.maxConsecutiveDroppedBufferCount = 1
        counter.skippedOutputBufferCount = 3
        assertEquals(", dropped=1, maxConsecDropped=1, skipped=3, counterScope=1",
            PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.MEDIA_CODEC, counter))
    }

    @Test fun `a replaced renderer starts a new counter scope`() {
        PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.MEDIA_CODEC, counters(40))
        assertEquals(", dropped=2, maxConsecDropped=2, skipped=0, counterScope=2",
            PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.MEDIA_CODEC, counters(2, maxConsecutive = 2)))
    }

    @Test fun `tunnelled output logs all three counters`() {
        assertEquals(", dropped=5, maxConsecDropped=4, skipped=9, counterScope=1",
            PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.TUNNELLED, counters(5, 4, skipped = 9)))
    }

    @Test fun `modes without counters name the mode instead`() {
        assertEquals(", frameCounters=native",
            PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.NATIVE, counters(7)))
        assertEquals(", frameCounters=tunnelled_unsupported",
            PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.TUNNELLED_UNSUPPORTED, null))
        assertEquals(", frameCounters=media_codec",
            PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.MEDIA_CODEC, null))
    }

    @Test fun `no video adds nothing`() {
        assertEquals("", PlaybackDecoderCounterFields.fields(PlaybackVideoPresentation.UNCONFIGURED, counters(3)))
    }
}
