package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoTunnelDemotionTest {

    @Test
    fun `decode-order tracks are never tunnelled`() {
        assertEquals("decode-order", videoTunnelDemotionReason(MimeTypes.VIDEO_VC1, null, null, false))
        assertEquals("decode-order", videoTunnelDemotionReason(MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_MATROSKA, null, false))
        assertEquals("decode-order", videoTunnelDemotionReason(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_AVI, "avc1.640028", false))
    }

    @Test
    fun `ordinary tracks keep tunneling`() {
        assertNull(videoTunnelDemotionReason(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_MATROSKA, "avc1.640028", false))
        assertNull(videoTunnelDemotionReason(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MP4, "hvc1.2.4.L153", false))
        assertNull(videoTunnelDemotionReason(null, null, null, true))
    }

    @Test
    fun `dolby vision drops tunneling only with the debug switch`() {
        assertNull(videoTunnelDemotionReason(MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_MATROSKA, "dvhe.08.06", false))
        assertEquals(
            "dolby-vision",
            videoTunnelDemotionReason(MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_MATROSKA, "dvhe.08.06", true)
        )
        assertEquals("dolby-vision", videoTunnelDemotionReason(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MP4, "dvh1.08.06", true))
    }
}
