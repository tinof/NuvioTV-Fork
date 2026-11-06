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
 * A stand-in for libnuvio_rpu.so's manifest calls (ROADMAP DV4.6). Handles come from one counter,
 * as in the library; it records what was called and released, and answers what each test sets.
 */
internal class FakePlaybackManifestNative : PlaybackManifestNative {
    private var next = 0L
    val live = mutableSetOf<Long>()
    val released = mutableListOf<Long>()
    val calls = mutableListOf<String>()
    var parseResult: Code? = null
    var createMatcherResult: Code? = null
    var createBuilderResult: Code? = null
    var renderResult: Code? = null
    val composers = mutableListOf<HlgComposer>()
    var composerResult = Code.OK
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
        composers += composer
        return if (checkResult != Code.OK) checkResult else composerResult
    }

    override fun createBuilder(manifest: ManifestHandle): Outcome<Handle> {
        calls += "createBuilder ${manifest.value}"
        createBuilderResult?.let { return Outcome.Failed(it) }
        return Outcome.Ok(Handle(handle()))
    }

    /** The RPUs of [l1]: the refresh variant is 0x7C 0x01 0x01 then the L1, the other 0x7C 0x01 0x00. */
    override fun renderShot(builder: Handle, l1: L1): Outcome<ShotRpu> {
        calls += "render ${builder.value} ${l1.minPq}/${l1.avgPq}/${l1.maxPq}"
        renderResult?.let { return Outcome.Failed(it) }
        return Outcome.Ok(rpuOf(l1))
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

/** The RPUs [FakePlaybackManifestNative.renderShot] gives for [l1]. */
internal fun rpuOf(l1: L1): ShotRpu {
    fun bytes(refresh: Int) = byteArrayOf(0x7C, 0x01, refresh.toByte(), l1.minPq.toByte(), l1.avgPq.toByte(), l1.maxPq.toByte())
    return ShotRpu(bytes(1), bytes(0))
}

/** The NAL [ShotRpu.writeTo] writes, in its [refresh] variant. */
internal fun ShotRpu.bytes(refresh: Boolean): ByteArray =
    java.io.ByteArrayOutputStream().also { writeTo(it, refresh) }.toByteArray()
