package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackAfrQuiesceTest {

    private fun quiesce(
        audioDisabled: Boolean = false,
        tunneling: Boolean = true,
        lossless: Boolean = true,
        switch: Boolean? = null,
        manufacturer: String? = "NVIDIA"
    ) = trackAfrQuiesce(audioDisabled, tunneling, lossless, switch, manufacturer)

    @Test
    fun `without tunnelling only audio is quiesced, as before`() {
        assertEquals(TrackAfrQuiesce.AUDIO, quiesce(tunneling = false))
        assertEquals(TrackAfrQuiesce.AUDIO, quiesce(tunneling = false, lossless = false, manufacturer = "Amlogic"))
        assertEquals(TrackAfrQuiesce.AUDIO, quiesce(tunneling = false, switch = true))
    }

    @Test
    fun `tunnelled lossless audio on nvidia quiesces audio and video together`() {
        assertEquals(TrackAfrQuiesce.AUDIO_AND_VIDEO, quiesce())
    }

    @Test
    fun `tunnelled sessions elsewhere or with lossy audio are left alone`() {
        assertEquals(TrackAfrQuiesce.NONE, quiesce(manufacturer = "Amlogic"))
        assertEquals(TrackAfrQuiesce.NONE, quiesce(manufacturer = null))
        assertEquals(TrackAfrQuiesce.NONE, quiesce(lossless = false))
    }

    @Test
    fun `the afr_quiesce_av switch forces either side for tunnelled lossless audio`() {
        assertEquals(TrackAfrQuiesce.NONE, quiesce(switch = false))
        assertEquals(TrackAfrQuiesce.AUDIO_AND_VIDEO, quiesce(switch = true, manufacturer = "Amlogic"))
        assertEquals(TrackAfrQuiesce.NONE, quiesce(switch = true, lossless = false))
    }

    @Test
    fun `a stream already playing without audio is never touched`() {
        assertEquals(TrackAfrQuiesce.NONE, quiesce(audioDisabled = true))
        assertEquals(TrackAfrQuiesce.NONE, quiesce(audioDisabled = true, tunneling = false))
        assertEquals(TrackAfrQuiesce.NONE, quiesce(audioDisabled = true, switch = true))
    }

    private fun group(mime: String, vararg selected: Boolean): Tracks.Group {
        val formats = selected.map { Format.Builder().setSampleMimeType(mime).build() }.toTypedArray()
        return Tracks.Group(
            TrackGroup(*formats),
            false,
            IntArray(selected.size) { C.FORMAT_HANDLED },
            selected
        )
    }

    @Test
    fun `selected audio mime type comes from the selected audio track`() {
        val tracks = Tracks(
            listOf(
                group(MimeTypes.VIDEO_H265, true),
                group(MimeTypes.AUDIO_AC3, false),
                group(MimeTypes.AUDIO_TRUEHD, true)
            )
        )
        assertEquals(MimeTypes.AUDIO_TRUEHD, selectedAudioMimeType(tracks))
    }

    @Test
    fun `no selected audio track gives no mime type`() {
        assertNull(selectedAudioMimeType(Tracks(listOf(group(MimeTypes.AUDIO_TRUEHD, false)))))
        assertNull(selectedAudioMimeType(Tracks(listOf(group(MimeTypes.VIDEO_H265, true)))))
        assertNull(selectedAudioMimeType(Tracks.EMPTY))
    }
}
