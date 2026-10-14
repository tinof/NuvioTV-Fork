package com.nuvio.tv.core.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes the box's video decoder list to logcat once per process (tag NuvioCodecs):
 * name, MIME, hardware or software, tunneled and secure playback, and the
 * profile/level pairs for Dolby Vision. One `adb logcat -s NuvioCodecs` answers
 * which decoders a box has without a separate tool.
 *
 * ALL_CODECS, not REGULAR_CODECS: the regular list leaves out decoders that
 * only do secure playback (OMX.Nvidia.*.decode.secure on the Shield);
 * secureRequired marks them. This list only logs; the decisions elsewhere
 * keep their own MediaCodecList.
 */
object DecoderInventoryLog {

    private const val TAG = "NuvioCodecs"
    private val logged = AtomicBoolean(false)

    /** Call off the main thread: MediaCodecList enumeration takes tens of milliseconds. */
    fun logOnce() {
        if (!logged.compareAndSet(false, true)) return
        runCatching {
            Log.i(TAG, "device=${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE} api=${Build.VERSION.SDK_INT}")
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }
                .forEach { info ->
                    info.supportedTypes
                        .filter { it.startsWith("video/", ignoreCase = true) }
                        .forEach { mime -> Log.i(TAG, describe(info, mime)) }
                }
        }.onFailure { Log.w(TAG, "decoder inventory failed: ${it.message}") }
    }

    private fun describe(info: MediaCodecInfo, mime: String): String {
        val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        val hardware = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> if (info.isHardwareAccelerated) "hw" else "sw"
            else -> "hw?"
        }
        val tunneled = caps?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) == true
        val secure = caps?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) == true
        val secureRequired = caps?.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) == true
        val profiles = if (mime.equals("video/dolby-vision", ignoreCase = true)) {
            caps?.profileLevels?.joinToString(",", prefix = " dvProfiles=") { "${it.profile}/${it.level}" }.orEmpty()
        } else {
            ""
        }
        val maxSize = caps?.videoCapabilities?.let { v ->
            " max=${v.supportedWidths.upper}x${v.supportedHeights.upper}"
        }.orEmpty()
        return line(info.name, mime, hardware, tunneled, secure, secureRequired, maxSize + profiles)
    }

    /** One inventory line; [extra] is the max size and DV profile suffix, each starting with a space. */
    internal fun line(
        name: String,
        mime: String,
        hardware: String,
        tunneled: Boolean,
        secure: Boolean,
        secureRequired: Boolean,
        extra: String = ""
    ): String = "$name $mime $hardware tunneled=$tunneled secure=$secure secureRequired=$secureRequired$extra"
}
