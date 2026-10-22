package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DtsUtil
import com.nuvio.tv.ui.screens.player.iec.DtsHdAccessUnits
import java.nio.ByteBuffer

/**
 * Sink wrapper for the FFmpeg audio renderer that reports how many AC-3, DTS or DTS-HD access
 * units each encoded buffer holds.
 *
 * Media3's DecoderAudioRenderer passes encodedAccessUnitCount = 1 for every buffer, and
 * DefaultAudioSink counts written frames as framesPerEncodedSample * count. A transcode buffer
 * holds every whole unit the encoder produced for one input packet: two or more 512-sample DTS
 * frames or DTS-HD MA units (core + ExSS) for one AAC frame, or two AC-3 frames for one HE-AAC
 * frame. DefaultAudioSink takes the frames per unit from the DTS core at the buffer's position
 * for both DTS encodings. Counted as one, the sink's written-frame total falls behind and caps
 * the audio clock.
 */
internal class EncodedFrameCountingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    @Volatile
    private var inputMimeType: String? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        inputMimeType = inputFormat.sampleMimeType
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val count = countEncodedFrames(inputMimeType, buffer) ?: encodedAccessUnitCount
        return super.handleBuffer(buffer, presentationTimeUs, count)
    }

    companion object {
        private const val HEADER_BYTES = 16

        /**
         * Counts the frames in [buffer] from absolute index 0 to its limit, so a partly consumed
         * buffer that the sink hands back gives the same count. Returns null when the MIME type is
         * not one this wrapper counts or no frame parses at index 0.
         */
        fun countEncodedFrames(mimeType: String?, buffer: ByteBuffer): Int? {
            if (mimeType == MimeTypes.AUDIO_DTS_HD) {
                return DtsHdAccessUnits.walk(buffer).count.takeIf { it > 0 }
            }
            val frameSize: (ByteArray) -> Int = when (mimeType) {
                MimeTypes.AUDIO_DTS -> { header ->
                    if (DtsUtil.getFrameType(header.syncWord()) == DtsUtil.FRAME_TYPE_CORE) {
                        DtsUtil.getDtsFrameSize(header)
                    } else {
                        0
                    }
                }
                MimeTypes.AUDIO_AC3 -> { header ->
                    if (header[0] == AC3_SYNC_0 && header[1] == AC3_SYNC_1) {
                        Ac3Util.parseAc3SyncframeSize(header)
                    } else {
                        0
                    }
                }
                else -> return null
            }
            val limit = buffer.limit()
            val header = ByteArray(HEADER_BYTES)
            var offset = 0
            var frames = 0
            while (limit - offset >= HEADER_BYTES) {
                for (i in 0 until HEADER_BYTES) header[i] = buffer.get(offset + i)
                val size = runCatching { frameSize(header) }.getOrDefault(0)
                if (size <= 0 || size > limit - offset) break
                frames++
                offset += size
            }
            return frames.takeIf { it > 0 }
        }

        private const val AC3_SYNC_0 = 0x0B.toByte()
        private const val AC3_SYNC_1 = 0x77.toByte()

        private fun ByteArray.syncWord(): Int =
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
    }
}
