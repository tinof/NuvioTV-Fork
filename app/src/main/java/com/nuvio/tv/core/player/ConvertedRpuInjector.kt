package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.util.DebugSwitches

/**
 * Where the RPUs of a converted track come from (ROADMAP DV2.7): a Matroska HEVC track without Dolby
 * Vision that DV2.3 signals as Dolby Vision gets one RPU NAL in every AU. Asked once per track at
 * track init, before the Format is published, so a track whose RPU cannot be written plays native: a
 * Dolby Vision Format without RPUs must never reach the decoder.
 */
@UnstableApi
internal fun interface DvRpuSource {

    fun prepare(target: DvSignallingDecision.Target, facts: DvTrackFacts): Prepared

    sealed interface Prepared {
        /** [rpus] writes the RPU of every AU of the track. */
        data class Ready(val rpus: TrackRpus, val label: String) : Prepared

        data class Unavailable(val reason: String) : Prepared
    }
}

/**
 * What the mastering display and content light level SEI of a stream state (ROADMAP DV3.4), each field
 * null when no message states it: [masteringPrimaries] as [DvTrackFacts] holds them; a light level of 0
 * or 65535 is not stated.
 */
internal class DvSeiStatic(
    val masteringPrimaries: FloatArray? = null,
    val masteringMaxNits: Float? = null,
    val masteringMinNits: Float? = null,
    val maxContentLightLevel: Int? = null,
    val maxFrameAverageLightLevel: Int? = null,
) {
    /**
     * This message or set of messages followed by [later], in reading order (hvcC, then AU): the first
     * mastering display stays and the last stated light level replaces it, as MediaInfo reports them
     * (`docs/dv-conversion/DESIGN.md` 2.4).
     */
    fun then(later: DvSeiStatic?): DvSeiStatic {
        if (later == null) return this
        return DvSeiStatic(
            masteringPrimaries ?: later.masteringPrimaries,
            masteringMaxNits ?: later.masteringMaxNits,
            masteringMinNits ?: later.masteringMinNits,
            later.maxContentLightLevel ?: maxContentLightLevel,
            later.maxFrameAverageLightLevel ?: maxFrameAverageLightLevel,
        )
    }
}

/** The values of [DvTrackFacts] [DvTrackFacts.filledFromSei] can take from the SEI: MediaInfo's four fields. */
internal enum class DvStaticField { MASTERING_LUMINANCE, MASTERING_PRIMARIES, MAX_CLL, MAX_FALL }

/**
 * What the extractor knows of a track when it prepares the conversion (ROADMAP DV3.1): the HDR10+
 * candidates of its hvcC or first AU ([HevcInitNals.hdr10PlusPayloadsInSei]) and the Matroska
 * MasteringMetadata, each field null when the container does not state it: [masteringPrimaries] is
 * R, G, B and white point as x, y pairs (8 values); [containerPrimaries] the Colour element's H.273 code.
 * DV3.4: [seiStatic] is what the stream's SEI states, unused until [filledFromSei], which records in
 * [fromSei] what it took.
 */
internal class DvTrackFacts @JvmOverloads constructor(
    val hdr10PlusPayloads: List<ByteArray> = emptyList(),
    val masteringMaxNits: Float? = null,
    val masteringMinNits: Float? = null,
    val maxContentLightLevel: Int? = null,
    val maxFrameAverageLightLevel: Int? = null,
    val masteringPrimaries: FloatArray? = null,
    val containerPrimaries: Int? = null,
    val seiStatic: DvSeiStatic? = null,
    val fromSei: Set<DvStaticField> = emptySet(),
) {
    private val masteringLuminanceStated: Boolean
        get() = isLuminance(masteringMaxNits) && isLuminance(masteringMinNits)

    /**
     * These facts with the SEI filling what the container leaves open, by MediaInfo's four fields
     * (`docs/dv-conversion/DESIGN.md` 2.4): the mastering luminance only as a pair, so one RPU never
     * mixes the two sources, a light level when the container's is absent or 0.
     */
    fun filledFromSei(): DvTrackFacts {
        val sei = seiStatic ?: return this
        val luminance = !masteringLuminanceStated && isLuminance(sei.masteringMaxNits) && isLuminance(sei.masteringMinNits)
        val primaries = masteringPrimaries == null && sei.masteringPrimaries != null
        val maxCll = (maxContentLightLevel ?: 0) <= 0 && sei.maxContentLightLevel != null
        val maxFall = (maxFrameAverageLightLevel ?: 0) <= 0 && sei.maxFrameAverageLightLevel != null
        return DvTrackFacts(
            hdr10PlusPayloads,
            if (luminance) sei.masteringMaxNits else masteringMaxNits,
            if (luminance) sei.masteringMinNits else masteringMinNits,
            if (maxCll) sei.maxContentLightLevel else maxContentLightLevel,
            if (maxFall) sei.maxFrameAverageLightLevel else maxFrameAverageLightLevel,
            if (primaries) sei.masteringPrimaries else masteringPrimaries,
            containerPrimaries,
            sei,
            listOfNotNull(
                DvStaticField.MASTERING_LUMINANCE.takeIf { luminance },
                DvStaticField.MASTERING_PRIMARIES.takeIf { primaries },
                DvStaticField.MAX_CLL.takeIf { maxCll },
                DvStaticField.MAX_FALL.takeIf { maxFall },
            ).toSet(),
        )
    }

    /** Whether the container and the SEI read so far state all four fields: another SEI could add nothing. */
    fun staticComplete(): Boolean {
        val filled = filledFromSei()
        return filled.masteringLuminanceStated && filled.masteringPrimaries != null &&
            (filled.maxContentLightLevel ?: 0) > 0 && (filled.maxFrameAverageLightLevel ?: 0) > 0
    }

    override fun toString(): String =
        "DvTrackFacts(hdr10Plus=${hdr10PlusPayloads.size}, mastering=$masteringMinNits..$masteringMaxNits, " +
            "maxCll=$maxContentLightLevel, maxFall=$maxFrameAverageLightLevel, " +
            "primaries=${masteringPrimaries?.contentToString()}/$containerPrimaries, fromSei=$fromSei)"

    companion object {
        @JvmField
        val NONE = DvTrackFacts()

        /** A luminance as a mastering display states it; NaN and negative values are not one. */
        internal fun isLuminance(value: Float?) = value != null && value.isFinite() && value >= 0f
    }
}

/**
 * The RPUs of one converted track, AU by AU in decode order. [next] always returns an RPU: a provider
 * that cannot compute a new one gives the last one it wrote, so no AU of a Dolby Vision track goes out
 * without one.
 */
internal interface TrackRpus {
    /** The RPU of the AU in [sample] (presentation time [timeUs]) and whether this source sets its scene refresh. */
    fun next(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int, timeUs: Long): AuRpu

    /** The first RPU, for the size check at track init. */
    val first: DoviRpuBuilder.ShotRpu

    /** The smallest NAL length field every later RPU of this source fits: 1 for one fixed RPU. */
    val minNalLengthFieldLength: Int get() = 1

    /** The extractor starts reading at a new position, after a seek or a resync. */
    fun onReset() {}

    /** The extractor is released, or the track is prepared again: free native resources. */
    fun release() {}
}

/** One AU's RPU: [shot] in its scene-refresh variant when [sceneRefresh]. */
internal class AuRpu(val shot: DoviRpuBuilder.ShotRpu, val sceneRefresh: Boolean)

/** One shot for the whole title: no scene refresh of its own (only frame 0 and the switch set one). */
internal class StaticTrackRpus(override val first: DoviRpuBuilder.ShotRpu) : TrackRpus {
    private val rpu = AuRpu(first, sceneRefresh = false)

    override fun next(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int, timeUs: Long) = rpu
}

/** The source for each target: 8.4 from [p84], 8.1 from [p81]. */
@UnstableApi
internal class DvRpuSources(
    private val p84: DvRpuSource,
    private val p81: DvRpuSource,
) : DvRpuSource {
    override fun prepare(target: DvSignallingDecision.Target, facts: DvTrackFacts): DvRpuSource.Prepared =
        when (target) {
            DvSignallingDecision.Target.PROFILE_8_4 -> p84.prepare(target, facts)
            DvSignallingDecision.Target.PROFILE_8_1 -> p81.prepare(target, facts)
        }

}

/**
 * The static providers of `docs/dv-conversion/DESIGN.md` 2.1 through [DoviRpuBuilder]. 8.4: [composer]
 * with `STATIC_84_L1`; the BT.2100 composer unless [CANARY_SWITCH] picks the canary, which DV3.2's G-C
 * check plays to see that the TV applies the composer. 8.1 comes from [Hdr10PlusDvRpuSource] (DV3.1);
 * static HDR10 is DV3.3.
 *
 * The one shot is written at track init and the builder handle released at once: [DoviRpuBuilder.ShotRpu]
 * holds its own bytes, so the track keeps no native resource. A provider that renders per shot keeps its
 * handle until the track is released.
 */
@UnstableApi
internal class StaticDvRpuSource(
    private val composer: DoviRpuBuilder.HlgComposer = DoviRpuBuilder.HlgComposer.BT2100,
) : DvRpuSource {

    override fun prepare(target: DvSignallingDecision.Target, facts: DvTrackFacts): DvRpuSource.Prepared =
        when (target) {
            DvSignallingDecision.Target.PROFILE_8_4 -> static84()
            DvSignallingDecision.Target.PROFILE_8_1 -> DvRpuSource.Prepared.Unavailable("no static 8.1 RPU provider")
        }

    private fun static84(): DvRpuSource.Prepared {
        val l1 = when (val outcome = DoviRpuBuilder.static84L1()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Failed -> return unavailable("static84L1", outcome.code)
        }
        val handle = when (val outcome = DoviRpuBuilder.createStatic84(composer)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Failed -> return unavailable("createStatic84", outcome.code)
        }
        try {
            return when (val outcome = DoviRpuBuilder.renderShot(handle, l1)) {
                is Outcome.Ok -> DvRpuSource.Prepared.Ready(StaticTrackRpus(outcome.value), label(composer, l1))
                is Outcome.Failed -> unavailable("renderShot", outcome.code)
            }
        } finally {
            DoviRpuBuilder.release(handle)
        }
    }

    private fun unavailable(call: String, code: DoviRpuBuilder.Code) =
        DvRpuSource.Prepared.Unavailable("$call failed: $code")

    companion object {
        /**
         * `adb shell setprop debug.nuvio.dv_hlg_canary 1`: converted HLG tracks get the canary composer, so a
         * TV that applies the composer shows the picture green. Read once per extractor, like
         * `debug.nuvio.dv_refresh_every_start`: a change needs a new playback, not a seek.
         */
        const val CANARY_SWITCH = "debug.nuvio.dv_hlg_canary"

        /** The source the extractor uses: the composer [CANARY_SWITCH] picks. */
        fun fromSwitches() = StaticDvRpuSource(composerFor(DebugSwitches.flag(CANARY_SWITCH)))

        internal fun composerFor(canary: Boolean?): DoviRpuBuilder.HlgComposer =
            if (canary == true) DoviRpuBuilder.HlgComposer.CANARY else DoviRpuBuilder.HlgComposer.BT2100

        /** The `DV_INJECT` log line names the composer: the device record of which one a playback used. */
        internal fun label(composer: DoviRpuBuilder.HlgComposer, l1: DoviRpuBuilder.L1): String {
            val name = when (composer) {
                DoviRpuBuilder.HlgComposer.PRESET -> "preset"
                DoviRpuBuilder.HlgComposer.BT2100 -> "BT.2100"
                DoviRpuBuilder.HlgComposer.CANARY -> "canary"
            }
            return "static 8.4, $name composer, L1 ${l1.minPq}/${l1.avgPq}/${l1.maxPq}"
        }
    }
}

/**
 * Writes one converted track's AUs with its RPU (ROADMAP DV2.7). Each AU keeps every NAL byte for byte,
 * drops any RPU NAL it already carries (a stray type 62 in a track without Dolby Vision signalling) and
 * gets the RPU [rpus] gives it as its last NAL, where `dovi_tool` and the Profile 7 path put it.
 *
 * `scene_refresh_flag` (`docs/dv-conversion/DESIGN.md` 2.2): set on the AU that starts the linear stream,
 * frame 0 (the extractor tells, [inject]'s `streamStart`), as an `mkvdovi` file has it; a mid-file start
 * or a seek carries 0. With [refreshEveryStart] (`debug.nuvio.dv_refresh_every_start`) it is also set on
 * the first IRAP AU after every start, seek or resync of the extractor: Media3 drops the AUs before the
 * first keyframe after a seek, so the AU the decoder starts on is the one that gets it. A source with
 * scenes (HDR10+, DV3.1) sets it on the AUs that start one (DESIGN.md 2.4).
 */
@UnstableApi
internal class ConvertedTrackInjector(
    private val rpus: TrackRpus,
    private val refreshEveryStart: Boolean,
) {
    private var firstAuOfGenerationPending = true

    /** The extractor started reading at a new position: a seek or a resync. */
    fun onReset() {
        firstAuOfGenerationPending = true
        rpus.onReset()
    }

    fun release() = rpus.release()

    /**
     * Writes [sample]'s first [length] bytes, NAL units with [nalLengthFieldLength]-byte length fields,
     * with the RPU into [out]. [timeUs] is the AU's presentation time. False when the sample's NAL
     * lengths do not add up to [length] or the RPU does not fit the length field; [out] then holds
     * nothing usable.
     */
    fun inject(
        sample: ByteArray,
        length: Int,
        nalLengthFieldLength: Int,
        streamStart: Boolean,
        timeUs: Long,
        out: ExposedByteArrayOutputStream,
    ): Boolean {
        if (nalLengthFieldLength !in 1..4) return false
        out.reset()
        var irap = false
        var position = 0
        while (position < length) {
            if (position + nalLengthFieldLength > length) return false
            var nalSize = 0L
            for (i in 0 until nalLengthFieldLength) {
                nalSize = (nalSize shl 8) or (sample[position + i].toLong() and 0xFF)
            }
            val nalStart = position + nalLengthFieldLength
            if (nalSize > length - nalStart) return false
            val nalEnd = nalStart + nalSize.toInt()
            val nalType = if (nalSize > 0) (sample[nalStart].toInt() ushr 1) and 0x3F else -1
            if (nalType in NAL_TYPE_IRAP) irap = true
            if (nalType != NAL_TYPE_RPU) out.write(sample, position, nalEnd - position)
            position = nalEnd
        }
        val rpu = rpus.next(sample, length, nalLengthFieldLength, timeUs)
        val sceneRefresh = rpu.sceneRefresh || streamStart ||
            (refreshEveryStart && firstAuOfGenerationPending && irap)
        if (irap) firstAuOfGenerationPending = false
        val shot = rpu.shot
        if (!fits(shot, nalLengthFieldLength)) return false
        val rpuSize = shot.size(sceneRefresh)
        for (shift in nalLengthFieldLength - 1 downTo 0) {
            out.write((rpuSize ushr (8 * shift)) and 0xFF)
        }
        shot.writeTo(out, sceneRefresh)
        return true
    }

    companion object {
        private const val NAL_TYPE_RPU = 62
        // BLA_W_LP to CRA_NUT: the pictures a decoder can start on.
        private val NAL_TYPE_IRAP = 16..21

        /** Whether both NALs of [shot] fit a [nalLengthFieldLength]-byte NAL length field. */
        fun fits(shot: DoviRpuBuilder.ShotRpu, nalLengthFieldLength: Int): Boolean {
            if (nalLengthFieldLength !in 1..4) return false
            if (nalLengthFieldLength == 4) return true
            val max = maxOf(shot.size(true), shot.size(false))
            return max ushr (8 * nalLengthFieldLength) == 0
        }
    }
}
