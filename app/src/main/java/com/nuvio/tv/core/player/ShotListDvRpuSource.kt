package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.DoviRpuBuilder.AuVerdict
import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.HlgComposer
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu

/**
 * The shot-list provider of ROADMAP DV4.1 (`docs/dv-conversion/DESIGN.md` 2.8): a converted track takes
 * its L1 from the playback manifest that hdr-analyze's `mkvdovi manifest` measured for the same file. At
 * track init the manifest fetched by `lookup_key` ([manifest]) must pass the source and track checks and
 * the target's own check; then every AU goes through the manifest's matcher ([ShotListTrackRpus]).
 *
 * - Profile 8.4 (HLG): the composer check. [fallback]'s static 8.4 RPU is every AU's fallback, and a track
 *   without a manifest, or one whose checks fail, keeps [fallback]'s prepared source as it is.
 * - Profile 8.1 (PQ, DV4.10), only with [static81]: the profile check. An HDR10+ track ([fallback] Ready)
 *   keeps its own RPUs and is not looked up. Any other PQ track converts only under a manifest that passes
 *   every check, with [static81]'s RPU before the first validated IRAP and after a rejection; without one
 *   it gets [fallback]'s answer and stays native HDR10. Its Format waits for the lookup: track init asks
 *   here before the formats are published. After a drop, a track this source gave the manifest is already
 *   Dolby Vision in this playback and is prepared again with [static81] alone; any other track, and a new
 *   extractor for the same file, stays native. Without [static81] an 8.1 target keeps [fallback]'s answer.
 *
 * Known limits of 8.1: with `debug.nuvio.dv_hdr10plus` 0 the first AU's static SEI is not read
 * ([DolbyVisionMatroskaTransformer.readsSeiStatic]), so [static81] has the container and the hvcC only; an
 * HDR10+ track whose payload is only in its first AU is looked up before that AU is read, so with a
 * manifest it falls back to the static RPU, not to HDR10+.
 */
@UnstableApi
internal class ShotListDvRpuSource(
    private val fallback: DvRpuSource,
    private val composer: HlgComposer,
    private val manifest: ShotListManifests.Pending?,
    private val native: PlaybackManifestNative = PlaybackManifestNative.Library,
    private val static81: DvRpuSource? = null,
) : DvRpuSource {

    // The 8.1 tracks this source gave the manifest ([trackKey]); one source per extractor.
    private val accepted81 = mutableSetOf<String>()

    override fun prepare(target: DvSignallingDecision.Target, facts: DvTrackFacts): DvRpuSource.Prepared {
        val prepared = fallback.prepare(target, facts)
        if (manifest == null) return prepared
        return when (target) {
            DvSignallingDecision.Target.PROFILE_8_4 -> prepare84(prepared, facts, manifest)
            DvSignallingDecision.Target.PROFILE_8_1 ->
                static81?.let { prepare81(prepared, facts, manifest, it) } ?: prepared
        }
    }

    private fun prepare84(
        prepared: DvRpuSource.Prepared,
        facts: DvTrackFacts,
        manifest: ShotListManifests.Pending,
    ): DvRpuSource.Prepared {
        if (prepared !is DvRpuSource.Prepared.Ready) return prepared
        val identity = facts.identity
            ?: return prepared.also { log("the track's identity is not known at track init; no manifest") }
        val opened = open(manifest, identity, DvSignallingDecision.Target.PROFILE_8_4) ?: return prepared
        return DvRpuSource.Prepared.Ready(
            ShotListTrackRpus(opened.session, prepared.rpus, opened.key, onDrop = manifest::markDropped),
            "shot list ${opened.key}, fallback ${prepared.label}",
        )
    }

    private fun prepare81(
        prepared: DvRpuSource.Prepared,
        facts: DvTrackFacts,
        manifest: ShotListManifests.Pending,
        static81: DvRpuSource,
    ): DvRpuSource.Prepared {
        if (prepared is DvRpuSource.Prepared.Ready) return prepared
        val target = DvSignallingDecision.Target.PROFILE_8_1
        val identity = facts.identity
            ?: return prepared.also { log("the track's identity is not known at track init; no manifest") }
        val track = trackKey(identity)
        if (manifest.dropped) {
            if (track !in accepted81) {
                return prepared.also { log("the manifest was dropped in this playback; the PQ track stays native") }
            }
            log("the manifest was dropped in this playback; the track keeps the static 8.1 RPU")
            return static81.prepare(target, facts)
        }
        val opened = open(manifest, identity, target) ?: return prepared
        val static = when (val made = static81.prepare(target, facts)) {
            is DvRpuSource.Prepared.Ready -> made
            is DvRpuSource.Prepared.Unavailable -> {
                opened.session.close()
                log("manifest ${opened.key} not used: no static 8.1 fallback (${made.reason})")
                return made
            }
        }
        accepted81 += track
        return DvRpuSource.Prepared.Ready(
            ShotListTrackRpus(opened.session, static.rpus, opened.key, onDrop = manifest::markDropped),
            "shot list ${opened.key}, fallback ${static.label}",
        )
    }

    private class Opened(val session: PlaybackManifestSession, val key: String)

    /** The manifest's session when it describes this file and track for [target], or null (logged). */
    private fun open(
        manifest: ShotListManifests.Pending,
        identity: DvTrackIdentity,
        target: DvSignallingDecision.Target,
    ): Opened? {
        val fetched = manifest.await() ?: return null
        val key = fetched.identity.lookupKeyHex.take(12)
        val session = when (val opened = PlaybackManifestSession.open(fetched.json, native)) {
            is Outcome.Ok -> opened.value
            is Outcome.Failed -> return null.also { log("manifest $key refused by the parser: ${opened.code}") }
        }
        val refusal = refusal(session, fetched.identity, identity, target)
        if (refusal != null) {
            session.close()
            log("manifest $key not used: $refusal")
            return null
        }
        log("manifest $key accepted for the track, ${identity.width}x${identity.height}")
        return Opened(session, key)
    }

    /** Why the manifest does not describe this file and track, or null when every check passes. */
    private fun refusal(
        session: PlaybackManifestSession,
        source: ManifestSourceIdentity,
        track: DvTrackIdentity,
        target: DvSignallingDecision.Target,
    ): String? {
        val sourceCode = session.checkSource(source.sizeBytes, source.headSha256, source.tailSha256, source.lookupKey)
        if (sourceCode != Code.OK) return "source check $sourceCode"
        val timeBase = timeBase(track.timestampScaleNs) ?: return "TimestampScale ${track.timestampScaleNs} out of range"
        val trackCode = session.checkTrack(
            ManifestSourceIdentity.sha256(track.codecPrivate),
            track.width,
            track.height,
            timeBase.first,
            timeBase.second,
        )
        if (trackCode != Code.OK) return "track check $trackCode"
        when (target) {
            DvSignallingDecision.Target.PROFILE_8_4 -> {
                val composerCode = session.checkComposer(composer)
                if (composerCode != Code.OK) return "composer check $composerCode for $composer"
            }
            DvSignallingDecision.Target.PROFILE_8_1 -> {
                val profileCode = session.checkProfile81()
                if (profileCode != Code.OK) return "profile check $profileCode: not an 8.1 manifest"
            }
        }
        // A builder the library refuses would fail on the first injected AU; refuse it here instead.
        val builder = session.builder()
        if (builder is Outcome.Failed) return "builder ${builder.code}"
        return null
    }

    private fun log(message: String) = android.util.Log.i(TAG, "DV_MANIFEST: $message")

    companion object {
        private const val TAG = "DvShotList"
        private const val NS_PER_SECOND = 1_000_000_000L

        /**
         * `adb shell setprop debug.nuvio.dv_manifest_hdr10 1` (DV4.10): PQ tracks take a playback manifest
         * too ([p81Source]). Off by default; read once per extractor.
         */
        const val HDR10_SWITCH = "debug.nuvio.dv_manifest_hdr10"

        /** [timestampScaleNs] / 10^9 s in lowest terms, as the track check takes it, or null past Int. */
        internal fun timeBase(timestampScaleNs: Long): Pair<Int, Int>? {
            if (timestampScaleNs <= 0) return null
            val divisor = gcd(timestampScaleNs, NS_PER_SECOND)
            val num = timestampScaleNs / divisor
            val den = NS_PER_SECOND / divisor
            if (num > Int.MAX_VALUE) return null
            return num.toInt() to den.toInt()
        }

        /** One track of the file, as far as this source tells tracks apart: its CodecPrivate and size. */
        internal fun trackKey(identity: DvTrackIdentity): String =
            "${ManifestSourceIdentity.sha256(identity.codecPrivate).toHex()} ${identity.width}x${identity.height}"

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

/**
 * The RPUs of a track with a playback manifest (ROADMAP DV4.1). Each AU goes to the manifest's matcher
 * with its raw ticks, its stored bytes and whether its block is laced (`AuInput`, DV4.7):
 *
 * - [AuVerdict.Inject]: the shot's RPU from the manifest's builder, with the matcher's scene refresh;
 * - [AuVerdict.Fallback] (before the first IRAP after a start or a seek, a non-picture AU, a picture
 *   outside the coverage): [fallback]'s RPU;
 * - [AuVerdict.Rejected], or a matcher or builder failure: [fallback]'s RPU for the rest of the playback,
 *   seeks included; [onDrop] keeps the file's manifest dropped when the track is prepared again.
 *
 * An AU whose provider differs from the AU before it in the same generation is marked
 * [AuRpu.providerSwitch], so [ConvertedTrackInjector] sets the scene refresh by `DESIGN.md` 2.2. The first
 * AU after a reset has no AU before it and carries no switch. Owns [session] and [fallback] until
 * [release].
 */
internal class ShotListTrackRpus(
    private val session: PlaybackManifestSession,
    private val fallback: TrackRpus,
    private val name: String,
    private val onDrop: () -> Unit = {},
) : TrackRpus {

    override val first: ShotRpu get() = fallback.first

    // Every shot has its own L1; an 8.4 RPU is far below 64 KiB.
    override val minNalLengthFieldLength: Int get() = maxOf(2, fallback.minNalLengthFieldLength)

    private var dropped = false
    // Whether the previous AU of this generation had the manifest's RPU; null at its start.
    private var previousFromManifest: Boolean? = null
    private var injected = 0L
    private var fellBack = 0L
    private var released = false

    private val shots = object : LinkedHashMap<Int, ShotRpu>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ShotRpu>?) = size > SHOT_CACHE
    }

    override fun next(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int, au: AuInput): AuRpu {
        if (!dropped) {
            when (val outcome = session.next(au.ticks, sample, 0, length, au.laced)) {
                is Outcome.Ok -> when (val verdict = outcome.value) {
                    is AuVerdict.Inject -> {
                        val shot = shot(verdict)
                        if (shot != null) return fromManifest(shot, verdict.sceneRefresh)
                    }
                    AuVerdict.Fallback -> Unit
                    is AuVerdict.Rejected ->
                        drop("rejected (${verdict.reason}) at decode index ${verdict.decodeIndex ?: "?"}, AU ${au.decodeIndex} of the generation")
                }
                // A refused argument leaves the matcher as it was: this AU only keeps the fallback.
                is Outcome.Failed -> if (outcome.code != Code.BAD_ARGUMENT) drop("the matcher failed: ${outcome.code}")
            }
        }
        return fromFallback(sample, length, nalUnitLengthFieldLength, au)
    }

    private fun shot(verdict: AuVerdict.Inject): ShotRpu? {
        shots[verdict.shot]?.let { return it }
        return when (val rendered = session.renderShot(verdict.l1)) {
            is Outcome.Ok -> rendered.value.also { shots[verdict.shot] = it }
            is Outcome.Failed -> null.also { drop("shot ${verdict.shot} could not be rendered: ${rendered.code}") }
        }
    }

    private fun fromManifest(shot: ShotRpu, sceneRefresh: Boolean): AuRpu {
        val switched = previousFromManifest == false
        previousFromManifest = true
        injected++
        return AuRpu(shot, sceneRefresh, providerSwitch = switched)
    }

    private fun fromFallback(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int, au: AuInput): AuRpu {
        val rpu = fallback.next(sample, length, nalUnitLengthFieldLength, au)
        val switched = previousFromManifest == true
        previousFromManifest = false
        fellBack++
        return if (switched) AuRpu(rpu.shot, rpu.sceneRefresh, providerSwitch = true) else rpu
    }

    private fun drop(reason: String) {
        dropped = true
        onDrop()
        log("manifest $name dropped: $reason; the fallback for the rest of the playback")
    }

    override fun onReset() {
        previousFromManifest = null
        if (!dropped) {
            val code = session.reset()
            if (code != Code.OK) drop("the matcher reset failed: $code")
        }
        fallback.onReset()
    }

    override fun release() {
        if (released) return
        released = true
        log("manifest $name: $injected AUs from the manifest, $fellBack from the fallback")
        session.close()
        fallback.release()
    }

    private fun log(message: String) = android.util.Log.i(TAG, "DV_MANIFEST: $message")

    private companion object {
        const val TAG = "DvShotList"
        const val SHOT_CACHE = 32
    }
}
