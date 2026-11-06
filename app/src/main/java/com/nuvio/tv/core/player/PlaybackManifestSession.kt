package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.AuVerdict
import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.Handle
import com.nuvio.tv.core.player.DoviRpuBuilder.HlgComposer
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.ManifestHandle
import com.nuvio.tv.core.player.DoviRpuBuilder.MatcherHandle
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu

/**
 * The playback manifest calls of [DoviRpuBuilder] (ROADMAP DV4.6), behind an interface so that JVM
 * tests can stand in for `libnuvio_rpu.so`.
 */
internal interface PlaybackManifestNative {
    fun parse(json: ByteArray): Outcome<ManifestHandle>
    fun checkSource(
        manifest: ManifestHandle,
        sizeBytes: Long,
        headSha256: ByteArray,
        tailSha256: ByteArray,
        lookupKey: ByteArray
    ): Code
    fun checkTrack(
        manifest: ManifestHandle,
        codecPrivateSha256: ByteArray,
        width: Int,
        height: Int,
        timeBaseNum: Int,
        timeBaseDen: Int
    ): Code
    fun checkComposer(manifest: ManifestHandle, composer: HlgComposer): Code
    fun createBuilder(manifest: ManifestHandle): Outcome<Handle>
    fun renderShot(builder: Handle, l1: L1): Outcome<ShotRpu>
    fun createMatcher(manifest: ManifestHandle): Outcome<MatcherHandle>
    fun next(matcher: MatcherHandle, pts: Long, data: ByteArray, offset: Int, length: Int, laced: Boolean): Outcome<AuVerdict>
    fun reset(matcher: MatcherHandle): Code
    fun release(manifest: ManifestHandle): Code
    fun release(matcher: MatcherHandle): Code
    fun release(builder: Handle): Code

    object Library : PlaybackManifestNative {
        override fun parse(json: ByteArray) = DoviRpuBuilder.parseManifest(json)

        override fun checkSource(
            manifest: ManifestHandle,
            sizeBytes: Long,
            headSha256: ByteArray,
            tailSha256: ByteArray,
            lookupKey: ByteArray
        ) = DoviRpuBuilder.checkManifestSource(manifest, sizeBytes, headSha256, tailSha256, lookupKey)

        override fun checkTrack(
            manifest: ManifestHandle,
            codecPrivateSha256: ByteArray,
            width: Int,
            height: Int,
            timeBaseNum: Int,
            timeBaseDen: Int
        ) = DoviRpuBuilder.checkManifestTrack(manifest, codecPrivateSha256, width, height, timeBaseNum, timeBaseDen)

        override fun checkComposer(manifest: ManifestHandle, composer: HlgComposer) =
            DoviRpuBuilder.checkManifestComposer(manifest, composer)

        override fun createBuilder(manifest: ManifestHandle) = DoviRpuBuilder.createManifestBuilder(manifest)

        override fun renderShot(builder: Handle, l1: L1) = DoviRpuBuilder.renderShot(builder, l1)

        override fun createMatcher(manifest: ManifestHandle) = DoviRpuBuilder.createMatcher(manifest)

        override fun next(matcher: MatcherHandle, pts: Long, data: ByteArray, offset: Int, length: Int, laced: Boolean) =
            DoviRpuBuilder.matchAu(matcher, pts, data, offset, length, laced)

        override fun reset(matcher: MatcherHandle) = DoviRpuBuilder.resetMatcher(matcher)

        override fun release(manifest: ManifestHandle) = DoviRpuBuilder.releaseManifest(manifest)

        override fun release(matcher: MatcherHandle) = DoviRpuBuilder.releaseMatcher(matcher)

        override fun release(builder: Handle) = DoviRpuBuilder.release(builder)
    }
}

/**
 * One playback's manifest (ROADMAP DV4.6): the parsed manifest, its AU matcher and, once asked for,
 * the manifest's RPU builder, all released together by [close]. The DV4.1 provider opens one per
 * title after it fetched the manifest by `lookup_key`, runs the checks, and feeds every AU to [next].
 *
 * - A failed [next] is sticky, except a refused argument ([Code.BAD_ARGUMENT]), which leaves the
 *   matcher as it was. Any other failure may come after the matcher moved on without its verdict
 *   reaching here, so the session answers that failure from then on and the caller falls back for
 *   the rest of the playback, as it does for [AuVerdict.Rejected].
 * - After [close] every call fails with [Code.UNKNOWN_HANDLE] and touches no native state.
 * - Every method is synchronized: the extractor's loading thread calls [next] and [reset], the
 *   player's thread may call [close].
 */
internal class PlaybackManifestSession private constructor(
    private val native: PlaybackManifestNative,
    manifest: ManifestHandle,
    matcher: MatcherHandle
) : AutoCloseable {
    private var manifest: ManifestHandle? = manifest
    private var matcher: MatcherHandle? = matcher
    private var builder: Handle? = null
    private var failure: Code? = null

    companion object {
        /** Parses [json] and creates its matcher; nothing stays in the native table on a failure. */
        fun open(
            json: ByteArray,
            native: PlaybackManifestNative = PlaybackManifestNative.Library
        ): Outcome<PlaybackManifestSession> {
            val manifest = when (val parsed = native.parse(json)) {
                is Outcome.Ok -> parsed.value
                is Outcome.Failed -> return parsed
            }
            val matcher = when (val created = native.createMatcher(manifest)) {
                is Outcome.Ok -> created.value
                is Outcome.Failed -> {
                    native.release(manifest)
                    return created
                }
            }
            return Outcome.Ok(PlaybackManifestSession(native, manifest, matcher))
        }
    }

    /** See [DoviRpuBuilder.checkManifestSource]. */
    @Synchronized
    fun checkSource(sizeBytes: Long, headSha256: ByteArray, tailSha256: ByteArray, lookupKey: ByteArray): Code {
        val manifest = manifest ?: return Code.UNKNOWN_HANDLE
        return native.checkSource(manifest, sizeBytes, headSha256, tailSha256, lookupKey)
    }

    /** See [DoviRpuBuilder.checkManifestTrack]. */
    @Synchronized
    fun checkTrack(codecPrivateSha256: ByteArray, width: Int, height: Int, timeBaseNum: Int, timeBaseDen: Int): Code {
        val manifest = manifest ?: return Code.UNKNOWN_HANDLE
        return native.checkTrack(manifest, codecPrivateSha256, width, height, timeBaseNum, timeBaseDen)
    }

    /** See [DoviRpuBuilder.checkManifestComposer]. */
    @Synchronized
    fun checkComposer(composer: HlgComposer): Code {
        val manifest = manifest ?: return Code.UNKNOWN_HANDLE
        return native.checkComposer(manifest, composer)
    }

    /** The manifest's RPU builder, created on the first call and owned by the session. */
    @Synchronized
    fun builder(): Outcome<Handle> {
        builder?.let { return Outcome.Ok(it) }
        val manifest = manifest ?: return Outcome.Failed(Code.UNKNOWN_HANDLE)
        val created = native.createBuilder(manifest)
        if (created is Outcome.Ok) builder = created.value
        return created
    }

    /** One shot's RPUs from the manifest's builder (DV4.1): [l1] with the manifest's `RpuParams`. */
    @Synchronized
    fun renderShot(l1: L1): Outcome<ShotRpu> = when (val builder = builder()) {
        is Outcome.Ok -> native.renderShot(builder.value, l1)
        is Outcome.Failed -> builder
    }

    /** See [DoviRpuBuilder.matchAu]; a refused argument ([Code.BAD_ARGUMENT]) is not sticky. */
    @Synchronized
    fun next(pts: Long, data: ByteArray, offset: Int, length: Int, laced: Boolean): Outcome<AuVerdict> {
        val matcher = matcher ?: return Outcome.Failed(Code.UNKNOWN_HANDLE)
        failure?.let { return Outcome.Failed(it) }
        val verdict = native.next(matcher, pts, data, offset, length, laced)
        if (verdict is Outcome.Failed && verdict.code != Code.BAD_ARGUMENT) failure = verdict.code
        return verdict
    }

    /** After a seek or a flush. A rejection, and a sticky failure, stay. */
    @Synchronized
    fun reset(): Code {
        val matcher = matcher ?: return Code.UNKNOWN_HANDLE
        failure?.let { return it }
        return native.reset(matcher)
    }

    /** Releases the matcher, the builder and the manifest; a second call does nothing. */
    @Synchronized
    override fun close() {
        matcher?.let { native.release(it) }
        builder?.let { native.release(it) }
        manifest?.let { native.release(it) }
        matcher = null
        builder = null
        manifest = null
    }
}
