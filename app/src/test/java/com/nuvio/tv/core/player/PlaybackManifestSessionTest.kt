package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.AuVerdict
import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.Handle
import com.nuvio.tv.core.player.DoviRpuBuilder.HlgComposer
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.ManifestHandle
import com.nuvio.tv.core.player.DoviRpuBuilder.MatcherHandle
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.player.DoviRpuBuilder.QualityClass
import com.nuvio.tv.core.player.DoviRpuBuilder.RejectReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stand-in for libnuvio_rpu.so's manifest calls (ROADMAP DV4.6). Handles come from one counter,
 * as in the library; it records what was called and released, and answers what each test sets.
 */
private class FakePlaybackManifestNative : PlaybackManifestNative {
    private var next = 0L
    val live = mutableSetOf<Long>()
    val released = mutableListOf<Long>()
    val calls = mutableListOf<String>()
    var parseResult: Code? = null
    var createMatcherResult: Code? = null
    var createBuilderResult: Code? = null
    var checkResult = Code.OK
    val verdicts = ArrayDeque<Outcome<AuVerdict>>()

    private fun handle(): Long = (++next).also { live += it }

    private fun release(value: Long): Code {
        if (!live.remove(value)) return Code.UNKNOWN_HANDLE
        released += value
        return Code.OK
    }

    override fun parse(json: ByteArray): Outcome<ManifestHandle> {
        calls += "parse"
        parseResult?.let { return Outcome.Failed(it) }
        return Outcome.Ok(ManifestHandle(handle()))
    }

    override fun checkSource(
        manifest: ManifestHandle,
        sizeBytes: Long,
        headSha256: ByteArray,
        tailSha256: ByteArray,
        lookupKey: ByteArray
    ): Code {
        calls += "checkSource ${manifest.value} $sizeBytes"
        return checkResult
    }

    override fun checkTrack(
        manifest: ManifestHandle,
        codecPrivateSha256: ByteArray,
        width: Int,
        height: Int,
        timeBaseNum: Int,
        timeBaseDen: Int
    ): Code {
        calls += "checkTrack ${manifest.value} ${width}x$height $timeBaseNum/$timeBaseDen"
        return checkResult
    }

    override fun checkComposer(manifest: ManifestHandle, composer: HlgComposer): Code {
        calls += "checkComposer ${manifest.value} $composer"
        return checkResult
    }

    override fun createBuilder(manifest: ManifestHandle): Outcome<Handle> {
        calls += "createBuilder ${manifest.value}"
        createBuilderResult?.let { return Outcome.Failed(it) }
        return Outcome.Ok(Handle(handle()))
    }

    override fun createMatcher(manifest: ManifestHandle): Outcome<MatcherHandle> {
        calls += "createMatcher ${manifest.value}"
        createMatcherResult?.let { return Outcome.Failed(it) }
        return Outcome.Ok(MatcherHandle(handle()))
    }

    override fun next(
        matcher: MatcherHandle,
        pts: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
        laced: Boolean
    ): Outcome<AuVerdict> {
        calls += "next ${matcher.value} $pts $offset+$length $laced"
        return verdicts.removeFirstOrNull() ?: Outcome.Ok(AuVerdict.Fallback)
    }

    override fun reset(matcher: MatcherHandle): Code {
        calls += "reset ${matcher.value}"
        return Code.OK
    }

    override fun release(manifest: ManifestHandle) = release(manifest.value)

    override fun release(matcher: MatcherHandle) = release(matcher.value)

    override fun release(builder: Handle) = release(builder.value)
}

class PlaybackManifestSessionTest {

    private val digest = ByteArray(DoviRpuBuilder.DIGEST_BYTES)
    private val inject = AuVerdict.Inject(L1(0, 1000, 3000), sceneRefresh = true, shot = 0, quality = QualityClass.FULL)

    private fun open(native: FakePlaybackManifestNative): PlaybackManifestSession =
        (PlaybackManifestSession.open("{}".toByteArray(), native) as Outcome.Ok).value

    @Test
    fun `open parses and creates the matcher, close releases both once`() {
        val native = FakePlaybackManifestNative()
        val session = open(native)
        assertEquals(listOf("parse", "createMatcher 1"), native.calls)
        assertEquals(setOf(1L, 2L), native.live)

        session.close()
        assertEquals(emptySet<Long>(), native.live)
        assertEquals(listOf(2L, 1L), native.released)
        session.close()
        assertEquals("a second close releases nothing", listOf(2L, 1L), native.released)
    }

    @Test
    fun `a failed open leaves nothing in the table`() {
        val refused = FakePlaybackManifestNative().apply { parseResult = Code.MANIFEST }
        assertEquals(Outcome.Failed(Code.MANIFEST), PlaybackManifestSession.open(ByteArray(0), refused))
        assertEquals(emptySet<Long>(), refused.live)

        val noMatcher = FakePlaybackManifestNative().apply { createMatcherResult = Code.UNAVAILABLE }
        assertEquals(Outcome.Failed(Code.UNAVAILABLE), PlaybackManifestSession.open(ByteArray(0), noMatcher))
        assertEquals(emptySet<Long>(), noMatcher.live)
        assertEquals(listOf(1L), noMatcher.released)
    }

    @Test
    fun `the checks reach the native with the manifest handle`() {
        val native = FakePlaybackManifestNative()
        val session = open(native)
        assertEquals(Code.OK, session.checkSource(1000, digest, digest, digest))
        assertEquals(Code.OK, session.checkTrack(digest, 3840, 2160, 1, 1000))
        assertEquals(Code.OK, session.checkComposer(HlgComposer.BT2100))
        assertEquals(
            listOf("checkSource 1 1000", "checkTrack 1 3840x2160 1/1000", "checkComposer 1 BT2100"),
            native.calls.drop(2)
        )
        native.checkResult = Code.MANIFEST
        assertEquals(Code.MANIFEST, session.checkSource(1000, digest, digest, digest))
        assertEquals(Code.MANIFEST, session.checkComposer(HlgComposer.PRESET))
        session.close()
    }

    @Test
    fun `the builder is created once and released with the session`() {
        val native = FakePlaybackManifestNative()
        val session = open(native)
        val builder = session.builder()
        assertEquals(Outcome.Ok(Handle(3)), builder)
        assertEquals(builder, session.builder())
        assertEquals(1, native.calls.count { it.startsWith("createBuilder") })
        session.close()
        assertEquals(listOf(2L, 3L, 1L), native.released)
        assertEquals(emptySet<Long>(), native.live)

        val failing = FakePlaybackManifestNative().apply { createBuilderResult = Code.RPU }
        val other = open(failing)
        assertEquals(Outcome.Failed(Code.RPU), other.builder())
        failing.createBuilderResult = null
        assertTrue("a failure is not cached", other.builder() is Outcome.Ok)
        other.close()
        assertEquals(emptySet<Long>(), failing.live)
    }

    @Test
    fun `verdicts pass through and a rejection is the native's to keep`() {
        val native = FakePlaybackManifestNative()
        val session = open(native)
        val rejected = AuVerdict.Rejected(RejectReason.SIZE, 3)
        native.verdicts += listOf(Outcome.Ok(inject), Outcome.Ok(AuVerdict.Fallback), Outcome.Ok(rejected))
        val data = ByteArray(16)
        assertEquals(Outcome.Ok(inject), session.next(0, data, 2, 10, laced = false))
        assertEquals(Outcome.Ok(AuVerdict.Fallback), session.next(3, data, 0, 16, laced = false))
        assertEquals(Outcome.Ok(rejected), session.next(1, data, 0, 4, laced = true))
        assertEquals(Code.OK, session.reset())
        assertEquals(
            listOf("next 2 0 2+10 false", "next 2 3 0+16 false", "next 2 1 0+4 true", "reset 2"),
            native.calls.drop(2)
        )
        session.close()
    }

    @Test
    fun `a failed next is sticky, a refused argument is not`() {
        val native = FakePlaybackManifestNative()
        val session = open(native)
        native.verdicts += listOf(
            Outcome.Failed(Code.BAD_ARGUMENT),
            Outcome.Ok(inject),
            Outcome.Failed(Code.JNI_FAILURE),
            Outcome.Ok(inject)
        )
        val data = ByteArray(4)
        assertEquals(Outcome.Failed(Code.BAD_ARGUMENT), session.next(0, data, 0, 4, laced = false))
        assertEquals(Outcome.Ok(inject), session.next(0, data, 0, 4, laced = false))
        assertEquals(Outcome.Failed(Code.JNI_FAILURE), session.next(3, data, 0, 4, laced = false))
        // From here the session answers the failure without asking the matcher.
        val asked = native.calls.size
        assertEquals(Outcome.Failed(Code.JNI_FAILURE), session.next(1, data, 0, 4, laced = false))
        assertEquals(Code.JNI_FAILURE, session.reset())
        assertEquals(asked, native.calls.size)
        session.close()
    }

    @Test
    fun `after close every call is an unknown handle and reaches no native`() {
        val native = FakePlaybackManifestNative()
        val session = open(native)
        native.verdicts += Outcome.Failed(Code.PANIC)
        session.next(0, ByteArray(1), 0, 1, laced = false)
        session.close()
        val asked = native.calls.size
        assertEquals(Code.UNKNOWN_HANDLE, session.checkSource(1000, digest, digest, digest))
        assertEquals(Code.UNKNOWN_HANDLE, session.checkTrack(digest, 3840, 2160, 1, 1000))
        assertEquals(Code.UNKNOWN_HANDLE, session.checkComposer(HlgComposer.BT2100))
        assertEquals(Outcome.Failed(Code.UNKNOWN_HANDLE), session.builder())
        assertEquals(Outcome.Failed(Code.UNKNOWN_HANDLE), session.next(0, ByteArray(1), 0, 1, laced = false))
        assertEquals(Code.UNKNOWN_HANDLE, session.reset())
        assertEquals(asked, native.calls.size)
        assertArrayEquals(longArrayOf(2, 1), native.released.toLongArray())
    }

    @Test
    fun `without the library a session does not open`() {
        assertEquals(Outcome.Failed(Code.UNAVAILABLE), PlaybackManifestSession.open("{}".toByteArray()))
    }
}
