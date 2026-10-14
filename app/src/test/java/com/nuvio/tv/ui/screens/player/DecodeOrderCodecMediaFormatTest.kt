package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeOrderCodecMediaFormatTest {

    private fun format(sampleMimeType: String, containerMimeType: String? = null): Format = Format.Builder()
        .setSampleMimeType(sampleMimeType)
        .setContainerMimeType(containerMimeType)
        .build()

    /** Runs the renderer's format building with a map standing in for MediaFormat; returns the flags super got. */
    private fun build(
        format: Format,
        noPostProcessWorkaround: Boolean,
        vendorUnstablePts: Boolean,
        keys: MutableMap<String, Int> = mutableMapOf()
    ): List<Boolean> {
        val passed = mutableListOf<Boolean>()
        val result = decodeOrderCodecMediaFormat<MutableMap<String, Int>>(
            format = format,
            deviceNeedsNoPostProcessWorkaround = noPostProcessWorkaround,
            vendorUnstablePts = vendorUnstablePts,
            baseFormat = { flag -> passed += flag; keys },
            setInteger = { key, value -> put(key, value) }
        )
        assertTrue(result === keys)
        return passed
    }

    @Test
    fun `media3's nvidia no-post-process flag reaches super unchanged`() {
        val hevc = format(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MATROSKA)
        assertEquals(listOf(true), build(hevc, noPostProcessWorkaround = true, vendorUnstablePts = false))
        assertEquals(listOf(false), build(hevc, noPostProcessWorkaround = false, vendorUnstablePts = false))
    }

    @Test
    fun `the flag is forwarded on the vendor pts path too`() {
        val vc1 = format(MimeTypes.VIDEO_VC1, MimeTypes.VIDEO_AVI)
        assertEquals(listOf(true), build(vc1, noPostProcessWorkaround = true, vendorUnstablePts = true))
        assertEquals(listOf(false), build(vc1, noPostProcessWorkaround = false, vendorUnstablePts = true))
    }

    @Test
    fun `vendor keys only for decode-order tracks with the switch`() {
        val aviKeys = mutableMapOf<String, Int>()
        build(format(MimeTypes.VIDEO_VC1, MimeTypes.VIDEO_AVI), true, vendorUnstablePts = true, keys = aviKeys)
        assertEquals(mapOf(KEY_AMLOGIC_UNSTABLE_PTS to 1, KEY_AMLOGIC_AVI_DISCARD to 1), aviKeys)

        val mkvKeys = mutableMapOf<String, Int>()
        build(format(MimeTypes.VIDEO_VC1, MimeTypes.VIDEO_MATROSKA), true, vendorUnstablePts = true, keys = mkvKeys)
        assertEquals(mapOf(KEY_AMLOGIC_UNSTABLE_PTS to 1), mkvKeys)

        val off = mutableMapOf<String, Int>()
        build(format(MimeTypes.VIDEO_VC1, MimeTypes.VIDEO_AVI), true, vendorUnstablePts = false, keys = off)
        assertTrue(off.isEmpty())

        val hevc = mutableMapOf<String, Int>()
        build(format(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MATROSKA), true, vendorUnstablePts = true, keys = hevc)
        assertTrue(hevc.isEmpty())
    }
}
