package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The calls [Hdr10PlusDvRpuSource] makes into `libnuvio_rpu.so`, behind an interface so the JVM tests,
 * which have no library, can drive the scene logic.
 */
internal interface Hdr10PlusNative {
    fun frame(payload: ByteArray): Outcome<DoviRpuBuilder.Hdr10PlusFrame>
    fun create(params: DoviRpuBuilder.Params): Outcome<DoviRpuBuilder.Handle>
    fun renderShot(handle: DoviRpuBuilder.Handle, l1: DoviRpuBuilder.L1): Outcome<DoviRpuBuilder.ShotRpu>
    fun release(handle: DoviRpuBuilder.Handle)
    fun sourceRange81(minNits: Double, maxNits: Double): DoviRpuBuilder.SourceRange?

    /** The library, with `mkvdovi`'s HDR10+ defaults: peak source `histogram`, CM v4.0. */
    object Library : Hdr10PlusNative {
        override fun frame(payload: ByteArray) =
            DoviRpuBuilder.hdr10PlusFrame(payload, Hdr10PlusDvRpuSource.PEAK_SOURCE, Hdr10PlusDvRpuSource.CM_VERSION)

        override fun create(params: DoviRpuBuilder.Params) = DoviRpuBuilder.create(params)

        override fun renderShot(handle: DoviRpuBuilder.Handle, l1: DoviRpuBuilder.L1) =
            DoviRpuBuilder.renderShot(handle, l1)

        override fun release(handle: DoviRpuBuilder.Handle) {
            DoviRpuBuilder.release(handle)
        }

        override fun sourceRange81(minNits: Double, maxNits: Double) = DoviRpuBuilder.sourceRange81(minNits, maxNits)
    }
}

/**
 * HDR10+ → Dolby Vision 8.1 (ROADMAP DV3.1, `docs/dv-conversion/DESIGN.md` 2.4): `dovi_tool generate
 * --hdr10plus-json` as `mkvdovi` runs it, per AU. A PQ track converts only when its hvcC or first AU
 * carries an HDR10+ payload the library accepts; without one it stays native (static HDR10 is DV3.3).
 * [enabled] is false when `debug.nuvio.dv_hdr10plus` is 0.
 */
@UnstableApi
internal class Hdr10PlusDvRpuSource(
    private val enabled: Boolean = true,
    private val native: Hdr10PlusNative = Hdr10PlusNative.Library,
) : DvRpuSource {

    override fun prepare(target: DvSignallingDecision.Target, facts: DvTrackFacts): DvRpuSource.Prepared {
        if (target != DvSignallingDecision.Target.PROFILE_8_1) return unavailable("HDR10+ gives 8.1 only")
        if (!enabled) return unavailable("HDR10+ conversion is off ($SWITCH 0)")
        if (facts.hdr10PlusPayloads.isEmpty()) {
            return unavailable("no HDR10+ SEI in the hvcC or the first AU (static HDR10 is DV3.3)")
        }
        val frame = when (val first = firstFrame(native, facts.hdr10PlusPayloads)) {
            is Outcome.Ok -> first.value
            is Outcome.Failed -> return unavailable("the first AU's HDR10+ SEI: ${first.code}")
        }
        val params = params(facts, native)
        val handle = when (val outcome = native.create(params)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Failed -> return unavailable("create failed: ${outcome.code}")
        }
        val shot = when (val outcome = native.renderShot(handle, frame.l1)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Failed -> {
                native.release(handle)
                return unavailable("renderShot failed: ${outcome.code}")
            }
        }
        return DvRpuSource.Prepared.Ready(Hdr10PlusTrackRpus(native, handle, frame, shot), label(params, frame.l1))
    }

    private fun unavailable(reason: String) = DvRpuSource.Prepared.Unavailable(reason)

    companion object {
        /** `adb shell setprop debug.nuvio.dv_hdr10plus 0`: every PQ track plays native. Read once per extractor. */
        const val SWITCH = "debug.nuvio.dv_hdr10plus"

        val PEAK_SOURCE = DoviRpuBuilder.PeakSource.HISTOGRAM
        val CM_VERSION = DoviRpuBuilder.CmVersion.V40

        /** `mkvdovi`'s L6 defaults (`Level6::mkvdovi_default`): nits, the minimum in 0.0001 nits. */
        private const val DEFAULT_MAX_DML = 1000
        private const val DEFAULT_MIN_DML = 50
        private const val DEFAULT_MAX_CLL = 1000
        private const val DEFAULT_MAX_FALL = 400

        /** `Level6::MAX_VALUE`: every L6 field is capped here (`Level6::clamped`). */
        private const val L6_MAX = 10_000

        /** The first payload the library reads as HDR10+; not-HDR10+ messages are skipped. */
        internal fun firstFrame(
            native: Hdr10PlusNative,
            payloads: List<ByteArray>,
        ): Outcome<DoviRpuBuilder.Hdr10PlusFrame> {
            var failure: Outcome.Failed = Outcome.Failed(Code.NOT_HDR10_PLUS)
            for (payload in payloads) {
                when (val outcome = native.frame(payload)) {
                    is Outcome.Ok -> return outcome
                    is Outcome.Failed -> if (outcome.code != Code.NOT_HDR10_PLUS) failure = outcome
                }
            }
            return failure
        }

        /**
         * `mkvdovi`'s 8.1 params for an HDR10+ title (DESIGN.md 2.4): CM v4.0 and its average floor, L6 from
         * the container with `mkvdovi`'s defaults for what it does not state (a zero MaxCLL or MaxFALL is
         * not stated, a zero mastering minimum is), each field capped at 10 000, and the source range of
         * the final mastering values.
         */
        internal fun params(facts: DvTrackFacts, native: Hdr10PlusNative): DoviRpuBuilder.Params {
            val level6 = level6(facts)
            val range = native.sourceRange81(
                level6.minDisplayMasteringLuminance / 10_000.0,
                level6.maxDisplayMasteringLuminance.toDouble(),
            )
            return DoviRpuBuilder.Params(
                profile = DoviRpuBuilder.Profile.P81(range),
                cmVersion = CM_VERSION,
                l1AvgCmVersion = CM_VERSION,
                level6 = level6,
                sourcePrimaryIndex = sourcePrimaryIndex(facts),
            )
        }

        // R, G, B chromaticities (x, y) of the three gamuts L9 names; P3's red, green and blue are those
        // of both DCI-P3 and P3-D65, which mkvdovi both writes as 0.
        private val GAMUTS = listOf(
            DoviRpuBuilder.PRIMARIES_P3_D65 to floatArrayOf(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f),
            DoviRpuBuilder.PRIMARIES_BT709 to floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f),
            DoviRpuBuilder.PRIMARIES_BT2020 to floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f),
        )

        /** Chromaticity slack: the SEI codes x and y in steps of 0.00002, muxers round further. */
        private const val PRIMARIES_TOLERANCE = 0.002f

        /**
         * L9's source primaries as `mkvdovi` detects them (`detect_source_primaries`): the mastering
         * display's gamut first, then the container's primaries (H.273 1 BT.709, 9 BT.2020, 11 and 12
         * P3), BT.2020 otherwise.
         */
        internal fun sourcePrimaryIndex(facts: DvTrackFacts): Int {
            facts.masteringPrimaries?.takeIf { it.size >= 6 }?.let { primaries ->
                GAMUTS.firstOrNull { (_, gamut) ->
                    gamut.indices.all { abs(primaries[it] - gamut[it]) <= PRIMARIES_TOLERANCE }
                }?.let { return it.first }
            }
            return when (facts.containerPrimaries) {
                1 -> DoviRpuBuilder.PRIMARIES_BT709
                11, 12 -> DoviRpuBuilder.PRIMARIES_P3_D65
                else -> DoviRpuBuilder.PRIMARIES_BT2020
            }
        }

        internal fun level6(facts: DvTrackFacts): DoviRpuBuilder.Level6 {
            fun cap(value: Int) = value.coerceIn(0, L6_MAX)
            // A float the container stated; NaN and negative values are not a mastering display.
            fun stated(value: Float?) = value?.takeIf { it.isFinite() && it >= 0f }
            val maxDml = stated(facts.masteringMaxNits)?.let { it.roundToInt() } ?: DEFAULT_MAX_DML
            val minDml = stated(facts.masteringMinNits)?.let { (it * 10_000.0).roundToInt() } ?: DEFAULT_MIN_DML
            val statedCll = facts.maxContentLightLevel?.takeIf { it > 0 }
            val statedFall = facts.maxFrameAverageLightLevel?.takeIf { it > 0 }
            var maxCll = statedCll ?: DEFAULT_MAX_CLL
            var maxFall = statedFall ?: DEFAULT_MAX_FALL
            // mkvdovi's resolve_light_levels: a filled value keeps MaxFALL <= MaxCLL against a stated one.
            if ((statedCll == null || statedFall == null) && maxFall > maxCll) {
                if (statedCll != null) maxFall = maxCll else maxCll = maxFall
            }
            return DoviRpuBuilder.Level6(cap(maxDml), cap(minDml), cap(maxCll), cap(maxFall))
        }

        /** The `DV_INJECT` log line's provider part. */
        internal fun label(params: DoviRpuBuilder.Params, l1: DoviRpuBuilder.L1): String {
            val l6 = params.level6
            val range = (params.profile as? DoviRpuBuilder.Profile.P81)?.sourceRange
                ?.let { "${it.minPq}..${it.maxPq}" } ?: "from L6"
            return "HDR10+ 8.1, peak ${PEAK_SOURCE.name.lowercase()}, CM ${CM_VERSION.name}, " +
                "L9 primaries ${params.sourcePrimaryIndex}, " +
                "L6 ${l6?.maxDisplayMasteringLuminance}/${l6?.minDisplayMasteringLuminance}/" +
                "${l6?.maxContentLightLevel}/${l6?.maxFrameAverageLightLevel}, source range $range, " +
                "first L1 ${l1.minPq}/${l1.avgPq}/${l1.maxPq}"
        }
    }
}

/**
 * The RPUs of one HDR10+ track (DESIGN.md 2.4). Each AU's L1 comes from its own payload, or from the
 * previous payload in decode order when it has none; the scene refresh goes on an AU whose scene key
 * differs from the key of the already-read AU with the largest presentation time below its own.
 * Owns [handle] until [release].
 */
@UnstableApi
internal class Hdr10PlusTrackRpus(
    private val native: Hdr10PlusNative,
    private val handle: DoviRpuBuilder.Handle,
    firstFrame: DoviRpuBuilder.Hdr10PlusFrame,
    override val first: DoviRpuBuilder.ShotRpu,
) : TrackRpus {

    // Every shot has its own size; an 8.1 RPU is far below 64 KiB (DESIGN.md 2.4).
    override val minNalLengthFieldLength: Int get() = 2

    // Presentation time → scene key of the AUs read since the last reset, the oldest dropped first.
    private val keysByTime = TreeMap<Long, DoviRpuBuilder.SceneKey>()
    private val readOrder = ArrayDeque<Long>()

    // The previous payload's frame in decode order, within this generation.
    private var previous: DoviRpuBuilder.Hdr10PlusFrame? = null

    // The L1 of the last RPU written, carried across a reset until the first payload after it.
    private var lastL1 = firstFrame.l1
    private var lastShot = first
    private var carriedSinceReset = false

    private val shots = object : LinkedHashMap<DoviRpuBuilder.L1, DoviRpuBuilder.ShotRpu>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DoviRpuBuilder.L1, DoviRpuBuilder.ShotRpu>?) =
            size > SHOT_CACHE
    }

    private var released = false
    private var payloadFailureLogged = false
    private var renderFailureLogged = false

    init {
        shots[firstFrame.l1] = first
    }

    override fun next(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int, timeUs: Long): AuRpu {
        val own = try {
            ownFrame(HevcInitNals.hdr10PlusPayloadsInAu(sample, length, nalUnitLengthFieldLength))
        } catch (e: RuntimeException) {
            logPayloadFailure("the AU's SEI could not be read: ${e.message}")
            null
        }
        if (own != null) previous = own
        val frame = own ?: previous
        if (frame == null) {
            // No payload since the reset: the last L1, no refresh of its own (DESIGN.md 2.4).
            carriedSinceReset = true
            return AuRpu(lastShot, sceneRefresh = false)
        }
        val predecessor = keysByTime.lowerEntry(timeUs)?.value
        var sceneRefresh = predecessor != null && predecessor != frame.key
        if (carriedSinceReset) {
            // The AUs since the reset carried an older L1; this one replaces it.
            carriedSinceReset = false
            if (predecessor == null && frame.l1 != lastL1) sceneRefresh = true
        }
        remember(timeUs, frame.key)
        return AuRpu(shot(frame.l1), sceneRefresh)
    }

    override fun onReset() {
        keysByTime.clear()
        readOrder.clear()
        previous = null
        // A gap carried in the old generation gives the new one's first AU no flag (DESIGN.md 2.4).
        carriedSinceReset = false
    }

    override fun release() {
        if (released) return
        released = true
        native.release(handle)
    }

    /** The first payload of the AU the library reads as HDR10+, or null (a gap). */
    private fun ownFrame(payloads: List<ByteArray>): DoviRpuBuilder.Hdr10PlusFrame? {
        if (payloads.isEmpty()) return null
        return when (val outcome = Hdr10PlusDvRpuSource.firstFrame(native, payloads)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Failed -> {
                if (outcome.code != Code.NOT_HDR10_PLUS) logPayloadFailure("an HDR10+ SEI was refused: ${outcome.code}")
                null
            }
        }
    }

    private fun remember(timeUs: Long, key: DoviRpuBuilder.SceneKey) {
        if (keysByTime.put(timeUs, key) == null) {
            readOrder.addLast(timeUs)
            if (readOrder.size > KEY_WINDOW) keysByTime.remove(readOrder.removeFirst())
        }
    }

    private fun shot(l1: DoviRpuBuilder.L1): DoviRpuBuilder.ShotRpu {
        val shot = shots[l1] ?: when (val outcome = native.renderShot(handle, l1)) {
            is Outcome.Ok -> outcome.value.also { shots[l1] = it }
            is Outcome.Failed -> {
                if (!renderFailureLogged) {
                    renderFailureLogged = true
                    android.util.Log.w(TAG, "HDR10+ L1 ${l1.minPq}/${l1.avgPq}/${l1.maxPq}: renderShot " +
                        "failed (${outcome.code}); the last RPU is kept")
                }
                return lastShot
            }
        }
        lastL1 = l1
        lastShot = shot
        return shot
    }

    private fun logPayloadFailure(what: String) {
        if (payloadFailureLogged) return
        payloadFailureLogged = true
        android.util.Log.w(TAG, "HDR10+: $what; such AUs take the previous payload")
    }

    companion object {
        private const val TAG = "DolbyVisionMkvXform"

        /** AUs whose keys are kept: well past any HEVC reorder depth (at most 16 pictures). */
        const val KEY_WINDOW = 64

        /** Shots kept rendered, for scenes that alternate. */
        const val SHOT_CACHE = 8
    }
}
