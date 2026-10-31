package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

/**
 * Reads NAL units from the HEVC initialization data Media3's HevcConfig builds from an hvcC: every
 * NAL of every hvcC array, each behind a start code, in one buffer. For the HLG contract (ROADMAP
 * DV2.4): the first SPS, and the alternative-transfer SEI an hvcC can carry in its prefix SEI array.
 * For the HDR10+ provider (DV3.1): the HDR10+ T.35 payloads of an hvcC or an access unit; and (DV3.4)
 * their mastering display and content light level SEI.
 */
@UnstableApi
internal object HevcInitNals {

    private const val NAL_TYPE_FIRST_NON_VCL = 32
    private const val NAL_TYPE_VPS = 32
    private const val NAL_TYPE_SPS = 33
    private const val NAL_TYPE_PPS = 34
    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private const val NAL_TYPE_PREFIX_SEI = 39
    private const val SEI_PAYLOAD_ALTERNATIVE_TRANSFER = 147
    private const val SEI_PAYLOAD_T35 = 4
    private val HDR10_PLUS_T35_HEADER = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01)
    private const val SEI_PAYLOAD_MASTERING_DISPLAY = 137
    private const val SEI_PAYLOAD_CONTENT_LIGHT = 144
    private const val MASTERING_DISPLAY_SIZE = 24
    private const val CONTENT_LIGHT_SIZE = 4

    // H.265 D.3.28: chromaticity in 0.00002 units, luminance in 0.0001 nits.
    private const val CHROMATICITY_UNIT = 0.00002
    private const val LUMINANCE_UNIT = 0.0001

    // A blue primary lies below this in x and y (0.35, in chromaticity units), as MediaInfo sorts them.
    private const val BLUE_LIMIT = 17_500
    private const val LIGHT_LEVEL_NOT_MEASURED = 65_535

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
        val prefix = readAuPrefix(buffer, available, auLength, nalUnitLengthFieldLength)
        return prefix.preferredTransfer ?: if (prefix.status == PREFIX_COMPLETE) PREFIX_NO_SEI else prefix.status
    }

    /** [readAuPrefix]: every NAL before the first VCL NAL (or the end of the access unit) was read. */
    const val PREFIX_COMPLETE = 0

    /**
     * What the NALs before the first VCL NAL of an access unit hold: the preferred transfer of the
     * first alternative-transfer SEI, or null, and the VPS, SPS and PPS NALs in order (ROADMAP DV2.11: a
     * track whose hvcC carries no parameter sets has them only in its access units). [status] is
     * [PREFIX_COMPLETE], [PREFIX_TRUNCATED] or [PREFIX_MALFORMED]; what was read before a truncation or
     * a malformed length is kept.
     */
    class AuPrefix(
        val status: Int,
        val preferredTransfer: Int?,
        val parameterSets: List<ByteArray>,
        /** ROADMAP DV3.1: the HDR10+ candidates of the prefix SEI NALs, as [hdr10PlusPayloadsInSei]. */
        val hdr10PlusPayloads: List<ByteArray> = emptyList(),
        /** ROADMAP DV3.4: the static metadata of the prefix SEI NALs, or null without any. */
        val seiStatic: DvSeiStatic? = null,
    ) {
        /** The first SPS NAL, or null. */
        val sps: ByteArray? get() = parameterSets.firstOrNull { nalType(it) == NAL_TYPE_SPS }

        /** The parameter sets as one Annex B buffer, as Media3's HevcConfig builds initialization data. */
        fun parameterSetsAnnexB(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (nal in parameterSets) {
                out.write(START_CODE)
                out.write(nal)
            }
            return out.toByteArray()
        }
    }

    /**
     * Walks the NALs before the first VCL NAL in the first [available] bytes of an access unit of
     * [auLength] bytes, NALs behind [nalUnitLengthFieldLength]-byte lengths, as a Matroska block holds
     * them. A VCL NAL ends the walk even when its body is not available.
     */
    @JvmStatic
    fun readAuPrefix(buffer: ByteArray, available: Int, auLength: Int, nalUnitLengthFieldLength: Int): AuPrefix {
        val parameterSets = mutableListOf<ByteArray>()
        val hdr10PlusPayloads = mutableListOf<ByteArray>()
        var preferredTransfer: Int? = null
        var seiStatic: DvSeiStatic? = null
        fun result(status: Int) = AuPrefix(status, preferredTransfer, parameterSets, hdr10PlusPayloads, seiStatic)
        if (nalUnitLengthFieldLength !in 1..4) return result(PREFIX_MALFORMED)
        val limit = minOf(available, auLength)
        var pos = 0
        while (pos < auLength) {
            // A NAL needs its length field and its 2-byte header.
            if (pos + nalUnitLengthFieldLength + 2 > auLength) return result(PREFIX_MALFORMED)
            if (pos + nalUnitLengthFieldLength + 2 > limit) return result(PREFIX_TRUNCATED)
            var nalLength = 0L
            repeat(nalUnitLengthFieldLength) { nalLength = (nalLength shl 8) or (buffer[pos++].toLong() and 0xFF) }
            if (nalLength < 2 || nalLength > auLength - pos) return result(PREFIX_MALFORMED)
            val type = (buffer[pos].toInt() ushr 1) and 0x3F
            if (type < NAL_TYPE_FIRST_NON_VCL) return result(PREFIX_COMPLETE)
            val end = pos + nalLength.toInt()
            if (end > limit) return result(PREFIX_TRUNCATED)
            if (type in NAL_TYPE_VPS..NAL_TYPE_PPS) parameterSets += buffer.copyOfRange(pos, end)
            if (type == NAL_TYPE_PREFIX_SEI) {
                val sei = buffer.copyOfRange(pos, end)
                if (preferredTransfer == null) preferredTransfer = preferredTransferInSei(sei)
                hdr10PlusPayloads += hdr10PlusPayloadsInSei(sei)
                val found = seiStaticInSei(sei)
                seiStatic = seiStatic?.then(found) ?: found
            }
            pos = end
        }
        return result(PREFIX_COMPLETE)
    }

    /** DV3.2 diagnostics: the NAL types of [initializationData] and the first SPS as hex. */
    @JvmStatic
    fun describe(initializationData: List<ByteArray>?): String {
        val nals = nals(initializationData)
        val types = nals.joinToString(",") { nalType(it).toString() }
        val sps = nals.firstOrNull { nalType(it) == NAL_TYPE_SPS }
            ?.joinToString("") { "%02x".format(it.toInt() and 0xFF) } ?: "none"
        return "${initializationData?.size ?: 0} buffers, NAL types [$types], SPS $sps"
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
        var transfer: Int? = null
        forEachSeiMessage(nal) { type, rbsp, start, size ->
            if (type == SEI_PAYLOAD_ALTERNATIVE_TRANSFER && size >= 1) {
                transfer = rbsp[start].toInt() and 0xFF
                false
            } else {
                true
            }
        }
        return transfer
    }

    /**
     * The payloads of the ITU-T T.35 SEI messages (payloadType 4) of one SEI NAL that start with the
     * HDR10+ T.35 header (country 0xB5, provider 0x003C, oriented code 0x0001), in order: each one the
     * RBSP from the country code on, the bytes `hdr10plus_tool` and `DoviRpuBuilder` read. Whether one
     * is HDR10+ is the library's call ([DoviRpuBuilder.Code.NOT_HDR10_PLUS]).
     */
    @JvmStatic
    fun hdr10PlusPayloadsInSei(nal: ByteArray): List<ByteArray> {
        // The header has no 00 00 pair, so emulation prevention never splits it: a raw search is exact.
        if (indexOf(nal, 0, nal.size, HDR10_PLUS_T35_HEADER) < 0) return emptyList()
        val payloads = mutableListOf<ByteArray>()
        forEachSeiMessage(nal) { type, rbsp, start, size ->
            if (type == SEI_PAYLOAD_T35 && size >= HDR10_PLUS_T35_HEADER.size &&
                indexOf(rbsp, start, start + HDR10_PLUS_T35_HEADER.size, HDR10_PLUS_T35_HEADER) == start
            ) {
                payloads += rbsp.copyOfRange(start, start + size)
            }
            true
        }
        return payloads
    }

    /** The HDR10+ candidates (as [hdr10PlusPayloadsInSei]) of the prefix SEI NALs an hvcC carries. */
    @JvmStatic
    fun hdr10PlusPayloads(initializationData: List<ByteArray>?): List<ByteArray> =
        nals(initializationData).filter { nalType(it) == NAL_TYPE_PREFIX_SEI }.flatMap(::hdr10PlusPayloadsInSei)

    /**
     * The HDR10+ candidates of one access unit (ROADMAP DV3.1): those of its prefix SEI NALs before the
     * first VCL NAL, [length] bytes of [sample] behind [nalUnitLengthFieldLength]-byte lengths. Empty
     * when there are none or the lengths do not parse. Only SEI NALs that hold the T.35 header are
     * copied, so an AU without HDR10+ costs one pass over its NAL headers.
     */
    @JvmStatic
    fun hdr10PlusPayloadsInAu(sample: ByteArray, length: Int, nalUnitLengthFieldLength: Int): List<ByteArray> {
        if (nalUnitLengthFieldLength !in 1..4) return emptyList()
        var payloads: MutableList<ByteArray>? = null
        var pos = 0
        while (pos + nalUnitLengthFieldLength + 2 <= length) {
            var nalLength = 0L
            repeat(nalUnitLengthFieldLength) { nalLength = (nalLength shl 8) or (sample[pos++].toLong() and 0xFF) }
            if (nalLength < 2 || nalLength > length - pos) break
            val end = pos + nalLength.toInt()
            val type = (sample[pos].toInt() ushr 1) and 0x3F
            if (type < NAL_TYPE_FIRST_NON_VCL) break
            if (type == NAL_TYPE_PREFIX_SEI && indexOf(sample, pos, end, HDR10_PLUS_T35_HEADER) >= 0) {
                val found = hdr10PlusPayloadsInSei(sample.copyOfRange(pos, end))
                if (found.isNotEmpty()) (payloads ?: mutableListOf<ByteArray>().also { payloads = it }) += found
            }
            pos = end
        }
        return payloads ?: emptyList()
    }

    /** The static metadata of the prefix SEI NALs an hvcC carries (ROADMAP DV3.4), or null without any. */
    @JvmStatic
    fun seiStatic(initializationData: List<ByteArray>?): DvSeiStatic? {
        var static: DvSeiStatic? = null
        for (nal in nals(initializationData)) {
            if (nalType(nal) != NAL_TYPE_PREFIX_SEI) continue
            val found = seiStaticInSei(nal)
            static = static?.then(found) ?: found
        }
        return static
    }

    /**
     * The mastering display (payloadType 137) and content light level (144) messages of one SEI NAL,
     * [DvSeiStatic.then] in message order, or null without a usable one. A message shorter than its
     * syntax is skipped; a longer one counts, as in MediaInfo (`docs/dv-conversion/DESIGN.md` 2.4).
     */
    fun seiStaticInSei(nal: ByteArray): DvSeiStatic? {
        var static: DvSeiStatic? = null
        forEachSeiMessage(nal) { type, rbsp, start, size ->
            val message = when {
                type == SEI_PAYLOAD_MASTERING_DISPLAY && size >= MASTERING_DISPLAY_SIZE -> masteringDisplay(rbsp, start)
                type == SEI_PAYLOAD_CONTENT_LIGHT && size >= CONTENT_LIGHT_SIZE -> contentLight(rbsp, start)
                else -> null
            }
            static = static?.then(message) ?: message
            true
        }
        return static
    }

    /**
     * mastering_display_colour_volume (H.265 D.2.28): three x, y pairs, the white point, then maximum
     * and minimum luminance. The pairs come as G, B, R; [masteringPrimaries] sorts them into R, G, B.
     */
    private fun masteringDisplay(rbsp: ByteArray, start: Int): DvSeiStatic {
        val pairs = List(4) { i -> u16(rbsp, start + 4 * i) to u16(rbsp, start + 4 * i + 2) }
        val maxUnits = u32(rbsp, start + 16)
        val minUnits = u32(rbsp, start + 20)
        return DvSeiStatic(
            masteringPrimaries = masteringPrimaries(pairs),
            masteringMaxNits = (maxUnits * LUMINANCE_UNIT).toFloat(),
            masteringMinNits = (minUnits * LUMINANCE_UNIT).toFloat(),
        )
    }

    /**
     * R, G, B and white point as x, y pairs. MediaInfo reports a known gamut whatever order the three
     * primaries come in, so they are sorted by chromaticity; the spec's G, B, R stands when that does
     * not give one of each.
     */
    private fun masteringPrimaries(pairs: List<Pair<Int, Int>>): FloatArray {
        val primaries = pairs.take(3)
        val blue = primaries.filter { (x, y) -> x < BLUE_LIMIT && y < BLUE_LIMIT }
        val rest = primaries - blue.toSet()
        val green = rest.filter { (x, y) -> y >= x }
        val red = rest - green.toSet()
        val sorted = if (blue.size == 1 && green.size == 1 && red.size == 1) {
            listOf(red[0], green[0], blue[0])
        } else {
            listOf(pairs[2], pairs[0], pairs[1])
        }
        return (sorted + pairs[3]).flatMap { (x, y) -> listOf(x, y) }
            .map { (it * CHROMATICITY_UNIT).toFloat() }.toFloatArray()
    }

    /** content_light_level_info (H.265 D.2.35). 0 and 65535 state nothing; null when neither level is stated. */
    private fun contentLight(rbsp: ByteArray, start: Int): DvSeiStatic? {
        fun stated(value: Int) = value.takeIf { it in 1 until LIGHT_LEVEL_NOT_MEASURED }
        val maxCll = stated(u16(rbsp, start))
        val maxFall = stated(u16(rbsp, start + 2))
        if (maxCll == null && maxFall == null) return null
        return DvSeiStatic(maxContentLightLevel = maxCll, maxFrameAverageLightLevel = maxFall)
    }

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, at: Int) =
        (u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong()

    /**
     * Calls [visit] with (payloadType, RBSP, payload start, payload size) for each sei_message() of
     * one SEI NAL (H.265 7.3.5), until it returns false or a message runs past the RBSP.
     */
    private inline fun forEachSeiMessage(nal: ByteArray, visit: (Int, ByteArray, Int, Int) -> Boolean) {
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
            if (pos >= end) return
            payloadType += rbsp[pos++].toInt() and 0xFF
            var payloadSize = 0
            while (pos < end && rbsp[pos] == 0xFF.toByte()) {
                payloadSize += 255
                pos++
            }
            if (pos >= end) return
            payloadSize += rbsp[pos++].toInt() and 0xFF
            if (pos + payloadSize > end) return
            if (!visit(payloadType, rbsp, pos, payloadSize)) return
            pos += payloadSize
        }
    }

    /** The first index of [pattern] in [buffer] between [from] and [to], or -1. */
    private fun indexOf(buffer: ByteArray, from: Int, to: Int, pattern: ByteArray): Int {
        var i = from
        while (i <= to - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (buffer[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }
}
