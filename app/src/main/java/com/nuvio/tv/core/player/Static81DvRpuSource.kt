package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The L1 of a PQ track's static 8.1 RPU (ROADMAP DV4.10, `docs/dv-conversion/DESIGN.md` 2.8), from the
 * track's static metadata alone: a heuristic, not a measurement.
 *
 * - max: the stated MaxCLL, else the mastering display's peak, and never below a stated MaxFALL;
 * - avg: the stated MaxFALL, else the CM v4.0 floor (1229). MaxFALL is the brightest frame's average
 *   (DESIGN.md 4), so it overstates most shots;
 * - min: 0.
 *
 * A light level of 0 or 65535 is not stated. The values are those of L6 ([Hdr10PlusDvRpuSource.params]):
 * capped at 10 000 nits, `mkvdovi`'s 1000-nit peak for a mastering display the track does not state.
 * `dovi_tool`'s clamp (dolby_vision 3.4.0 `level1.rs`) is applied here, so the L1 logged is the one written.
 */
internal object Static81L1 {
    /** A light level of 65535 is "unknown" in the SEI and in muxers that copy it (as [HevcInitNals] reads it). */
    private const val UNKNOWN_LEVEL = 65_535

    // dolby_vision's L1 bounds: max 2081..4095, avg from the CM v4.0 floor to below max.
    private const val MAX_PQ_MIN = 2081
    private const val MAX_PQ_MAX = 4095
    const val AVG_PQ_FLOOR = 1229

    private const val MAX_NITS = 10_000
    private const val M1 = 2610.0 / 16384.0
    private const val M2 = 2523.0 / 32.0
    private const val C1 = 3424.0 / 4096.0
    private const val C2 = 2413.0 / 128.0
    private const val C3 = 2392.0 / 128.0

    /** [nits] as a 12-bit PQ code, as `dovi_rpu_builder::nits_to_pq_code` computes it; above 10 000 is 4095. */
    fun pq12(nits: Int): Int {
        val y = (nits.coerceIn(0, MAX_NITS) / MAX_NITS.toDouble()).pow(M1)
        val pq = ((C1 + C2 * y) / (1.0 + C3 * y)).pow(M2)
        return (pq * MAX_PQ_MAX).roundToInt()
    }

    /** A MaxCLL or MaxFALL the track states, or null for none, 0 and 65535. */
    fun stated(level: Int?): Int? = level?.takeIf { it > 0 && it != UNKNOWN_LEVEL }

    /** The L1 of [facts] (already filled from the SEI when the SEI counts) with the L6 its RPU carries. */
    fun of(facts: DvTrackFacts, level6: DoviRpuBuilder.Level6): L1 {
        val statedCll = stated(facts.maxContentLightLevel)
        val statedFall = stated(facts.maxFrameAverageLightLevel)
        val peak = if (statedCll != null) level6.maxContentLightLevel else level6.maxDisplayMasteringLuminance
        val maxPq = pq12(maxOf(peak, statedFall ?: 0)).coerceIn(MAX_PQ_MIN, MAX_PQ_MAX)
        val avgPq = (if (statedFall != null) pq12(level6.maxFrameAverageLightLevel) else AVG_PQ_FLOOR)
            .coerceIn(AVG_PQ_FLOOR, maxPq - 1)
        return L1(0, avgPq, maxPq)
    }
}

/**
 * One static Profile 8.1 RPU for a PQ track (ROADMAP DV4.10): [Static81L1] with the params `mkvdovi`
 * gives HDR10 ([Hdr10PlusDvRpuSource.params]: CM v4.0, L6 and L9 from the container and, with
 * [seiStatic], the stream's static SEI). Where those facts are the ones `mkvdovi` read, it differs from
 * `mkvdovi`'s RPUs for the track only in L1. The HDR10 shot list uses it before the first validated
 * IRAP and after a rejection; on its own it is DV3.3's static provider, which no track uses yet.
 *
 * The one shot is written at track init and the builder released at once, like [StaticDvRpuSource].
 */
@UnstableApi
internal class Static81DvRpuSource(
    private val native: Hdr10PlusNative = Hdr10PlusNative.Library,
    private val seiStatic: Boolean = true,
) : DvRpuSource {

    override fun prepare(target: DvSignallingDecision.Target, facts: DvTrackFacts): DvRpuSource.Prepared {
        if (target != DvSignallingDecision.Target.PROFILE_8_1) return unavailable("a static 8.1 RPU is 8.1 only")
        val resolved = if (seiStatic) facts.filledFromSei() else facts
        val params = Hdr10PlusDvRpuSource.params(resolved, native)
        val level6 = params.level6 ?: Hdr10PlusDvRpuSource.level6(resolved)
        val l1 = Static81L1.of(resolved, level6)
        val handle = when (val outcome = native.create(params)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Failed -> return unavailable("create failed: ${outcome.code}")
        }
        try {
            return when (val outcome = native.renderShot(handle, l1)) {
                is Outcome.Ok -> DvRpuSource.Prepared.Ready(StaticTrackRpus(outcome.value), label(params, l1, resolved))
                is Outcome.Failed -> unavailable("renderShot failed: ${outcome.code}")
            }
        } finally {
            native.release(handle)
        }
    }

    private fun unavailable(reason: String) = DvRpuSource.Prepared.Unavailable("static 8.1: $reason")

    companion object {
        /** The `DV_INJECT` log line's provider part: the L1, and the light levels it came from. */
        internal fun label(params: DoviRpuBuilder.Params, l1: L1, facts: DvTrackFacts): String {
            fun level(name: String, value: Int?, field: DvStaticField): String {
                val stated = Static81L1.stated(value) ?: return "$name not stated"
                return "$name $stated (${if (field in facts.fromSei) "SEI" else "container"})"
            }
            val l6 = params.level6
            val range = (params.profile as? DoviRpuBuilder.Profile.P81)?.sourceRange
                ?.let { "${it.minPq}..${it.maxPq}" } ?: "from L6"
            return "static 8.1, L1 ${l1.minPq}/${l1.avgPq}/${l1.maxPq} from " +
                level("MaxCLL", facts.maxContentLightLevel, DvStaticField.MAX_CLL) + ", " +
                level("MaxFALL", facts.maxFrameAverageLightLevel, DvStaticField.MAX_FALL) + ", " +
                "L6 ${l6?.maxDisplayMasteringLuminance}/${l6?.minDisplayMasteringLuminance}/" +
                "${l6?.maxContentLightLevel}/${l6?.maxFrameAverageLightLevel}, " +
                "L9 primaries ${params.sourcePrimaryIndex}, source range $range"
        }
    }
}
