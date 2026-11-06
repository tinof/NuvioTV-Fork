package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.AuVerdict
import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.HlgComposer
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.player.DoviRpuBuilder.QualityClass
import com.nuvio.tv.core.player.DoviRpuBuilder.RejectReason
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shot-list provider of ROADMAP DV4.1 on a stand-in for libnuvio_rpu.so: which manifest a track
 * takes at init, and what each AU gets from the matcher's verdicts. The matcher's own checks (pts,
 * size, CRC, order) are hdr-analyze's (P15.2, P15.3); here they arrive as verdicts.
 */
class ShotListDvRpuSourceTest {

    private val p84 = DvSignallingDecision.Target.PROFILE_8_4
    private val p81 = DvSignallingDecision.Target.PROFILE_8_1
    private val staticRpu = ShotRpu(byteArrayOf(0x7C, 0x01, 0x51), byteArrayOf(0x7C, 0x01, 0x50))
    private val identity = DvTrackIdentity(byteArrayOf(1, 2, 3), 3840, 2160, 1_000_000)
    private val facts = DvTrackFacts(identity = identity)
    private val fileIdentity = ManifestSourceIdentity(5, ByteArray(32) { 1 }, ByteArray(32) { 2 })
    private val manifest = FetchedManifest(fileIdentity, "{}".toByteArray())

    /** The static fallback: one RPU, and it records resets and the release. */
    private inner class Fallback : TrackRpus {
        var resets = 0
        var released = 0
        override val first = staticRpu
        override fun next(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int, au: AuInput) = AuRpu(staticRpu, false)
        override fun onReset() { resets++ }
        override fun release() { released++ }
    }

    private val fallbackRpus = Fallback()
    private val fallback = DvRpuSource { target, _ ->
        if (target == p84) DvRpuSource.Prepared.Ready(fallbackRpus, "static 8.4") else DvRpuSource.Prepared.Unavailable("no static 8.1")
    }

    private fun pending(fetched: FetchedManifest? = manifest) =
        ShotListManifests.Pending(CompletableFuture.completedFuture(fetched), waitMs = 0)

    private fun source(native: FakePlaybackManifestNative, fetched: FetchedManifest? = manifest, composer: HlgComposer = HlgComposer.BT2100) =
        ShotListDvRpuSource(fallback, composer, pending(fetched), native)

    private fun inject(min: Int, refresh: Boolean = false, shot: Int = min) =
        Outcome.Ok(AuVerdict.Inject(L1(min, min + 1, min + 2), refresh, shot, QualityClass.FULL))

    private fun au(ticks: Long, laceCount: Int = 1, index: Long = 0) = AuInput(ticks * 1000, ticks, laceCount, index)

    private fun shotList(native: FakePlaybackManifestNative): ShotListTrackRpus {
        val prepared = source(native).prepare(p84, facts) as DvRpuSource.Prepared.Ready
        return prepared.rpus as ShotListTrackRpus
    }

    private fun next(rpus: TrackRpus, au: AuInput, sample: ByteArray = byteArrayOf(9, 9, 9)) = rpus.next(sample, sample.size, 4, au)

    @Test
    fun `a manifest that passes every check gives the track the shot list over its static fallback`() {
        val native = FakePlaybackManifestNative()
        val prepared = source(native).prepare(p84, facts) as DvRpuSource.Prepared.Ready
        assertTrue(prepared.rpus is ShotListTrackRpus)
        assertEquals("shot list ${fileIdentity.lookupKeyHex.take(12)}, fallback static 8.4", prepared.label)
        assertSame(staticRpu, prepared.rpus.first)
        assertEquals(2, prepared.rpus.minNalLengthFieldLength)
        assertEquals(
            listOf("parse", "createMatcher 1", "checkSource 1 5", "checkTrack 1 3840x2160 1/1000", "checkComposer 1 BT2100", "createBuilder 1"),
            native.calls,
        )
    }

    @Test
    fun `a different encode of the same title fails the source or track check and keeps the static fallback`() {
        val native = FakePlaybackManifestNative().apply { checkResult = Code.MANIFEST }
        val prepared = source(native).prepare(p84, facts) as DvRpuSource.Prepared.Ready
        assertSame(fallbackRpus, prepared.rpus)
        assertEquals("static 8.4", prepared.label)
        assertTrue("every handle released", native.live.isEmpty())
    }

    @Test
    fun `a manifest for another composer keeps the static fallback`() {
        val native = FakePlaybackManifestNative().apply { composerResult = Code.MANIFEST }
        val prepared = source(native, composer = HlgComposer.CANARY).prepare(p84, facts) as DvRpuSource.Prepared.Ready
        assertSame(fallbackRpus, prepared.rpus)
        assertEquals(listOf(HlgComposer.CANARY), native.composers)
        assertTrue(native.live.isEmpty())
    }

    @Test
    fun `no manifest, a parser refusal, a refused builder or an unknown track identity keep the static fallback`() {
        val cases = listOf(
            FakePlaybackManifestNative() to null,
            FakePlaybackManifestNative().apply { parseResult = Code.MANIFEST } to manifest,
            FakePlaybackManifestNative().apply { createBuilderResult = Code.MANIFEST } to manifest,
        )
        for ((native, fetched) in cases) {
            val prepared = source(native, fetched).prepare(p84, facts) as DvRpuSource.Prepared.Ready
            assertSame(fallbackRpus, prepared.rpus)
            assertTrue(native.live.isEmpty())
        }
        val native = FakePlaybackManifestNative()
        val prepared = source(native).prepare(p84, DvTrackFacts()) as DvRpuSource.Prepared.Ready
        assertSame(fallbackRpus, prepared.rpus)
        assertTrue("no manifest call without an identity", native.calls.isEmpty())
    }

    @Test
    fun `an 8_1 target is the fallback's answer and the manifest is not opened`() {
        val native = FakePlaybackManifestNative()
        val prepared = source(native).prepare(p81, facts)
        assertTrue(prepared is DvRpuSource.Prepared.Unavailable)
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun `the time base is TimestampScale over a second in lowest terms`() {
        assertEquals(1 to 1000, ShotListDvRpuSource.timeBase(1_000_000))
        assertEquals(11_111 to 1_000_000_000, ShotListDvRpuSource.timeBase(11_111))
        assertEquals(1 to 1, ShotListDvRpuSource.timeBase(1_000_000_000))
        assertEquals(null, ShotListDvRpuSource.timeBase(0))
        assertEquals(null, ShotListDvRpuSource.timeBase(1_000_000_000L * Int.MAX_VALUE + 1_000_000_000L))
    }

    @Test
    fun `each AU goes to the matcher with its raw ticks, its stored bytes and whether its block is laced`() {
        val native = FakePlaybackManifestNative()
        val rpus = shotList(native)
        native.calls.clear()
        next(rpus, au(1001, laceCount = 3), byteArrayOf(1, 2, 3, 4))
        assertEquals(listOf("next 2 1001 0+4 true"), native.calls)
    }

    @Test
    fun `injected AUs get their shot's RPU and the matcher's scene refresh, each shot rendered once`() {
        val native = FakePlaybackManifestNative()
        val rpus = shotList(native)
        native.verdicts += listOf(inject(10, refresh = true), inject(10), inject(20, refresh = true))
        val a = next(rpus, au(0))
        val b = next(rpus, au(40))
        val c = next(rpus, au(80))
        assertArrayEquals(rpuOf(L1(10, 11, 12)).bytes(true), a.shot.bytes(true))
        assertTrue(a.sceneRefresh)
        assertFalse(b.sceneRefresh)
        assertSame(a.shot, b.shot)
        assertArrayEquals(rpuOf(L1(20, 21, 22)).bytes(false), c.shot.bytes(false))
        assertTrue(c.sceneRefresh)
        assertEquals(2, native.calls.count { it.startsWith("render") })
        assertFalse(a.providerSwitch || b.providerSwitch || c.providerSwitch)
    }

    @Test
    fun `AUs before the first IRAP keep the fallback, and the first injected AU after them is a provider switch`() {
        val native = FakePlaybackManifestNative()
        val rpus = shotList(native)
        native.verdicts += listOf(Outcome.Ok(AuVerdict.Fallback), inject(10), inject(10))
        val before = next(rpus, au(0))
        val irap = next(rpus, au(40))
        val after = next(rpus, au(80))
        assertSame(staticRpu, before.shot)
        assertFalse(before.providerSwitch)
        assertTrue(irap.providerSwitch)
        assertFalse(after.providerSwitch)
    }

    @Test
    fun `a different encode rejected by the matcher mid-stream gets the fallback from that AU on, seeks included`() {
        val native = FakePlaybackManifestNative()
        val rpus = shotList(native)
        native.verdicts += listOf(inject(10, refresh = true), inject(10), Outcome.Ok(AuVerdict.Rejected(RejectReason.SIZE, 2)), inject(10))
        next(rpus, au(0))
        next(rpus, au(40))
        val rejected = next(rpus, au(80))
        val later = next(rpus, au(120))
        assertSame(staticRpu, rejected.shot)
        assertTrue("the first fallback AU is a provider switch", rejected.providerSwitch)
        assertSame(staticRpu, later.shot)
        assertFalse(later.providerSwitch)
        val nexts = native.calls.count { it.startsWith("next") }
        assertEquals("the matcher is not asked again", 3, nexts)
        rpus.onReset()
        assertSame(staticRpu, next(rpus, au(0)).shot)
        assertFalse(native.calls.any { it.startsWith("reset") })
        assertEquals(3, native.calls.count { it.startsWith("next") })
    }

    @Test
    fun `a manifest the matcher rejected stays dropped when the track is prepared again, also by a new extractor`() {
        val native = FakePlaybackManifestNative()
        val fetch = pending()
        val source = ShotListDvRpuSource(fallback, HlgComposer.BT2100, fetch, native)
        val first = (source.prepare(p84, facts) as DvRpuSource.Prepared.Ready).rpus
        native.verdicts += Outcome.Ok(AuVerdict.Rejected(RejectReason.CRC, 4))
        next(first, au(0))
        first.release()
        // Tracks read a second time (Tracks behind the Clusters) prepare the same track again.
        assertSame(fallbackRpus, (source.prepare(p84, facts) as DvRpuSource.Prepared.Ready).rpus)
        val another = ShotListDvRpuSource(fallback, HlgComposer.BT2100, fetch, native)
        assertSame(fallbackRpus, (another.prepare(p84, facts) as DvRpuSource.Prepared.Ready).rpus)
        assertTrue(native.live.isEmpty())
    }

    @Test
    fun `a matcher failure or a shot that cannot be rendered drops the manifest like a rejection`() {
        val failing = FakePlaybackManifestNative()
        val a = shotList(failing)
        failing.verdicts += listOf(inject(10), Outcome.Failed(Code.UNKNOWN), inject(10))
        next(a, au(0))
        assertTrue(next(a, au(40)).providerSwitch)
        assertSame(staticRpu, next(a, au(80)).shot)

        val unrenderable = FakePlaybackManifestNative()
        val b = shotList(unrenderable)
        unrenderable.renderResult = Code.UNKNOWN
        unrenderable.verdicts += listOf(inject(10), inject(10))
        assertSame(staticRpu, next(b, au(0)).shot)
        assertSame(staticRpu, next(b, au(40)).shot)
        assertEquals(1, unrenderable.calls.count { it.startsWith("next") })
    }

    @Test
    fun `a refused argument keeps the fallback for that AU only`() {
        val native = FakePlaybackManifestNative()
        val rpus = shotList(native)
        native.verdicts += listOf(Outcome.Failed(Code.BAD_ARGUMENT), inject(10))
        assertSame(staticRpu, next(rpus, au(0)).shot)
        assertArrayEquals(rpuOf(L1(10, 11, 12)).bytes(false), next(rpus, au(40)).shot.bytes(false))
    }

    @Test
    fun `a seek resets the matcher and the fallback, and the first AU after it is no switch`() {
        val native = FakePlaybackManifestNative()
        val rpus = shotList(native)
        native.verdicts += listOf(inject(10), inject(20))
        next(rpus, au(0))
        rpus.onReset()
        assertEquals(1, native.calls.count { it.startsWith("reset") })
        assertEquals(1, fallbackRpus.resets)
        assertFalse(next(rpus, au(400)).providerSwitch)
    }

    @Test
    fun `release frees the manifest, its matcher and builder, and the fallback, once`() {
        val native = FakePlaybackManifestNative()
        val rpus = shotList(native)
        rpus.release()
        rpus.release()
        assertTrue(native.live.isEmpty())
        assertEquals(3, native.released.size)
        assertEquals(1, fallbackRpus.released)
    }
}
