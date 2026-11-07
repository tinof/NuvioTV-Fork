package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer

/**
 * The video renderer's frame counters for the 10 s BUFFER log line (M3.3).
 *
 * `onDroppedVideoFrames` reports only after 50 drops, so single drops never reached logcat. These are
 * the renderer's current cumulative counts, under the HUD's support rules ([PlaybackVideoPresentation]):
 * drops for MediaCodec and tunnelled output, skips for MediaCodec only. A mode without counters logs
 * `frameCounters=<mode>` instead. `counterScope` changes when the renderer's counters are replaced
 * (a new renderer starts again from zero); within one scope the counts never fall.
 * Call on the player's application thread.
 */
internal object PlaybackDecoderCounterFields {

    private var lastCounters: DecoderCounters? = null
    private var scope = 0

    fun sample(player: ExoPlayer, videoDecoderName: String?): String {
        val counters = player.videoDecoderCounters
        val presentation = PlaybackVideoPresentation.resolve(
            hasSelectedVideo = player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
            configured = player.playbackState != Player.STATE_IDLE && player.videoFormat != null,
            appliedTunneling = player.isTunnelingEnabled,
            decoderName = videoDecoderName,
            hasCounters = counters != null
        )
        return fields(presentation, counters)
    }

    @Synchronized
    internal fun fields(presentation: PlaybackVideoPresentation, counters: DecoderCounters?): String {
        if (presentation == PlaybackVideoPresentation.UNCONFIGURED) return ""
        if (!presentation.supportsDropCounters || counters == null) {
            return ", frameCounters=${presentation.name.lowercase()}"
        }
        counters.ensureUpdated()
        if (counters !== lastCounters) {
            lastCounters = counters
            scope++
        }
        val parts = ArrayList<String>(4)
        parts += "dropped=${counters.droppedBufferCount}"
        parts += "maxConsecDropped=${counters.maxConsecutiveDroppedBufferCount}"
        if (presentation.supportsSkipCounters) parts += "skipped=${counters.skippedOutputBufferCount}"
        parts += "counterScope=$scope"
        return parts.joinToString(prefix = ", ")
    }

    internal fun resetForTest() {
        lastCounters = null
        scope = 0
    }
}
