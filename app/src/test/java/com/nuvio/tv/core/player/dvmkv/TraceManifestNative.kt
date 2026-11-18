package com.nuvio.tv.core.player.dvmkv

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
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu
import com.nuvio.tv.core.player.FetchedManifest
import com.nuvio.tv.core.player.ManifestSourceIdentity
import com.nuvio.tv.core.player.PlaybackManifestNative
import com.nuvio.tv.core.player.toHex

/**
 * The HDR10 → 8.1 playback manifest fixture of ROADMAP DV4.10 (`dv_manifest/hdr10_81`, see its README):
 * a PQ clip muxed by mkvmerge, its `mkvdovi manifest`, `mkvdovi`'s own 8.1 RPUs, its stored AUs and the
 * real matcher's verdicts, which `native/rpu_builder/src/manifest_fixture_tests.rs` checks against the
 * library at its pin.
 */
internal object Hdr10ManifestFixture {
    private fun resource(suffix: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("dv_manifest/hdr10_81/hdr10_81.$suffix")!!.use { it.readBytes() }

    val mkv: ByteArray by lazy { resource("mkv") }
    val manifestJson: ByteArray by lazy { resource("dvmanifest.json") }
    val manifestText: String by lazy { String(manifestJson, Charsets.UTF_8) }

    /** One line of `verdicts.txt`: the matcher's verdict for one AU, in decode order. */
    class Verdict(val pts: Long, val size: Int, val kind: String, val l1: L1, val refresh: Boolean, val shot: Int)

    val verdicts: List<Verdict> by lazy {
        String(resource("verdicts.txt"), Charsets.UTF_8).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split(' ')
                Verdict(f[0].toLong(), f[1].toInt(), f[2], L1(f[3].toInt(), f[4].toInt(), f[5].toInt()), f[6] == "1", f[7].toInt())
            }
    }

    /** Each AU's bytes as the Matroska block stores them (4-byte NAL lengths), in decode order. */
    val aus: List<ByteArray> by lazy {
        val bin = resource("aus.bin")
        var offset = 0
        verdicts.map { verdict -> bin.copyOfRange(offset, offset + verdict.size).also { offset += verdict.size } }
            .also { check(offset == bin.size) { "aus.bin holds more than the table's AUs" } }
    }

    /** `mkvdovi`'s RPU NAL of each picture in presentation order: `7C 01` and its `RPU.bin` record. */
    val rpus: List<ByteArray> by lazy { records(resource("mkvdovi.RPU.bin")).map { byteArrayOf(0x7C, 0x01) + it } }

    private val presentation: List<Long> by lazy { verdicts.map { it.pts }.sorted() }

    /** The presentation rank of the picture at [pts]. */
    fun rank(pts: Long): Int = presentation.binarySearch(pts).also { check(it >= 0) { "no AU at pts $pts" } }

    /**
     * The identity the player reads for [mkv]: a file of one chunk or less is read once, so its head is its
     * tail (`ShotListManifestTest`).
     */
    fun identity(): ManifestSourceIdentity {
        check(mkv.size <= ManifestSourceIdentity.CHUNK_BYTES)
        val digest = ManifestSourceIdentity.sha256(mkv)
        return ManifestSourceIdentity(mkv.size.toLong(), digest, digest)
    }

    fun fetched() = FetchedManifest(identity(), manifestJson)

    /** The NALs of an `RPU.bin` after each `00 00 00 01`, which emulation prevention keeps out of a NAL. */
    private fun records(bin: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 4 <= bin.size) {
            if (bin[i] == 0.toByte() && bin[i + 1] == 0.toByte() && bin[i + 2] == 0.toByte() && bin[i + 3] == 1.toByte()) {
                starts += i + 4
                i += 4
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, start -> bin.copyOfRange(start, starts.getOrNull(n + 1)?.minus(4) ?: bin.size) }
    }
}

/**
 * A stand-in for libnuvio_rpu.so's manifest calls that replays [Hdr10ManifestFixture]: the JVM has no
 * library, and `cargo test` shows that the real one gives these verdicts and RPUs for these AU bytes.
 *
 * - The source and track checks pass only with the values the manifest states, so a pass shows that the
 *   player computes `mkvdovi`'s `lookup_key` and CodecPrivate digest.
 * - [next] answers each AU's verdict by its pts while the AU's bytes are the fixture's and it comes in
 *   decode order; otherwise, and at decode position [rejectAt], the matcher rejects, for good.
 * - [renderShot] gives a shot's RPUs from `mkvdovi`'s: the refresh variant of its first picture and the
 *   other of a later one.
 * - [reset] (a seek) only lifts the decode-order check; these tests do not seek.
 */
internal class TraceManifestNative(private val rejectAt: Int = -1) : PlaybackManifestNative {
    private var next = 0L
    val live = mutableSetOf<Long>()
    val calls = mutableListOf<String>()
    val lookupKeys = mutableListOf<String>()
    val codecPrivateDigests = mutableListOf<String>()

    private class Matcher {
        var read = 0
        var ordered = true
        var rejected: AuVerdict.Rejected? = null
    }

    private val matchers = HashMap<Long, Matcher>()
    private val fixture = Hdr10ManifestFixture
    private val byPts by lazy { fixture.verdicts.withIndex().associateBy { it.value.pts } }

    private fun handle(): Long = (++next).also { live += it }

    private fun release(value: Long): Code {
        if (!live.remove(value)) return Code.UNKNOWN_HANDLE
        matchers.remove(value)
        return Code.OK
    }

    override fun parse(json: ByteArray): Outcome<ManifestHandle> {
        calls += "parse"
        if (!json.contentEquals(fixture.manifestJson)) return Outcome.Failed(Code.MANIFEST)
        return Outcome.Ok(ManifestHandle(handle()))
    }

    override fun checkSource(
        manifest: ManifestHandle,
        sizeBytes: Long,
        headSha256: ByteArray,
        tailSha256: ByteArray,
        lookupKey: ByteArray
    ): Code {
        calls += "checkSource"
        lookupKeys += lookupKey.toHex()
        val source = "\"source\":{\"file_name\":\"hdr10_81.mkv\",\"head_sha256\":\"${headSha256.toHex()}\"," +
            "\"lookup_key\":\"${lookupKey.toHex()}\",\"size_bytes\":$sizeBytes,\"tail_sha256\":\"${tailSha256.toHex()}\"}"
        return if (source in fixture.manifestText) Code.OK else Code.MANIFEST
    }

    override fun checkTrack(
        manifest: ManifestHandle,
        codecPrivateSha256: ByteArray,
        width: Int,
        height: Int,
        timeBaseNum: Int,
        timeBaseDen: Int
    ): Code {
        calls += "checkTrack"
        codecPrivateDigests += codecPrivateSha256.toHex()
        val track = "\"track\":{\"codec\":\"hevc\",\"codec_private_sha256\":\"${codecPrivateSha256.toHex()}\"," +
            "\"container\":\"matroska\",\"height\":$height,\"time_base\":[$timeBaseNum,$timeBaseDen],\"width\":$width}"
        return if (track in fixture.manifestText) Code.OK else Code.MANIFEST
    }

    // The fixture is an 8.1 manifest: the composer check refuses it, the profile check passes it.
    override fun checkComposer(manifest: ManifestHandle, composer: HlgComposer): Code {
        calls += "checkComposer"
        return Code.MANIFEST
    }

    override fun checkProfile81(manifest: ManifestHandle): Code {
        calls += "checkProfile81"
        return Code.OK
    }

    override fun createBuilder(manifest: ManifestHandle): Outcome<Handle> {
        calls += "createBuilder"
        return Outcome.Ok(Handle(handle()))
    }

    override fun renderShot(builder: Handle, l1: L1): Outcome<ShotRpu> {
        calls += "render ${l1.minPq}/${l1.avgPq}/${l1.maxPq}"
        val shot = fixture.verdicts.filter { it.l1 == l1 }
        val first = shot.firstOrNull { it.refresh } ?: return Outcome.Failed(Code.BAD_ARGUMENT)
        val later = shot.firstOrNull { !it.refresh } ?: return Outcome.Failed(Code.BAD_ARGUMENT)
        return Outcome.Ok(ShotRpu(fixture.rpus[fixture.rank(first.pts)], fixture.rpus[fixture.rank(later.pts)]))
    }

    override fun createMatcher(manifest: ManifestHandle): Outcome<MatcherHandle> {
        calls += "createMatcher"
        return Outcome.Ok(MatcherHandle(handle().also { matchers[it] = Matcher() }))
    }

    override fun next(
        matcher: MatcherHandle,
        pts: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
        laced: Boolean
    ): Outcome<AuVerdict> {
        calls += "next $pts"
        val state = matchers[matcher.value] ?: return Outcome.Failed(Code.UNKNOWN_HANDLE)
        state.rejected?.let { return Outcome.Ok(it) }
        val index = state.read++
        fun reject(reason: RejectReason) = Outcome.Ok(AuVerdict.Rejected(reason, index.toLong()).also { state.rejected = it })
        if (index == rejectAt) return reject(RejectReason.CRC)
        val entry = byPts[pts] ?: return reject(RejectReason.UNKNOWN_PTS)
        if (state.ordered && entry.index != index) return reject(RejectReason.ORDER)
        if (laced) return reject(RejectReason.LACED)
        val expected = fixture.aus[entry.index]
        if (length != expected.size) return reject(RejectReason.SIZE)
        if (!data.copyOfRange(offset, offset + length).contentEquals(expected)) return reject(RejectReason.CRC)
        val verdict = entry.value
        check(verdict.kind == "inject") { "the fixture's verdict for pts $pts is ${verdict.kind}" }
        return Outcome.Ok(AuVerdict.Inject(verdict.l1, verdict.refresh, verdict.shot, QualityClass.FULL))
    }

    override fun reset(matcher: MatcherHandle): Code {
        calls += "reset"
        val state = matchers[matcher.value] ?: return Code.UNKNOWN_HANDLE
        state.ordered = false
        return Code.OK
    }

    override fun release(manifest: ManifestHandle) = release(manifest.value)

    override fun release(matcher: MatcherHandle) = release(matcher.value)

    override fun release(builder: Handle) = release(builder.value)
}
