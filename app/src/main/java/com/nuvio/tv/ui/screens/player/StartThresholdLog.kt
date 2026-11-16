package com.nuvio.tv.ui.screens.player

/**
 * The `STHRESH:` line for the reduced DIRECT start threshold, or null when nothing should be logged.
 *
 * AudioTrack.setStartThresholdInFrames is API 31+, so below that the threshold is never applied and
 * the line would only mislead. [sdkInt] is a parameter because Build.VERSION.SDK_INT is 0 in JVM tests.
 */
internal fun startThresholdLogLine(sdkInt: Int, frames: Int): String? {
    if (frames <= 0 || sdkInt < 31) return null
    return "STHRESH: DIRECT start threshold -> $frames frames"
}
