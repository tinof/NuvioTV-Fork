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

    fun prepare(target: DvSignallingDecision.Target): Prepared

    sealed interface Prepared {
        /** [shot] is the RPU of every AU of the track: one shot, the whole title. */
        data class Ready(val shot: DoviRpuBuilder.ShotRpu, val label: String) : Prepared

        data class Unavailable(val reason: String) : Prepared
    }
}

/**
 * The static providers of `docs/dv-conversion/DESIGN.md` 2.1 through [DoviRpuBuilder]. 8.4: [composer]
 * with `STATIC_84_L1`; the BT.2100 composer unless [CANARY_SWITCH] picks the canary, which DV3.2's G-C
 * check plays to see that the TV applies the composer. 8.1 has no provider before DV3.1 (HDR10+) and
 * DV3.3 (static HDR10), so a PQ track stays native.
 *
 * The one shot is written at track init and the builder handle released at once: [DoviRpuBuilder.ShotRpu]
 * holds its own bytes, so the track keeps no native resource. A provider that renders per shot keeps its
 * handle until the track is released.
 */
@UnstableApi
internal class StaticDvRpuSource(
    private val composer: DoviRpuBuilder.HlgComposer = DoviRpuBuilder.HlgComposer.BT2100,
) : DvRpuSource {

    override fun prepare(target: DvSignallingDecision.Target): DvRpuSource.Prepared = when (target) {
        DvSignallingDecision.Target.PROFILE_8_4 -> static84()
        DvSignallingDecision.Target.PROFILE_8_1 -> DvRpuSource.Prepared.Unavailable("no 8.1 RPU provider")
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
                is Outcome.Ok -> DvRpuSource.Prepared.Ready(outcome.value, label(composer, l1))
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
 * gets the shot's RPU as its last NAL, where `dovi_tool` and the Profile 7 path put it.
 *
 * `scene_refresh_flag` (`docs/dv-conversion/DESIGN.md` 2.2): set on the AU that starts the linear stream,
 * frame 0 (the extractor tells, [inject]'s `streamStart`), as an `mkvdovi` file has it; a mid-file start
 * or a seek carries 0. With [refreshEveryStart] (`debug.nuvio.dv_refresh_every_start`) it is also set on
 * the first IRAP AU after every start, seek or resync of the extractor: Media3 drops the AUs before the
 * first keyframe after a seek, so the AU the decoder starts on is the one that gets it.
 */
@UnstableApi
internal class ConvertedTrackInjector(
    private val shot: DoviRpuBuilder.ShotRpu,
    private val refreshEveryStart: Boolean,
) {
    private var firstAuOfGenerationPending = true

    /** The extractor started reading at a new position: a seek or a resync. */
    fun onReset() {
        firstAuOfGenerationPending = true
    }

    /**
     * Writes [sample]'s first [length] bytes, NAL units with [nalLengthFieldLength]-byte length fields,
     * with the RPU into [out]. False when the sample's NAL lengths do not add up to [length] or the RPU
     * does not fit the length field; [out] then holds nothing usable.
     */
    fun inject(
        sample: ByteArray,
        length: Int,
        nalLengthFieldLength: Int,
        streamStart: Boolean,
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
        val sceneRefresh = streamStart || (refreshEveryStart && firstAuOfGenerationPending && irap)
        if (irap) firstAuOfGenerationPending = false
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
