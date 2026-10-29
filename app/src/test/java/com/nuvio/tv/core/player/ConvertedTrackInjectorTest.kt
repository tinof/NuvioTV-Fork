package com.nuvio.tv.core.player

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RPU injection of a converted track's AU (ROADMAP DV2.7), on length-delimited samples. */
class ConvertedTrackInjectorTest {

    // The two scene-refresh variants of one shot differ in size, as ue(v) coding can make them.
    private val refreshRpu = byteArrayOf(0x7C, 0x01, 0x11, 0x22, 0x33, 0x44)
    private val plainRpu = byteArrayOf(0x7C, 0x01, 0x55, 0x66, 0x77)
    private val shot = DoviRpuBuilder.ShotRpu(refreshRpu, plainRpu)

    // NAL headers: type << 1 in the first byte, TemporalId + 1 in the second.
    private val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01)
    private val sps = byteArrayOf(0x42, 0x01, 0x01, 0x02, 0x03)
    private val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
    private val prefixSei = byteArrayOf(0x4E, 0x01, 0x89.toByte(), 0x08, 0x7C, 0x01)
    private val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x7C, 0x01, 0x10)
    private val trail = byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x7C, 0x01)
    private val strayRpu = byteArrayOf(0x7C, 0x01, 0x99.toByte(), 0x98.toByte())

    @Test
    fun `every AU gets one RPU as its last NAL and keeps every other NAL byte for byte`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = false)
        for (nals in listOf(listOf(vps, sps, pps, prefixSei, idr), listOf(trail), listOf(prefixSei, trail))) {
            val out = inject(injector, nals, streamStart = false)
            assertEquals(lengthPrefixed(nals + listOf(plainRpu)).toList(), out.toList())
        }
    }

    @Test
    fun `an RPU the AU already carries is dropped, so it has exactly one`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = false)
        val out = inject(injector, listOf(prefixSei, strayRpu, idr, strayRpu), streamStart = false)
        assertArrayEquals(lengthPrefixed(listOf(prefixSei, idr, plainRpu)), out)
    }

    @Test
    fun `by default only frame 0 carries scene refresh`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = false)
        assertArrayEquals(lengthPrefixed(listOf(idr, refreshRpu)), inject(injector, listOf(idr), streamStart = true))
        assertArrayEquals(lengthPrefixed(listOf(trail, plainRpu)), inject(injector, listOf(trail), streamStart = false))
        // A seek: its first AU is not frame 0 and carries 0, as an mkvdovi file played from there does.
        injector.onReset()
        assertArrayEquals(lengthPrefixed(listOf(idr, plainRpu)), inject(injector, listOf(idr), streamStart = false))
        // A seek back to the start reads frame 0 again.
        injector.onReset()
        assertArrayEquals(lengthPrefixed(listOf(idr, refreshRpu)), inject(injector, listOf(idr), streamStart = true))
    }

    @Test
    fun `with the switch the first AU after every start, seek or resync carries scene refresh`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = true)
        assertArrayEquals(lengthPrefixed(listOf(idr, refreshRpu)), inject(injector, listOf(idr), streamStart = false))
        assertArrayEquals(lengthPrefixed(listOf(trail, plainRpu)), inject(injector, listOf(trail), streamStart = false))
        injector.onReset()
        assertArrayEquals(lengthPrefixed(listOf(idr, refreshRpu)), inject(injector, listOf(idr), streamStart = false))
        assertArrayEquals(lengthPrefixed(listOf(trail, plainRpu)), inject(injector, listOf(trail), streamStart = false))
        // Frame 0 carries it whatever the generation.
        assertArrayEquals(lengthPrefixed(listOf(idr, refreshRpu)), inject(injector, listOf(idr), streamStart = true))
    }

    @Test
    fun `with the switch the flag waits for the first IRAP AU after a seek, the one the decoder starts on`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = true)
        injector.onReset()
        // Media3 drops the AUs before the first keyframe after a seek.
        assertArrayEquals(lengthPrefixed(listOf(trail, plainRpu)), inject(injector, listOf(trail), streamStart = false))
        assertArrayEquals(lengthPrefixed(listOf(prefixSei, idr, refreshRpu)), inject(injector, listOf(prefixSei, idr), streamStart = false))
        assertArrayEquals(lengthPrefixed(listOf(idr, plainRpu)), inject(injector, listOf(idr), streamStart = false))
        // A CRA (type 21) is IRAP too.
        val cra = byteArrayOf(0x2A, 0x01, 0xA0.toByte())
        injector.onReset()
        assertArrayEquals(lengthPrefixed(listOf(cra, refreshRpu)), inject(injector, listOf(cra), streamStart = false))
    }

    @Test
    fun `every NAL length field size from 1 to 4 bytes`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = false)
        for (fieldLength in 1..4) {
            val sample = lengthPrefixed(listOf(sps, idr), fieldLength)
            val out = ExposedByteArrayOutputStream(16)
            assertTrue(injector.inject(sample, sample.size, fieldLength, streamStart = false, timeUs = 0, out = out))
            assertArrayEquals("$fieldLength", lengthPrefixed(listOf(sps, idr, plainRpu), fieldLength), out.toByteArray())
        }
    }

    @Test
    fun `only the first length bytes of a reused buffer are read`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = false)
        val sample = lengthPrefixed(listOf(idr))
        val buffer = sample + ByteArray(32) { 0x7F }
        val out = ExposedByteArrayOutputStream(16)
        assertTrue(injector.inject(buffer, sample.size, 4, streamStart = false, timeUs = 0, out = out))
        assertArrayEquals(lengthPrefixed(listOf(idr, plainRpu)), out.toByteArray())
    }

    @Test
    fun `a zero-length NAL is kept`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = false)
        val out = inject(injector, listOf(idr, ByteArray(0)), streamStart = false)
        assertArrayEquals(lengthPrefixed(listOf(idr, ByteArray(0), plainRpu)), out)
    }

    @Test
    fun `a sample whose NAL lengths do not add up is refused`() {
        val injector = ConvertedTrackInjector(StaticTrackRpus(shot), refreshEveryStart = false)
        val sample = lengthPrefixed(listOf(idr))
        val out = ExposedByteArrayOutputStream(16)
        // A length that runs past the sample, a truncated length field, a 4-byte length above Int.MAX_VALUE.
        assertFalse(injector.inject(sample, sample.size - 1, 4, streamStart = false, timeUs = 0, out = out))
        assertFalse(injector.inject(sample + byteArrayOf(0, 0), sample.size + 2, 4, streamStart = false, timeUs = 0, out = out))
        val huge = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + idr
        assertFalse(injector.inject(huge, huge.size, 4, streamStart = false, timeUs = 0, out = out))
        assertFalse(injector.inject(sample, sample.size, 0, streamStart = false, timeUs = 0, out = out))
        assertFalse(injector.inject(sample, sample.size, 5, streamStart = false, timeUs = 0, out = out))
    }

    @Test
    fun `an RPU too long for the length field is refused`() {
        val long = DoviRpuBuilder.ShotRpu(ByteArray(300) { 0x7C }, ByteArray(256) { 0x7C })
        val injector = ConvertedTrackInjector(StaticTrackRpus(long), refreshEveryStart = false)
        val sample = lengthPrefixed(listOf(idr), 1)
        assertFalse(injector.inject(sample, sample.size, 1, streamStart = false, timeUs = 0, out = ExposedByteArrayOutputStream(16)))
        val wide = lengthPrefixed(listOf(idr), 2)
        val out = ExposedByteArrayOutputStream(16)
        assertTrue(injector.inject(wide, wide.size, 2, streamStart = false, timeUs = 0, out = out))
        assertEquals(wide.size + 2 + 256, out.size())
    }

    @Test
    fun `fits checks both NALs against the length field`() {
        // The larger NAL decides, whichever variant it is.
        val refreshLarger = DoviRpuBuilder.ShotRpu(ByteArray(256), ByteArray(255))
        val plainLarger = DoviRpuBuilder.ShotRpu(ByteArray(255), ByteArray(256))
        for (s in listOf(refreshLarger, plainLarger)) {
            assertFalse(ConvertedTrackInjector.fits(s, 1))
            assertTrue(ConvertedTrackInjector.fits(s, 2))
        }
        assertTrue(ConvertedTrackInjector.fits(shot, 1))
        assertTrue(ConvertedTrackInjector.fits(DoviRpuBuilder.ShotRpu(ByteArray(65_536), ByteArray(1)), 3))
        assertFalse(ConvertedTrackInjector.fits(DoviRpuBuilder.ShotRpu(ByteArray(65_536), ByteArray(1)), 2))
        assertTrue(ConvertedTrackInjector.fits(shot, 4))
        assertFalse(ConvertedTrackInjector.fits(shot, 0))
        assertFalse(ConvertedTrackInjector.fits(shot, 5))
    }

    private fun inject(injector: ConvertedTrackInjector, nals: List<ByteArray>, streamStart: Boolean): ByteArray {
        val sample = lengthPrefixed(nals)
        val out = ExposedByteArrayOutputStream(16)
        assertTrue(injector.inject(sample, sample.size, 4, streamStart, timeUs = 0, out = out))
        return out.toByteArray()
    }

    private fun lengthPrefixed(nals: List<ByteArray>, fieldLength: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            for (i in fieldLength - 1 downTo 0) out.write((nal.size ushr (8 * i)) and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }
}
