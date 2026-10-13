package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecsStringTest {

    @Test
    fun `H264 profile_idc is the first hex pair`() {
        assertEquals(100, VideoCodecsString.avcProfileIdc("avc1.640028"))
        assertEquals(110, VideoCodecsString.avcProfileIdc("avc1.6E0028"))
        assertEquals(122, VideoCodecsString.avcProfileIdc("AVC1.7A0033"))
        assertEquals(244, VideoCodecsString.avcProfileIdc("avc3.F40028"))
        assertEquals(110, VideoCodecsString.avcProfileIdc("avc1.6e0028, mp4a.40.2"))
        assertNull(VideoCodecsString.avcProfileIdc("avc1"))
        assertNull(VideoCodecsString.avcProfileIdc("avc1.6"))
        assertNull(VideoCodecsString.avcProfileIdc("hvc1.2.4.L150"))
        assertNull(VideoCodecsString.avcProfileIdc(null))
    }

    @Test
    fun `Hi10P and the 422 and 444 profiles are high bit depth`() {
        assertTrue(VideoCodecsString.isAvcHighBitDepthProfile("avc1.6E0028"))
        assertTrue(VideoCodecsString.isAvcHighBitDepthProfile("avc1.7A0033"))
        assertTrue(VideoCodecsString.isAvcHighBitDepthProfile("avc1.F40028"))
        assertFalse(VideoCodecsString.isAvcHighBitDepthProfile("avc1.640028"))
        assertFalse(VideoCodecsString.isAvcHighBitDepthProfile("avc1.42E01E"))
        assertFalse(VideoCodecsString.isAvcHighBitDepthProfile("avc1"))
        assertFalse(VideoCodecsString.isAvcHighBitDepthProfile(null))
    }

    @Test
    fun `bit depth from the codecs string`() {
        assertEquals(10, VideoCodecsString.bitDepth("dvhe.08.06"))
        assertEquals(10, VideoCodecsString.bitDepth("hvc1.2.4.L150"))
        assertEquals(8, VideoCodecsString.bitDepth("hev1.1.6.L120"))
        assertEquals(8, VideoCodecsString.bitDepth("avc1.640028"))
        assertNull(VideoCodecsString.bitDepth("avc1.6E0028"))
        assertEquals(10, VideoCodecsString.bitDepth("av01.0.13M.10"))
        assertNull(VideoCodecsString.bitDepth("vp09.02.40.10"))
        assertNull(VideoCodecsString.bitDepth(null))
    }

    @Test
    fun `chroma from the codecs string`() {
        assertEquals("4:2:0", VideoCodecsString.chroma("avc1.640028"))
        assertEquals("4:2:0", VideoCodecsString.chroma("avc1.6E0028"))
        assertNull(VideoCodecsString.chroma("avc1.7A0033"))
        assertEquals("4:2:0", VideoCodecsString.chroma("hvc1.2.4.L150"))
        assertEquals("4:2:0", VideoCodecsString.chroma("av01.0.08M.08"))
        assertNull(VideoCodecsString.chroma("av01.1.08M.08"))
    }
}
