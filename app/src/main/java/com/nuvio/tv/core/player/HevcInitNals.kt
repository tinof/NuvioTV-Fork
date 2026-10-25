package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

/**
 * Reads NAL units from the HEVC initialization data Media3's HevcConfig builds from an hvcC: every
 * NAL of every hvcC array, each behind a start code, in one buffer. For the HLG contract (ROADMAP
 * DV2.4): the first SPS, and the alternative-transfer SEI an hvcC can carry in its prefix SEI array.
 */
@UnstableApi
internal object HevcInitNals {

    private const val NAL_TYPE_FIRST_NON_VCL = 32
    private const val NAL_TYPE_SPS = 33
    private const val NAL_TYPE_PREFIX_SEI = 39
    private const val SEI_PAYLOAD_ALTERNATIVE_TRANSFER = 147

    /** The first SPS NAL (2-byte header included, as coded), or null when there is none. */
    @JvmStatic
    fun firstSps(initializationData: List<ByteArray>?): ByteArray? =
        nals(initializationData).firstOrNull { nalType(it) == NAL_TYPE_SPS }

    /**
     * preferred_transfer_characteristics of the first alternative_transfer_characteristics SEI
     * (payloadType 147, H.265 D.2.38) in a prefix SEI NAL, or null when there is none. It is an
     * H.273 transfer code; 18 is HLG.
     */
    @JvmStatic
    fun preferredTransfer(initializationData: List<ByteArray>?): Int? {
        for (nal in nals(initializationData)) {
            if (nalType(nal) != NAL_TYPE_PREFIX_SEI) continue
            preferredTransferInSei(nal)?.let { return it }
        }
        return null
    }

    /**
     * preferred_transfer_characteristics of the first alternative-transfer SEI in the prefix SEI NALs
     * of one access unit ([length] bytes of [sample], NALs behind [nalUnitLengthFieldLength]-byte
     * lengths, as a Matroska block holds them), or null. The walk stops at the first VCL NAL and at a
     * NAL length that overruns the sample. H.265 (7.4.2.4.4) lets a prefix SEI follow a slice that is
     * not the last of the access unit; encoders put the alternative-transfer SEI before the first slice,
     * and one between slices is not read.
     */
    @JvmStatic
    fun preferredTransferInSample(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int): Int? =
        preferredTransferInAuPrefix(sample, length, length, nalUnitLengthFieldLength).takeIf { it >= 0 }

    /** [preferredTransferInAuPrefix]: the access unit has no alternative-transfer SEI before its first VCL NAL. */
    const val PREFIX_NO_SEI = -1

    /** [preferredTransferInAuPrefix]: the NALs before the first VCL NAL run past the available bytes. */
    const val PREFIX_TRUNCATED = -2

    /** [preferredTransferInAuPrefix]: a NAL length below 2 or past the end of the access unit. */
    const val PREFIX_MALFORMED = -3

    /**
     * The walk of [preferredTransferInSample] over the first [available] bytes of an access unit of
     * [auLength] bytes (ROADMAP DV2.10: the extractor's early scan peeks only the start of a block). Only
     * the NALs before the first VCL NAL are needed, so a VCL NAL ends the walk even when its body is not
     * available. Returns the preferred transfer (0 to 255), [PREFIX_NO_SEI], [PREFIX_TRUNCATED] or
     * [PREFIX_MALFORMED].
     */
    @JvmStatic
    fun preferredTransferInAuPrefix(
        buffer: ByteArray,
        available: Int,
        auLength: Int,
        nalUnitLengthFieldLength: Int,
    ): Int {
        if (nalUnitLengthFieldLength !in 1..4) return PREFIX_MALFORMED
        val limit = minOf(available, auLength)
        var pos = 0
        while (pos < auLength) {
            if (pos + nalUnitLengthFieldLength > auLength) return PREFIX_MALFORMED
            // A NAL needs its length field and its 2-byte header.
            if (pos + nalUnitLengthFieldLength + 2 > auLength) return PREFIX_MALFORMED
            if (pos + nalUnitLengthFieldLength + 2 > limit) return PREFIX_TRUNCATED
            var nalLength = 0L
            repeat(nalUnitLengthFieldLength) { nalLength = (nalLength shl 8) or (buffer[pos++].toLong() and 0xFF) }
            if (nalLength < 2 || nalLength > auLength - pos) return PREFIX_MALFORMED
            val type = (buffer[pos].toInt() ushr 1) and 0x3F
            if (type < NAL_TYPE_FIRST_NON_VCL) return PREFIX_NO_SEI
            val end = pos + nalLength.toInt()
            if (end > limit) return PREFIX_TRUNCATED
            if (type == NAL_TYPE_PREFIX_SEI) {
                preferredTransferInSei(buffer.copyOfRange(pos, end))?.let { return it }
            }
            pos = end
        }
        return PREFIX_NO_SEI
    }

    /** The NAL units of Annex B [initializationData], start codes and trailing zero bytes removed. */
    fun nals(initializationData: List<ByteArray>?): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (buffer in initializationData ?: return out) {
            var start = -1
            var i = 0
            while (i + 2 < buffer.size) {
                if (buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() && buffer[i + 2] == 1.toByte()) {
                    if (start >= 0) addNal(out, buffer, start, i)
                    i += 3
                    start = i
                } else {
                    i++
                }
            }
            if (start >= 0) addNal(out, buffer, start, buffer.size)
        }
        return out
    }

    private fun addNal(out: MutableList<ByteArray>, buffer: ByteArray, start: Int, end: Int) {
        var limit = end
        // The zero bytes before the next start code (a 4-byte start code, or trailing_zero_8bits).
        while (limit > start && buffer[limit - 1] == 0.toByte()) limit--
        if (limit - start >= 2) out += buffer.copyOfRange(start, limit)
    }

    private fun nalType(nal: ByteArray): Int = (nal[0].toInt() ushr 1) and 0x3F

    /** Walks the sei_message()s of one SEI NAL (H.265 7.3.5) for payloadType 147. */
    private fun preferredTransferInSei(nal: ByteArray): Int? {
        val rbsp = nal.copyOf()
        val rbspLimit = NalUnitUtil.unescapeStream(rbsp, rbsp.size)
        // The last byte holds rbsp_stop_one_bit; messages end before it.
        val end = rbspLimit - 1
        var pos = 2
        while (pos < end) {
            var payloadType = 0
            while (pos < end && rbsp[pos] == 0xFF.toByte()) {
                payloadType += 255
                pos++
            }
            if (pos >= end) return null
            payloadType += rbsp[pos++].toInt() and 0xFF
            var payloadSize = 0
            while (pos < end && rbsp[pos] == 0xFF.toByte()) {
                payloadSize += 255
                pos++
            }
            if (pos >= end) return null
            payloadSize += rbsp[pos++].toInt() and 0xFF
            if (pos + payloadSize > end) return null
            if (payloadType == SEI_PAYLOAD_ALTERNATIVE_TRANSFER && payloadSize >= 1) {
                return rbsp[pos].toInt() and 0xFF
            }
            pos += payloadSize
        }
        return null
    }
}
