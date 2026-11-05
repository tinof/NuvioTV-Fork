package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.AuVerdict
import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.CmVersion
import com.nuvio.tv.core.player.DoviRpuBuilder.Handle
import com.nuvio.tv.core.player.DoviRpuBuilder.HlgComposer
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.Level5
import com.nuvio.tv.core.player.DoviRpuBuilder.Level6
import com.nuvio.tv.core.player.DoviRpuBuilder.ManifestHandle
import com.nuvio.tv.core.player.DoviRpuBuilder.MatcherHandle
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.player.DoviRpuBuilder.Params
import com.nuvio.tv.core.player.DoviRpuBuilder.PeakSource
import com.nuvio.tv.core.player.DoviRpuBuilder.Profile
import com.nuvio.tv.core.player.DoviRpuBuilder.QualityClass
import com.nuvio.tv.core.player.DoviRpuBuilder.RejectReason
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu
import com.nuvio.tv.core.player.DoviRpuBuilder.SourceRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The JVM has no libnuvio_rpu.so, so these tests cover the paths without the library and the
// logic on the Kotlin side; the library itself is tested by `cargo test` in native/rpu_builder.
class DoviRpuBuilderTest {

    private val unavailable = Outcome.Failed(Code.UNAVAILABLE)
    private val badArgument = Outcome.Failed(Code.BAD_ARGUMENT)
    private val hdr10Plus = Params(
        profile = Profile.P81(SourceRange(7, 3079)),
        cmVersion = CmVersion.V40,
        l1AvgCmVersion = CmVersion.V40,
        level6 = Level6(1000, 1, 1000, 400)
    )

    @Test
    fun `without the library every call returns its unavailable result`() {
        assertFalse(DoviRpuBuilder.isAvailable())
        assertFalse(DoviRpuBuilder.isLibraryLoaded)
        assertNull(DoviRpuBuilder.versionOrNull())
        for (composer in HlgComposer.entries) {
            assertEquals(unavailable, DoviRpuBuilder.createStatic84(composer))
        }
        assertEquals(unavailable, DoviRpuBuilder.create(hdr10Plus))
        assertEquals(
            unavailable,
            DoviRpuBuilder.create(Params(Profile.P84(HlgComposer.BT2100), CmVersion.V40, CmVersion.V29))
        )
        assertEquals(unavailable, DoviRpuBuilder.static84L1())
        assertEquals(unavailable, DoviRpuBuilder.renderShot(Handle(1), L1(0, 1283, 3079)))
        assertEquals(
            unavailable,
            DoviRpuBuilder.hdr10PlusL1(byteArrayOf(0xB5.toByte()), PeakSource.HISTOGRAM, CmVersion.V40)
        )
        assertEquals(
            unavailable,
            DoviRpuBuilder.hdr10PlusFrame(byteArrayOf(0xB5.toByte()), PeakSource.HISTOGRAM, CmVersion.V40)
        )
        assertNull(DoviRpuBuilder.sourceRange81(0.0001, 1000.0))
        // L9 primaries outside 0..2 are refused before the library is asked.
        assertEquals(badArgument, DoviRpuBuilder.create(hdr10Plus.copy(sourcePrimaryIndex = 3)))
        assertEquals(Code.UNAVAILABLE, DoviRpuBuilder.release(Handle(1)))
    }

    @Test
    fun `an HDR10+ frame unpacks L1 and the two key halves`() {
        val frame = DoviRpuBuilder.hdr10PlusFrameOf(intArrayOf(0, 1229, 3079, -1, -2, 0x7FFF_FFFF, 1))
        assertEquals(L1(0, 1229, 3079), frame.l1)
        // The low half is unsigned: -2 is 0xFFFF_FFFE, not a sign extension into the high half.
        assertEquals(DoviRpuBuilder.SceneKey(-2L, 0x7FFF_FFFF_0000_0001L), frame.key)
        assertEquals(
            DoviRpuBuilder.hdr10PlusFrameOf(intArrayOf(5, 5, 5, 1, 2, 3, 4)).key,
            DoviRpuBuilder.hdr10PlusFrameOf(intArrayOf(9, 9, 9, 1, 2, 3, 4)).key
        )
    }

    @Test
    fun `codes match the library's numbers`() {
        val native = mapOf(
            0 to Code.OK,
            1 to Code.INVALID_COMPOSER,
            2 to Code.BLOCK_LEVEL,
            3 to Code.OUT_OF_RANGE,
            4 to Code.NOT_HDR10_PLUS,
            5 to Code.HDR10_PLUS_PARSE,
            6 to Code.HDR10_PLUS_NO_PEAK,
            7 to Code.RPU,
            8 to Code.MANIFEST,
            20 to Code.BAD_ARGUMENT,
            21 to Code.UNKNOWN_HANDLE,
            22 to Code.JNI_FAILURE,
            99 to Code.PANIC
        )
        for ((value, code) in native) {
            assertEquals(code, Code.fromNative(value))
            assertEquals(value, code.value)
        }
        // The Kotlin-only codes never come from a number, nor do numbers the library lacks.
        for (value in listOf(-1, -2, 9, 19, 23, 98, 100, Int.MIN_VALUE)) {
            assertEquals("$value", Code.UNKNOWN, Code.fromNative(value))
        }
        assertEquals(native.size + 2, Code.entries.size)
    }

    @Test
    fun `a handle or a negated code becomes an outcome`() {
        assertEquals(Outcome.Ok(Handle(1)), DoviRpuBuilder.handleOutcome(1))
        assertEquals(Outcome.Ok(Handle(Long.MAX_VALUE)), DoviRpuBuilder.handleOutcome(Long.MAX_VALUE))
        assertEquals(Outcome.Failed(Code.OUT_OF_RANGE), DoviRpuBuilder.handleOutcome(-3))
        assertEquals(Outcome.Failed(Code.BAD_ARGUMENT), DoviRpuBuilder.handleOutcome(-20))
        assertEquals(Outcome.Failed(Code.PANIC), DoviRpuBuilder.handleOutcome(-99))
        assertEquals(unavailable, DoviRpuBuilder.handleOutcome(null))
        assertEquals(Outcome.Failed(Code.UNKNOWN), DoviRpuBuilder.handleOutcome(0))
        assertEquals(Outcome.Failed(Code.UNKNOWN), DoviRpuBuilder.handleOutcome(-50))
        assertEquals(Outcome.Failed(Code.UNKNOWN), DoviRpuBuilder.handleOutcome(Long.MIN_VALUE))
    }

    @Test
    fun `arguments that do not fit their field are refused before the library`() {
        assertEquals(badArgument, DoviRpuBuilder.create(hdr10Plus.copy(level6 = Level6(1000, -1, 1000, 400))))
        assertEquals(
            badArgument,
            DoviRpuBuilder.create(hdr10Plus.copy(profile = Profile.P81(SourceRange(-1, 3079))))
        )
        assertEquals(
            badArgument,
            DoviRpuBuilder.create(hdr10Plus.copy(profile = Profile.P81(SourceRange(0, 65_536))))
        )
        assertEquals(badArgument, DoviRpuBuilder.create(hdr10Plus.copy(level5 = Level5(0, 0, -1, 0))))
        assertEquals(badArgument, DoviRpuBuilder.create(hdr10Plus.copy(level5 = Level5(0, 65_536, 0, 0))))
        assertEquals(badArgument, DoviRpuBuilder.renderShot(Handle(1), L1(-1, 1283, 3079)))
        assertEquals(badArgument, DoviRpuBuilder.renderShot(Handle(1), L1(0, 65_536, 3079)))
    }

    @Test
    fun `values the RPU cannot carry are left to the library`() {
        // They fit the field types: the library answers OUT_OF_RANGE, or clamps L1.
        assertTrue(DoviRpuBuilder.isValid(hdr10Plus.copy(level6 = Level6(10_001, 50, 65_535, 400))))
        assertTrue(DoviRpuBuilder.isValid(hdr10Plus.copy(profile = Profile.P81(SourceRange(3079, 62)))))
        assertTrue(DoviRpuBuilder.isValid(hdr10Plus.copy(level5 = Level5(65_535, 0, 0, 0))))
        assertTrue(DoviRpuBuilder.isValid(L1(20, 500, 65_535)))
        assertTrue(DoviRpuBuilder.isValid(Params(Profile.P84(HlgComposer.CANARY), CmVersion.V29, CmVersion.V29)))
    }

    @Test
    fun `array arguments keep the library's field order`() {
        assertArrayEquals(
            intArrayOf(1000, 50, 997, 91),
            DoviRpuBuilder.level6Args(
                Level6(
                    maxDisplayMasteringLuminance = 1000,
                    minDisplayMasteringLuminance = 50,
                    maxContentLightLevel = 997,
                    maxFrameAverageLightLevel = 91
                )
            )
        )
        assertArrayEquals(
            intArrayOf(0, 4, 140, 136),
            DoviRpuBuilder.level5Args(Level5(left = 0, right = 4, top = 140, bottom = 136))
        )
        assertArrayEquals(intArrayOf(7, 3079), DoviRpuBuilder.sourceRangeArgs(SourceRange(7, 3079)))
        assertEquals(listOf(0, 1, 2), HlgComposer.entries.map { it.value })
        assertEquals(listOf(29, 40), CmVersion.entries.map { it.value })
        assertEquals(listOf(0, 1, 2, 3), PeakSource.entries.map { it.value })
    }

    @Test
    fun `a shot hands out copies of its NALs`() {
        val refresh = byteArrayOf(0x7C, 0x01, 1, 2, 3)
        val held = byteArrayOf(0x7C, 0x01, 4, 5)
        val shot = ShotRpu(refresh, held)
        assertEquals(5, shot.size(sceneRefresh = true))
        assertEquals(4, shot.size(sceneRefresh = false))

        val copy = shot.toByteArray(sceneRefresh = true)
        copy[2] = 9
        assertArrayEquals(byteArrayOf(0x7C, 0x01, 1, 2, 3), shot.toByteArray(sceneRefresh = true))

        val destination = ByteArray(7)
        assertEquals(4, shot.copyInto(destination, 3, sceneRefresh = false))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0x7C, 0x01, 4, 5), destination)
        assertEquals(-1, shot.copyInto(destination, 3, sceneRefresh = true))
        assertEquals(-1, shot.copyInto(destination, -1, sceneRefresh = false))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0x7C, 0x01, 4, 5), destination)
    }

    private val digest = ByteArray(DoviRpuBuilder.DIGEST_BYTES)

    @Test
    fun `without the library the manifest calls return their unavailable result`() {
        val manifest = ManifestHandle(1)
        val matcher = MatcherHandle(2)
        assertEquals(unavailable, DoviRpuBuilder.parseManifest("{}".toByteArray()))
        assertEquals(Code.UNAVAILABLE, DoviRpuBuilder.checkManifestSource(manifest, 1000, digest, digest, digest))
        assertEquals(Code.UNAVAILABLE, DoviRpuBuilder.checkManifestTrack(manifest, digest, 3840, 2160, 1, 1000))
        assertEquals(Code.UNAVAILABLE, DoviRpuBuilder.checkManifestComposer(manifest, HlgComposer.BT2100))
        assertEquals(unavailable, DoviRpuBuilder.createManifestBuilder(manifest))
        assertEquals(unavailable, DoviRpuBuilder.createMatcher(manifest))
        assertEquals(unavailable, DoviRpuBuilder.matchAu(matcher, 0, ByteArray(4), 0, 4, laced = false))
        assertEquals(Code.UNAVAILABLE, DoviRpuBuilder.resetMatcher(matcher))
        assertEquals(Code.UNAVAILABLE, DoviRpuBuilder.releaseManifest(manifest))
        assertEquals(Code.UNAVAILABLE, DoviRpuBuilder.releaseMatcher(matcher))
    }

    @Test
    fun `manifest arguments that do not fit are refused before the library`() {
        val manifest = ManifestHandle(1)
        val short = ByteArray(31)
        val long = ByteArray(33)
        assertEquals(Code.BAD_ARGUMENT, DoviRpuBuilder.checkManifestSource(manifest, -1, digest, digest, digest))
        assertEquals(Code.BAD_ARGUMENT, DoviRpuBuilder.checkManifestSource(manifest, 1000, short, digest, digest))
        assertEquals(Code.BAD_ARGUMENT, DoviRpuBuilder.checkManifestSource(manifest, 1000, digest, long, digest))
        assertEquals(Code.BAD_ARGUMENT, DoviRpuBuilder.checkManifestSource(manifest, 1000, digest, digest, ByteArray(0)))
        assertEquals(Code.BAD_ARGUMENT, DoviRpuBuilder.checkManifestTrack(manifest, short, 3840, 2160, 1, 1000))
        assertEquals(Code.BAD_ARGUMENT, DoviRpuBuilder.checkManifestTrack(manifest, digest, -1, 2160, 1, 1000))
        assertEquals(Code.BAD_ARGUMENT, DoviRpuBuilder.checkManifestTrack(manifest, digest, 3840, -1, 1, 1000))
        assertEquals(badArgument, DoviRpuBuilder.parseManifest(ByteArray(DoviRpuBuilder.MAX_MANIFEST_BYTES + 1)))
        val matcher = MatcherHandle(2)
        val data = ByteArray(10)
        for ((offset, length) in listOf(-1 to 2, 0 to -1, 9 to 2, 11 to 0, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertEquals(
                "$offset+$length",
                badArgument,
                DoviRpuBuilder.matchAu(matcher, 0, data, offset, length, laced = false)
            )
        }
    }

    @Test
    fun `a verdict unpacks from the library's fields`() {
        assertEquals(
            AuVerdict.Inject(L1(0, 1000, 3000), sceneRefresh = true, shot = 0, quality = QualityClass.FULL),
            DoviRpuBuilder.auVerdictOf(longArrayOf(0, 0, 1000, 3000, 1, 0, 0, 0, -1))
        )
        assertEquals(
            AuVerdict.Inject(L1(10, 1500, 4095), sceneRefresh = false, shot = 41, quality = QualityClass.APPROXIMATE),
            DoviRpuBuilder.auVerdictOf(longArrayOf(0, 10, 1500, 4095, 0, 41, 1, 0, -1))
        )
        assertEquals(AuVerdict.Fallback, DoviRpuBuilder.auVerdictOf(longArrayOf(1, 0, 0, 0, 0, 0, 0, 0, -1)))
        assertEquals(
            AuVerdict.Rejected(RejectReason.ORDER, 17),
            DoviRpuBuilder.auVerdictOf(longArrayOf(2, 0, 0, 0, 0, 0, 0, 4, 17))
        )
        assertEquals(
            AuVerdict.Rejected(RejectReason.UNKNOWN_PTS, null),
            DoviRpuBuilder.auVerdictOf(longArrayOf(2, 0, 0, 0, 0, 0, 0, 1, -1))
        )
        // A reason the library should not return still rejects.
        assertEquals(
            AuVerdict.Rejected(RejectReason.UNKNOWN, null),
            DoviRpuBuilder.auVerdictOf(longArrayOf(2, 0, 0, 0, 0, 0, 0, 7, -1))
        )
    }

    @Test
    fun `verdict fields that make no sense are null`() {
        val malformed = listOf(
            longArrayOf(0, 0, 1000, 3000, 1, 0, 0, 0),
            longArrayOf(3, 0, 0, 0, 0, 0, 0, 0, -1),
            longArrayOf(-1, 0, 0, 0, 0, 0, 0, 0, -1),
            longArrayOf(0, -1, 1000, 3000, 1, 0, 0, 0, -1),
            longArrayOf(0, 0, 65_536, 3000, 1, 0, 0, 0, -1),
            longArrayOf(0, 0, 1000, 3000, 2, 0, 0, 0, -1),
            longArrayOf(0, 0, 1000, 3000, 1, -1, 0, 0, -1),
            longArrayOf(0, 0, 1000, 3000, 1, Int.MAX_VALUE + 1L, 0, 0, -1),
            longArrayOf(0, 0, 1000, 3000, 1, 0, 2, 0, -1)
        )
        for (fields in malformed) {
            assertNull(fields.joinToString(), DoviRpuBuilder.auVerdictOf(fields))
        }
    }

    @Test
    fun `reject reasons match the crate's codes`() {
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6),
            RejectReason.entries.filter { it != RejectReason.UNKNOWN }.map { it.value }
        )
        for (reason in RejectReason.entries.filter { it != RejectReason.UNKNOWN }) {
            assertEquals(reason, RejectReason.fromNative(reason.value.toLong()))
        }
        for (value in listOf(0L, 7L, -2L, Long.MAX_VALUE)) {
            assertEquals("$value", RejectReason.UNKNOWN, RejectReason.fromNative(value))
        }
    }
}
