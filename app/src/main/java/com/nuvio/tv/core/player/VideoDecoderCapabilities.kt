package com.nuvio.tv.core.player

import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Which video types this box decodes in hardware. Read once per type and process: the codec list does not change
 * while the app runs, and the media-server profiles and mpv ask on every playback.
 */
internal object VideoDecoderCapabilities {

    private val hardwareByMime = ConcurrentHashMap<String, Boolean>()

    fun hasHardwareDecoder(mimeType: String): Boolean {
        val key = mimeType.lowercase()
        hardwareByMime[key]?.let { return it }
        // A failed query is not cached, so a transient MediaCodecList error does not stick for the session.
        val answer = queryHardwareDecoder(mimeType) ?: return false
        hardwareByMime[key] = answer
        return answer
    }

    private fun queryHardwareDecoder(mimeType: String): Boolean? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                if (Build.VERSION.SDK_INT >= 29) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                        !info.name.startsWith("c2.android.", ignoreCase = true)
                }
        }
    }.getOrNull()
}
