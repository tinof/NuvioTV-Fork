package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.dvmkv.DolbyVisionCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionDecoderChoiceTest {

    private fun features(
        requiresSecure: Boolean = false,
        secureSupported: Boolean = false,
        secureRequired: Boolean = false,
        requiresTunneling: Boolean = false,
        tunnelingSupported: Boolean = false,
        tunnelingRequired: Boolean = false
    ) = DolbyVisionCodecFallback.matchesPlaybackFeatures(
        requiresSecure = requiresSecure,
        secureSupported = secureSupported,
        secureRequired = secureRequired,
        requiresTunneling = requiresTunneling,
        tunnelingSupported = tunnelingSupported,
        tunnelingRequired = tunnelingRequired
    )

    @Test
    fun `plain query takes a plain decoder`() {
        assertTrue(features())
    }

    @Test
    fun `tunneled query needs tunneling support`() {
        assertFalse(features(requiresTunneling = true))
        assertTrue(features(requiresTunneling = true, tunnelingSupported = true))
    }

    @Test
    fun `secure query needs secure support`() {
        assertFalse(features(requiresSecure = true))
        assertTrue(features(requiresSecure = true, secureSupported = true))
    }

    @Test
    fun `a decoder that requires a feature is skipped when the query does not ask for it`() {
        assertFalse(features(secureSupported = true, secureRequired = true))
        assertFalse(features(tunnelingSupported = true, tunnelingRequired = true))
    }

    private data class Decoder(val name: String, val hardwareDv: Boolean)

    private val hevc = Decoder("c2.vendor.hevc.decoder", hardwareDv = false)
    private val dv = Decoder("c2.vendor.dolby-vision.decoder", hardwareDv = true)

    @Test
    fun `dv decoders move first only when enabled and the track is dolby vision`() {
        val sorted = listOf(hevc, dv)
        assertEquals(
            listOf(dv, hevc),
            DolbyVisionCodecFallback.dvDecodersFirst(true, MimeTypes.VIDEO_DOLBY_VISION, sorted) { it.hardwareDv }
        )
        assertEquals(
            sorted,
            DolbyVisionCodecFallback.dvDecodersFirst(false, MimeTypes.VIDEO_DOLBY_VISION, sorted) { it.hardwareDv }
        )
        assertEquals(
            sorted,
            DolbyVisionCodecFallback.dvDecodersFirst(true, MimeTypes.VIDEO_H265, sorted) { it.hardwareDv }
        )
    }

    @Test
    fun `no dv decoder keeps the order`() {
        val sorted = listOf(hevc, hevc.copy(name = "c2.android.hevc.decoder"))
        assertEquals(
            sorted,
            DolbyVisionCodecFallback.dvDecodersFirst(true, MimeTypes.VIDEO_DOLBY_VISION, sorted) { it.hardwareDv }
        )
    }

    @Test
    fun `only profiles 7 and 8 remap to the hevc base layer`() {
        assertTrue(DolbyVisionCompatibility.hasHevcBaseLayer(7))
        assertTrue(DolbyVisionCompatibility.hasHevcBaseLayer(8))
        assertFalse(DolbyVisionCompatibility.hasHevcBaseLayer(5))
        assertFalse(DolbyVisionCompatibility.hasHevcBaseLayer(4))
        assertFalse(DolbyVisionCompatibility.hasHevcBaseLayer(9))
    }

    @Test
    fun `profile 5 remaps to hevc only on a box without any dv decoder`() {
        assertTrue(DolbyVisionCompatibility.remapsToHevcBaseLayer(5, false))
        assertFalse(DolbyVisionCompatibility.remapsToHevcBaseLayer(5, true))
        assertTrue(DolbyVisionCompatibility.remapsToHevcBaseLayer(7, true))
        assertTrue(DolbyVisionCompatibility.remapsToHevcBaseLayer(8, false))
        assertFalse(DolbyVisionCompatibility.remapsToHevcBaseLayer(4, false))
        assertFalse(DolbyVisionCompatibility.remapsToHevcBaseLayer(9, false))
    }

    @Test
    fun `dv decoder presence defaults to present until the player sets it`() {
        assertTrue(DolbyVisionCompatibility.isDolbyVisionDecoderPresent())
    }

    @Test
    fun `dv decoder name matches the codec list, secure suffix ignored`() {
        val known = setOf("omx.nvidia.dovi.decode", "c2.amlogic.dolby-vision.dvhe.decoder")
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("OMX.Nvidia.DOVI.decode", known))
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("OMX.Nvidia.DOVI.decode.secure", known))
        assertFalse(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("OMX.Nvidia.h265.decode", known))
    }

    @Test
    fun `the amlogic native fel port counts as a dv decoder`() {
        val known = setOf("c2.amlogic.dolby-vision.dvhe.decoder")
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("amstream_dves_hevc", known))
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("amstream_dves_hevc.sideband", known))
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("amstream_dves_hevc", emptySet()))
    }

    @Test
    fun `a failed codec list read is held for 30 seconds`() {
        assertFalse(DolbyVisionBaseLayerPolicy.isQueryFailureFresh(nowNanos = 5L, failedAtNanos = null))
        assertTrue(DolbyVisionBaseLayerPolicy.isQueryFailureFresh(nowNanos = 29_999_999_999L, failedAtNanos = 0L))
        assertFalse(DolbyVisionBaseLayerPolicy.isQueryFailureFresh(nowNanos = 30_000_000_000L, failedAtNanos = 0L))
    }

    @Test
    fun `name hints apply only without a codec list`() {
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("c2.amlogic.dolby-vision.dvhe.decoder", emptySet()))
        assertFalse(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("c2.amlogic.hevc.decoder.awesome", emptySet()))
        // A box whose HEVC decoder also lists DV: the list decides, not the name.
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("c2.mtk.hevc.decoder", setOf("c2.mtk.hevc.decoder")))
    }

    @Test
    fun `dv decoders first is the nvidia default on a dv display`() {
        assertTrue(DolbyVisionCodecFallback.prefersDvDecoders(switch = null, displayDv = true, manufacturer = "NVIDIA"))
        assertFalse(DolbyVisionCodecFallback.prefersDvDecoders(switch = null, displayDv = true, manufacturer = "Amlogic"))
        assertFalse(DolbyVisionCodecFallback.prefersDvDecoders(switch = null, displayDv = true, manufacturer = null))
    }

    @Test
    fun `dv decoders first switch wins on a dv display only`() {
        assertFalse(DolbyVisionCodecFallback.prefersDvDecoders(switch = false, displayDv = true, manufacturer = "NVIDIA"))
        assertTrue(DolbyVisionCodecFallback.prefersDvDecoders(switch = true, displayDv = true, manufacturer = "Amlogic"))
        assertFalse(DolbyVisionCodecFallback.prefersDvDecoders(switch = true, displayDv = false, manufacturer = "Amlogic"))
        assertFalse(DolbyVisionCodecFallback.prefersDvDecoders(switch = null, displayDv = false, manufacturer = "NVIDIA"))
    }
}
