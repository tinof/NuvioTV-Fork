package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerVideoFormatsTest {

    private val extractor = InAppYouTubeExtractor()

    @Test
    fun `codec is read from the mime type`() {
        assertEquals(TrailerVideoCodec.H264, trailerVideoCodecOf("video/mp4; codecs=\"avc1.640028\""))
        assertEquals(TrailerVideoCodec.VP9, trailerVideoCodecOf("video/webm; codecs=\"vp9\""))
        assertEquals(TrailerVideoCodec.VP9, trailerVideoCodecOf("video/webm; codecs=\"vp09.00.40.08\""))
        assertEquals(TrailerVideoCodec.AV1, trailerVideoCodecOf("video/mp4; codecs=\"av01.0.08M.08\""))
        assertEquals(TrailerVideoCodec.OTHER, trailerVideoCodecOf("video/mp4"))
    }

    @Test
    fun `HDR is read from the colour transfer, the quality label or the VP9 profile`() {
        assertTrue(
            isHdrTrailerFormat("video/webm; codecs=\"vp9\"", "1080p", "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084")
        )
        assertTrue(isHdrTrailerFormat("video/mp4; codecs=\"av01.0.09M.10\"", "1080p60 HDR", null))
        assertTrue(isHdrTrailerFormat("video/webm; codecs=\"vp09.02.40.10.01\"", null, null))
        assertFalse(
            isHdrTrailerFormat("video/mp4; codecs=\"avc1.640028\"", "1080p", "COLOR_TRANSFER_CHARACTERISTICS_BT709")
        )
    }

    @Test
    fun `video above 1080p is left out`() {
        val allowed = trailerVideoCandidates(
            listOf(
                video("2160p", height = 2160, width = 3840, codec = TrailerVideoCodec.VP9),
                video("1440p-scope", height = 1072, width = 2560, codec = TrailerVideoCodec.VP9),
                video("1080p", height = 1080, width = 1920, codec = TrailerVideoCodec.VP9),
                video("720p", height = 720, width = 1280, codec = TrailerVideoCodec.H264)
            ),
            TrailerVideoLimits()
        )

        assertEquals(listOf("1080p", "720p"), allowed.map { it.url })
    }

    @Test
    fun `a codec needs a decoder on this device`() {
        val candidates = listOf(
            video("av1", height = 1080, codec = TrailerVideoCodec.AV1, codecs = "av01.0.08M.08"),
            video("vp9", height = 1080, codec = TrailerVideoCodec.VP9, codecs = "vp9")
        )
        val noAv1 = TrailerVideoLimits(canDecode = { codecs, _, _, _ -> !codecs.startsWith("av01") })

        assertEquals(listOf("vp9"), trailerVideoCandidates(candidates, noAv1).map { it.url })
        assertEquals(listOf("av1", "vp9"), trailerVideoCandidates(candidates, TrailerVideoLimits()).map { it.url })
    }

    @Test
    fun `a stream the device cannot decode never comes back, even when nothing else fits`() {
        val av1 = video("2160p-av1", height = 2160, width = 3840, codec = TrailerVideoCodec.AV1, codecs = "av01.0.12M.08")
        val vp9 = video("2160p-vp9", height = 2160, width = 3840, codec = TrailerVideoCodec.VP9, codecs = "vp9")
        val noAv1 = TrailerVideoLimits(canDecode = { codecs, _, _, _ -> !codecs.startsWith("av01") })

        assertEquals(listOf(vp9), trailerVideoCandidates(listOf(av1, vp9), noAv1))
        assertTrue(trailerVideoCandidates(listOf(av1), noAv1).isEmpty())
    }

    @Test
    fun `HDR needs an HDR display`() {
        val candidates = listOf(
            video("hdr", height = 1080, codec = TrailerVideoCodec.VP9, isHdr = true),
            video("sdr", height = 1080, codec = TrailerVideoCodec.VP9)
        )

        assertEquals(listOf("sdr"), trailerVideoCandidates(candidates, TrailerVideoLimits()).map { it.url })
        assertEquals(
            listOf("hdr", "sdr"),
            trailerVideoCandidates(candidates, TrailerVideoLimits(hdrDisplay = true)).map { it.url }
        )
    }

    @Test
    fun `when the limits leave nothing every stream stays available`() {
        val candidates = listOf(video("2160p-av1", height = 2160, width = 3840, codec = TrailerVideoCodec.AV1))

        assertEquals(candidates, trailerVideoCandidates(candidates, TrailerVideoLimits()))
    }

    @Test
    fun `the limits are given up one at a time, HDR first, then size`() {
        val sdr = video("1080p-vp9", height = 1080, width = 1920, codec = TrailerVideoCodec.VP9)
        val hdr = video("1080p-vp9-hdr", height = 1080, width = 1920, codec = TrailerVideoCodec.VP9, isHdr = true)
        val big = video("2160p-vp9", height = 2160, width = 3840, codec = TrailerVideoCodec.VP9)
        val bigHdr = video("2160p-vp9-hdr", height = 2160, width = 3840, codec = TrailerVideoCodec.VP9, isHdr = true)
        val limits = TrailerVideoLimits()

        assertEquals(listOf(sdr), trailerVideoCandidates(listOf(bigHdr, big, hdr, sdr), limits))
        assertEquals(listOf(hdr), trailerVideoCandidates(listOf(bigHdr, big, hdr), limits))
        assertEquals(listOf(big), trailerVideoCandidates(listOf(bigHdr, big), limits))
        assertEquals(listOf(bigHdr), trailerVideoCandidates(listOf(bigHdr), limits))
    }

    @Test
    fun `at the same height H264 comes first, then VP9, then AV1`() {
        val ranked = extractor.sortCandidates(
            listOf(
                video("av1-1080", height = 1080, codec = TrailerVideoCodec.AV1, bitrate = 3_000_000.0),
                video("vp9-1080", height = 1080, codec = TrailerVideoCodec.VP9, bitrate = 2_500_000.0),
                video("h264-1080", height = 1080, codec = TrailerVideoCodec.H264, bitrate = 2_000_000.0),
                video("h264-720", height = 720, codec = TrailerVideoCodec.H264, bitrate = 1_500_000.0)
            )
        )

        assertEquals(listOf("h264-1080", "vp9-1080", "av1-1080", "h264-720"), ranked.map { it.url })
    }

    @Test
    fun `a taller stream still beats the preferred codec`() {
        val ranked = extractor.sortCandidates(
            listOf(
                video("h264-720", height = 720, codec = TrailerVideoCodec.H264),
                video("vp9-1080", height = 1080, codec = TrailerVideoCodec.VP9)
            )
        )

        assertEquals("vp9-1080", ranked.first().url)
    }

    private fun video(
        url: String,
        height: Int,
        codec: TrailerVideoCodec,
        width: Int = 0,
        isHdr: Boolean = false,
        bitrate: Double = 1_000_000.0,
        codecs: String? = null
    ) = StreamCandidate(
        client = "visionos",
        priority = 0,
        url = url,
        score = height * 1_000_000_000.0 + 30 * 1_000_000.0 + bitrate,
        hasN = false,
        itag = "",
        height = height,
        fps = 30,
        ext = "mp4",
        width = width,
        codec = codec,
        codecs = codecs,
        isHdr = isHdr
    )

    @Test
    fun `AV1 trailers need a hardware decoder on Nvidia unless the switch says otherwise`() {
        assertTrue(trailerAv1HardwareOnly(null, manufacturer = "NVIDIA"))
        assertFalse(trailerAv1HardwareOnly(null, manufacturer = "Amazon"))
        assertFalse(trailerAv1HardwareOnly(false, manufacturer = "NVIDIA"))
        assertTrue(trailerAv1HardwareOnly(true, manufacturer = "Amazon"))
    }

    @Test
    fun `the hardware AV1 rule drops only AV1 and keeps the decoder question`() {
        val anyDecoder: (String, Int, Int, Int) -> Boolean = { _, _, _, _ -> true }
        val shield = trailerCanDecode(av1HardwareOnly = true, hasHardwareAv1 = { false }, canDecode = anyDecoder)
        assertFalse(shield("av01.0.08M.08", 1920, 1080, 24))
        assertTrue(shield("avc1.640028", 1920, 1080, 24))
        assertTrue(shield("vp09.00.40.08", 1920, 1080, 24))

        val hardwareAv1 = trailerCanDecode(av1HardwareOnly = true, hasHardwareAv1 = { true }, canDecode = anyDecoder)
        assertTrue(hardwareAv1("av01.0.08M.08", 1920, 1080, 24))

        val off = trailerCanDecode(av1HardwareOnly = false, hasHardwareAv1 = { false }, canDecode = anyDecoder)
        assertTrue(off("av01.0.08M.08", 1920, 1080, 24))

        val noDecoder = trailerCanDecode(av1HardwareOnly = false, hasHardwareAv1 = { true }) { _, _, _, _ -> false }
        assertFalse(noDecoder("avc1.640028", 1920, 1080, 24))
    }
}
