package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MP4 head fixtures built box by box, the way a muxer writes them. */
class Mp4AfrProbeTest {

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun u32(value: Long): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun u32(value: Int): ByteArray = u32(value.toLong())

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { acc, child -> acc + child }
        return u32(8 + payload.size) + ascii(type) + payload
    }

    private fun largeBox(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { acc, child -> acc + child }
        return u32(1) + ascii(type) + u32(0) + u32(16 + payload.size) + payload
    }

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()) + payload)

    private val ftyp = box("ftyp", ascii("isom"), u32(512), ascii("isomiso2avc1mp41"))

    private fun tkhd(trackId: Int) = fullBox("tkhd", 0, 7, u32(0) + u32(0) + u32(trackId) + ByteArray(68))

    private fun mdhd(timescale: Int, version: Int = 0) = if (version == 1) {
        fullBox("mdhd", 1, 0, ByteArray(16) + u32(timescale) + ByteArray(8) + u32(0))
    } else {
        fullBox("mdhd", 0, 0, u32(0) + u32(0) + u32(timescale) + u32(0) + u32(0))
    }

    private fun hdlr(handler: String) = fullBox("hdlr", 0, 0, u32(0) + ascii(handler) + ByteArray(12) + ascii("Handler\u0000"))

    private fun stsd(width: Int, height: Int) = fullBox(
        "stsd", 0, 0,
        u32(1) + box("avc1", ByteArray(6) + u16(1) + ByteArray(16) + u16(width) + u16(height) + ByteArray(50))
    )

    private fun stts(vararg entries: Pair<Int, Int>) = fullBox(
        "stts", 0, 0,
        entries.fold(u32(entries.size)) { acc, (count, delta) -> acc + u32(count) + u32(delta) }
    )

    private fun stsz(samples: Int) = fullBox("stsz", 0, 0, u32(0) + u32(samples) + ByteArray(samples * 4))

    private fun trak(trackId: Int, handler: String, timescale: Int, vararg tables: ByteArray, mdhdVersion: Int = 0) = box(
        "trak",
        tkhd(trackId),
        box("mdia", mdhd(timescale, mdhdVersion), hdlr(handler), box("minf", box("stbl", *tables)))
    )

    private val mvhd = fullBox("mvhd", 0, 0, ByteArray(96))

    private val audioTrak = trak(2, "soun", 48_000, stts(5_000 to 1_024))

    private fun videoTrak(vararg tables: ByteArray, timescale: Int = 24_000, mdhdVersion: Int = 0) =
        trak(1, "vide", timescale, *tables, mdhdVersion = mdhdVersion)

    private fun parse(bytes: ByteArray) = Mp4AfrProbe.parseVideoFrameRateFromHead(bytes)

    @Test
    fun `faststart file with the audio track first`() {
        val video = videoTrak(stsd(1920, 1080), stts(1 to 2_002, 1_000 to 1_001))
        val hint = parse(ftyp + box("moov", mvhd, audioTrak, video) + box("mdat", ByteArray(64)))
        assertNotNull(hint)
        assertEquals(24_000f / 1_001f, hint!!.rawFps, 0.001f)
        assertEquals(1920, hint.width)
        assertEquals(1080, hint.height)
    }

    @Test
    fun `moov cut off by the head window still gives the rate`() {
        val video = videoTrak(stsd(3840, 2160), stts(150_000 to 1_001), stsz(150_000))
        val file = ftyp + box("moov", mvhd, audioTrak, video, box("udta"))
        assertTrue(file.size > 262_144)
        val hint = parse(file.copyOf(262_144))
        assertNotNull(hint)
        assertEquals(24_000f / 1_001f, hint!!.rawFps, 0.001f)
        assertEquals(3840, hint.width)
    }

    @Test
    fun `fragmented MP4 takes the duration from trex`() {
        val video = videoTrak(stsd(1280, 720), stts())
        val trex = fullBox("trex", 0, 0, u32(1) + u32(1) + u32(1_001) + u32(0) + u32(0))
        val hint = parse(ftyp + box("moov", mvhd, video, box("mvex", trex)))
        assertEquals(24_000f / 1_001f, hint!!.rawFps, 0.001f)
    }

    @Test
    fun `the first fragment's tfhd wins over trex`() {
        val video = videoTrak(stsd(1920, 1080), stts(), timescale = 25_000)
        val trex = fullBox("trex", 0, 0, u32(1) + u32(1) + u32(1_001) + u32(0) + u32(0))
        // Flags: sample-description-index present (0x02) before the default duration (0x08).
        val tfhd = fullBox("tfhd", 0, 0x00000A, u32(1) + u32(1) + u32(1_000))
        val moof = box("moof", fullBox("mfhd", 0, 0, u32(1)), box("traf", tfhd))
        val hint = parse(ftyp + box("moov", mvhd, video, box("mvex", trex)) + moof + box("mdat", ByteArray(32)))
        assertEquals(25f, hint!!.rawFps, 0.001f)
    }

    @Test
    fun `64-bit box size and version 1 mdhd`() {
        val video = videoTrak(stsd(1920, 1080), stts(500 to 1_000), timescale = 50_000, mdhdVersion = 1)
        val hint = parse(ftyp + largeBox("moov", mvhd, video))
        assertEquals(50f, hint!!.rawFps, 0.001f)
    }

    @Test
    fun `a 64-bit size near Long MAX_VALUE is a cut-off box, not a wrap-around`() {
        // pos + size would overflow to a negative end and walk the parser backwards.
        val hostile = u32(1) + ascii("free") + byteArrayOf(0x7F, -1, -1, -1, -1, -1, -1, -16) + ByteArray(64)
        assertNull(parse(ftyp + hostile))
        val huge = u32(1) + ascii("moov") + byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1) + ByteArray(64)
        assertNull(parse(ftyp + huge))
    }

    @Test
    fun `no rate without a moov in the head, a video track or an MP4 at all`() {
        // moov at the end: the window holds ftyp and the start of mdat only.
        val mdatFirst = ftyp + u32(50_000_000) + ascii("mdat") + ByteArray(1_024)
        assertNull(parse(mdatFirst))
        assertNull(parse(ftyp + box("moov", mvhd, audioTrak)))
        val matroska = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
        assertNull(parse(matroska))
        assertNull(parse(ByteArray(0)))
        assertNull(parse(ftyp + box("moov", mvhd, videoTrak(stsd(1920, 1080), stts()))))
    }
}
