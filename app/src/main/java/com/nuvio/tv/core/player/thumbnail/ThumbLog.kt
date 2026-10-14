package com.nuvio.tv.core.player.thumbnail

/** Which per-thumbnail ThumbWorker lines reach logcat. `setprop log.tag.ThumbWorker D` turns all of them on. */
internal object ThumbLog {
    /** A decode this slow is logged without the DEBUG tag: it is the one worth seeing. */
    const val SLOW_DECODE_MS = 500L

    fun perThumbnail(decodeMs: Long, debugLoggable: Boolean): Boolean = debugLoggable || decodeMs >= SLOW_DECODE_MS
}
