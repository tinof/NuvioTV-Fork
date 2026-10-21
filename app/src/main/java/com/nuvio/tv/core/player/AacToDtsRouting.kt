package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * Which tracks the "Transcode AAC to DTS" setting sends to the FFmpeg renderer, and the DTS
 * format it then asks the sink for. The FFmpeg renderer encodes DTS core at 48 kHz; mono is
 * carried as a 2-channel AudioTrack.
 */
object AacToDtsRouting {

    const val OUTPUT_SAMPLE_RATE = 48_000
    const val OUTPUT_CHANNEL_COUNT = 2

    // xHE-AAC (USAC). FFmpeg 7.0 has no decoder for it.
    private const val XHE_AAC_CODECS_PREFIX = "mp4a.40.42"

    val DTS_OUTPUT_FORMAT: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS)
        .setChannelCount(OUTPUT_CHANNEL_COUNT)
        .setSampleRate(OUTPUT_SAMPLE_RATE)
        .build()

    /** Mono or stereo AAC that the FFmpeg decoder can open. */
    fun isEligibleSource(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_AAC &&
            format.channelCount in 1..2 &&
            format.cryptoType == C.CRYPTO_TYPE_NONE &&
            format.codecs?.startsWith(XHE_AAC_CODECS_PREFIX) != true
}
