package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.DoviRpuBuilder.AuVerdict
import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.HlgComposer
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu

/**
 * The shot-list provider of ROADMAP DV4.1 (`docs/dv-conversion/DESIGN.md` 2.8): a converted HLG track
 * takes its L1 from the playback manifest that hdr-analyze's `mkvdovi manifest` measured for the same
 * file, and keeps [fallback]'s RPUs wherever the manifest gives none.
 *
 * At track init the manifest fetched by `lookup_key` ([manifest]) must pass the source, track and
 * composer checks; then every AU goes through the manifest's matcher ([ShotListTrackRpus]). A track
 * without a manifest, one whose checks fail, or a Profile 8.1 target keeps [fallback]'s prepared
 * source as it is: 8.1 needs a fallback RPU before the first validated IRAP and after a rejection,
 * which waits for DV3.3.
 */
@UnstableApi
internal class ShotListDvRpuSource(
    private val fallback: DvRpuSource,
    private val composer: HlgComposer,
    private val manifest: ShotListManifests.Pending?,
    private val native: PlaybackManifestNative = PlaybackManifestNative.Library,
) : DvRpuSource {

    override fun prepare(target: DvSignallingDecision.Target, facts: DvTrackFacts): DvRpuSource.Prepared {
        val prepared = fallback.prepare(target, facts)
        if (manifest == null || target != DvSignallingDecision.Target.PROFILE_8_4 || prepared !is DvRpuSource.Prepared.Ready) {
            return prepared
        }
        val identity = facts.identity
            ?: return prepared.also { log("the track's identity is not known at track init; no manifest") }
        val fetched = manifest.await() ?: return prepared
        val key = fetched.identity.lookupKeyHex.take(12)
        val session = when (val opened = PlaybackManifestSession.open(fetched.json, native)) {
            is Outcome.Ok -> opened.value
            is Outcome.Failed -> return prepared.also { log("manifest $key refused by the parser: ${opened.code}") }
        }
        val refusal = refusal(session, fetched.identity, identity)
        if (refusal != null) {
            session.close()
            log("manifest $key not used: $refusal")
            return prepared
        }
        log("manifest $key accepted for the track, ${identity.width}x${identity.height}")
        return DvRpuSource.Prepared.Ready(
            ShotListTrackRpus(session, prepared.rpus, key, onDrop = manifest::markDropped),
            "shot list $key, fallback ${prepared.label}",
        )
    }

    /** Why the manifest does not describe this file and track, or null when every check passes. */
    private fun refusal(session: PlaybackManifestSession, source: ManifestSourceIdentity, track: DvTrackIdentity): String? {
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
        val composerCode = session.checkComposer(composer)
        if (composerCode != Code.OK) return "composer check $composerCode for $composer"
        // A builder the library refuses would fail on the first injected AU; refuse it here instead.
        val builder = session.builder()
        if (builder is Outcome.Failed) return "builder ${builder.code}"
        return null
    }

    private fun log(message: String) = android.util.Log.i(TAG, "DV_MANIFEST: $message")

    companion object {
        private const val TAG = "DvShotList"
        private const val NS_PER_SECOND = 1_000_000_000L

        /** [timestampScaleNs] / 10^9 s in lowest terms, as the track check takes it, or null past Int. */
        internal fun timeBase(timestampScaleNs: Long): Pair<Int, Int>? {
            if (timestampScaleNs <= 0) return null
            val divisor = gcd(timestampScaleNs, NS_PER_SECOND)
            val num = timestampScaleNs / divisor
            val den = NS_PER_SECOND / divisor
            if (num > Int.MAX_VALUE) return null
            return num.toInt() to den.toInt()
        }

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
