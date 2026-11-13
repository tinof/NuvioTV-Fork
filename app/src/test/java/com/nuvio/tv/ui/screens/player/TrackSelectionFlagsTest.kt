package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** B4: the both-off selection of an audio-and-video quiesce does not reach the UI flags. */
class TrackSelectionFlagsTest {

    private val hdrTunnelled = PlayerUiState(isHdrVideo = true, tunnelingApplied = true)

    @Test
    fun `the both-off selection of an audio-and-video quiesce keeps hdr and tunnelling`() {
        val noVideo = hdrTunnelled.withTrackSelectionFlags(avQuiesceInFlight = true, isHdrVideo = false)
        assertSame(hdrTunnelled, noVideo)
        assertTrue(noVideo.isHdrVideo)

        val noTunnel = hdrTunnelled.withTrackSelectionFlags(avQuiesceInFlight = true, tunnelingApplied = false)
        assertSame(hdrTunnelled, noTunnel)
        assertTrue(noTunnel.tunnelingApplied)
    }

    @Test
    fun `outside the quiesce a track change sets hdr and tunnelling`() {
        val sdr = hdrTunnelled.withTrackSelectionFlags(avQuiesceInFlight = false, isHdrVideo = false)
        assertFalse(sdr.isHdrVideo)
        assertTrue(sdr.tunnelingApplied)

        val untunnelled = hdrTunnelled.withTrackSelectionFlags(avQuiesceInFlight = false, tunnelingApplied = false)
        assertTrue(untunnelled.isHdrVideo)
        assertFalse(untunnelled.tunnelingApplied)

        val hdr = PlayerUiState().withTrackSelectionFlags(avQuiesceInFlight = false, isHdrVideo = true)
        assertTrue(hdr.isHdrVideo)
    }
}
