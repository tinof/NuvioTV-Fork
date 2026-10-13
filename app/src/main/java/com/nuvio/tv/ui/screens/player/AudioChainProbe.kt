package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport

object AudioChainProbe {

    /**
     * [direct] and [maxPcmChannels] are the 5.1 probe and the API 31+ PCM read; they feed the
     * surround decision and the tunnel chain signature. [directUnion] (stereo, 5.1 or 7.1
     * opens) and [plugMaxChannels] (the HDMI plug intent's channel count, any format) are
     * read only when [unionProbed]; see SurroundFormatResolver.decisionInputs.
     */
    data class ChainSnapshot(
        val direct: DirectSupport?,
        val maxPcmChannels: Int?,
        val directUnion: DirectSupport? = null,
        val plugMaxChannels: Int? = null,
        val unionProbed: Boolean = false
    ) {
        fun deniesEveryEncoding(): Boolean {
            val d = direct ?: return false
            return !d.ac3 && !d.eac3 && !d.trueHd && !d.dts && !d.dtsHd
        }
    }

    @Volatile
    private var cached: Pair<String, ChainSnapshot>? = null

    fun snapshot(context: Context, routeKey: String?, withUnion: Boolean = false): ChainSnapshot {
        if (routeKey != null) {
            cached?.let { (key, snap) -> if (key == routeKey && (snap.unionProbed || !withUnion)) return snap }
        }
        val fresh = ChainSnapshot(
            direct = probeDirectSupport(context),
            maxPcmChannels = readMaxPcmChannelCount(context),
            directUnion = if (withUnion) probeDirectSupportAnyLayout(context) else null,
            plugMaxChannels = if (withUnion) readHdmiPlugMaxChannelCount(context) else null,
            unionProbed = withUnion
        )
        if (routeKey != null && !fresh.deniesEveryEncoding()) {
            cached = routeKey to fresh
        }
        return fresh
    }

    fun invalidate() {
        cached = null
    }

    fun probeDirectSupport(context: Context): DirectSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return readHdmiPlugReport(context)
        return runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            DirectSupport(
                ac3 = probeDirect(AudioFormat.ENCODING_AC3, attributes),
                eac3 = probeDirect(AudioFormat.ENCODING_E_AC3, attributes),
                trueHd = probeDirect(AudioFormat.ENCODING_DOLBY_TRUEHD, attributes),
                dts = probeDirect(AudioFormat.ENCODING_DTS, attributes),
                dtsHd = probeDirect(AudioFormat.ENCODING_DTS_HD, attributes)
            )
        }.getOrNull()
    }

    /**
     * Like [probeDirectSupport], but a format counts when the box opens it as stereo, 5.1 or
     * 7.1. Null below API 29, where the plug report has no layouts to add.
     */
    fun probeDirectSupportAnyLayout(context: Context): DirectSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            fun anyLayout(encoding: Int) = PROBE_CHANNEL_MASKS.any { mask -> probeDirect(encoding, attributes, mask) }
            DirectSupport(
                ac3 = anyLayout(AudioFormat.ENCODING_AC3),
                eac3 = anyLayout(AudioFormat.ENCODING_E_AC3),
                trueHd = anyLayout(AudioFormat.ENCODING_DOLBY_TRUEHD),
                dts = anyLayout(AudioFormat.ENCODING_DTS),
                dtsHd = anyLayout(AudioFormat.ENCODING_DTS_HD)
            )
        }.getOrNull()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun readHdmiPlugReport(context: Context): DirectSupport? {
        val report = runCatching {
            context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        }.getOrNull() ?: return null
        return directSupportFromHdmiPlugReport(
            plugState = report.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1),
            encodings = report.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)
        )
    }

    @SuppressLint("InlinedApi")
    internal fun directSupportFromHdmiPlugReport(plugState: Int, encodings: IntArray?): DirectSupport? {
        return when {
            plugState == 0 ->
                DirectSupport(ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false)
            plugState != 1 || encodings == null || encodings.isEmpty() -> null
            else -> DirectSupport(
                ac3 = AudioFormat.ENCODING_AC3 in encodings,
                eac3 = AudioFormat.ENCODING_E_AC3 in encodings ||
                    AudioFormat.ENCODING_E_AC3_JOC in encodings,
                trueHd = AudioFormat.ENCODING_DOLBY_TRUEHD in encodings,
                dts = AudioFormat.ENCODING_DTS in encodings,
                dtsHd = AudioFormat.ENCODING_DTS_HD in encodings
            )
        }
    }

    /** Stereo, 5.1 and 7.1: some HALs answer for one layout only. */
    val PROBE_CHANNEL_MASKS = intArrayOf(
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.CHANNEL_OUT_5POINT1,
        AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    )

    private fun probeDirect(
        encoding: Int,
        attributes: AudioAttributes,
        channelMask: Int = AudioFormat.CHANNEL_OUT_5POINT1
    ): Boolean {
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(48_000)
            .setChannelMask(channelMask)
            .build()
        return runCatching {
            AudioTrack.isDirectPlaybackSupported(format, attributes)
        }.getOrDefault(false)
    }

    private val HDMI_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC
    )

    /**
     * The HDMI output the box is plugged into. Some boxes also list ARC / eARC outputs with
     * fixed capabilities next to the real port; mixing those in adds formats and PCM channel
     * counts the connected TV never reported. They are used only when no HDMI port is listed.
     */
    internal fun <T> preferHdmiPort(devices: List<T>, typeOf: (T) -> Int): List<T> {
        val hdmi = devices.filter { typeOf(it) == AudioDeviceInfo.TYPE_HDMI }
        return hdmi.ifEmpty { devices.filter { typeOf(it) in HDMI_OUTPUT_TYPES } }
    }

    fun hdmiOutputDevices(audioManager: AudioManager): List<AudioDeviceInfo> =
        preferHdmiPort(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).asList()) { it.type }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun readHdmiPlugEncodings(context: Context): IntArray? {
        val report = runCatching {
            context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        }.getOrNull() ?: return null
        if (report.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1) != 1) return null
        return report.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)
    }

    /**
     * The channel count in the sticky HDMI plug intent, or null. Android fills it from the
     * EDID's audio blocks for any format, so it is a heuristic for PCM, used only where the
     * API 31+ PCM read ([readMaxPcmChannelCount]) is missing.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun readHdmiPlugMaxChannelCount(context: Context): Int? {
        val report = runCatching {
            context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        }.getOrNull() ?: return null
        if (report.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1) != 1) return null
        return report.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, -1).takeIf { it > 0 }
    }

    data class HdmiPlugSnapshot(val unplugged: Boolean, val encodings: Set<Int>?)

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun hdmiPlugSnapshot(context: Context): HdmiPlugSnapshot {
        val report = runCatching {
            context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        }.getOrNull()
        val state = report?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1) ?: -1
        val encodings = if (state == 1) {
            report?.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)?.toSet()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val listed = state == 0 || state == 1 || run {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager == null ||
                runCatching { hdmiOutputDevices(audioManager).isNotEmpty() }.getOrDefault(true)
        }
        return HdmiPlugSnapshot(SurroundRecheckGuard.hdmiUnplugged(state, listed), encodings)
    }

    @SuppressLint("NewApi", "InlinedApi")
    fun readMaxPcmChannelCount(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val pcmEncodings = setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return runCatching {
            hdmiOutputDevices(audioManager)
                .flatMap { it.audioProfiles }
                .filter { it.format in pcmEncodings }
                .flatMap { profile ->
                    (profile.channelMasks.asList() + profile.channelIndexMasks.asList())
                        .map { mask -> Integer.bitCount(mask) }
                }
                .filter { it > 0 }
                .maxOrNull()
        }.getOrNull()
    }
}
