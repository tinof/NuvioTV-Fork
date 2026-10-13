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
    fun `dv decoder name matches the codec list, secure suffix ignored`() {
        val known = setOf("omx.nvidia.dovi.decode", "c2.amlogic.dolby-vision.dvhe.decoder")
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("OMX.Nvidia.DOVI.decode", known))
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("OMX.Nvidia.DOVI.decode.secure", known))
        assertFalse(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("OMX.Nvidia.h265.decode", known))
    }

    @Test
    fun `name hints apply only without a codec list`() {
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("c2.amlogic.dolby-vision.dvhe.decoder", emptySet()))
        assertFalse(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("c2.amlogic.hevc.decoder.awesome", emptySet()))
        // A box whose HEVC decoder also lists DV: the list decides, not the name.
        assertTrue(DolbyVisionBaseLayerPolicy.isDolbyVisionDecoderName("c2.mtk.hevc.decoder", setOf("c2.mtk.hevc.decoder")))
    }
}
