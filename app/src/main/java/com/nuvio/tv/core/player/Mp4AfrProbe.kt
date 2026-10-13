package com.nuvio.tv.core.player

/**
 * Video frame rate from an MP4 / MOV head window, the MP4 counterpart of
 * [MatroskaAfrProbe.parseVideoFrameRateFromHead]: the prewarm already holds the first 256 KiB, and a faststart file
 * keeps moov there. A long film's sample tables run past the window, so a cut-off moov is read as far as it goes:
 * the video trak's mdhd timescale and stts come before the large stsz / stco tables. Fragmented MP4 has an empty
 * stts and gives the frame duration in the first fragment's tfhd or in mvex/trex instead. Anything else (moov at
 * the end, no video track, odd boxes) returns null and leaves AFR to the track format after prepare.
 */
internal object Mp4AfrProbe {

    private val TOP_LEVEL_TYPES = setOf("ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "wide", "pnot", "uuid", "mdat")
    private const val TFHD_BASE_DATA_OFFSET = 0x01
    private const val TFHD_SAMPLE_DESCRIPTION_INDEX = 0x02
    private const val TFHD_DEFAULT_SAMPLE_DURATION = 0x08

    /** [end] is clamped to the bytes held; [complete] is false for a box cut off by the window. */
    private class Box(val type: String, val dataStart: Int, val end: Int, val complete: Boolean)

    private class Track {
        var trackId: Long? = null
        var isVideo = false
        var timescale = 0L
        var sttsDelta: Long? = null
        var width: Int? = null
        var height: Int? = null
    }

    fun parseVideoFrameRateFromHead(bytes: ByteArray): MatroskaAfrProbe.VideoFrameRateHint? {
        val first = readBox(bytes, 0, bytes.size) ?: return null
        if (first.type !in TOP_LEVEL_TYPES) return null

        var video: Track? = null
        val trexDurations = HashMap<Long, Long>()
        val tfhdDurations = HashMap<Long, Long>()
        forEachBox(bytes, 0, bytes.size) { top ->
            when (top.type) {
                "moov" -> forEachBox(bytes, top.dataStart, top.end) { child ->
                    when (child.type) {
                        "trak" -> if (video == null) {
                            val track = parseTrak(bytes, child)
                            if (track.isVideo) video = track
                        }
                        "mvex" -> forEachBox(bytes, child.dataStart, child.end) { entry ->
                            if (entry.type == "trex") readTrex(bytes, entry)?.let { (id, duration) ->
                                trexDurations.getOrPut(id) { duration }
                            }
                        }
                    }
                }
                "moof" -> forEachBox(bytes, top.dataStart, top.end) { child ->
                    if (child.type == "traf") forEachBox(bytes, child.dataStart, child.end) { entry ->
                        if (entry.type == "tfhd") readTfhd(bytes, entry)?.let { (id, duration) ->
                            tfhdDurations.getOrPut(id) { duration }
                        }
                    }
                }
            }
        }

        val track = video ?: return null
        if (track.timescale <= 0L) return null
        val trackId = track.trackId
        val delta = track.sttsDelta
            ?: trackId?.let { tfhdDurations[it] ?: trexDurations[it] }
            ?: return null
        if (delta <= 0L) return null
        return MatroskaAfrProbe.VideoFrameRateHint(
            rawFps = (track.timescale.toDouble() / delta.toDouble()).toFloat(),
            width = track.width?.takeIf { it > 0 },
            height = track.height?.takeIf { it > 0 }
        )
    }

    private fun parseTrak(bytes: ByteArray, trak: Box): Track {
        val track = Track()
        forEachBox(bytes, trak.dataStart, trak.end) { child ->
            when (child.type) {
                "tkhd" -> track.trackId = readTkhdTrackId(bytes, child)
                "mdia" -> forEachBox(bytes, child.dataStart, child.end) { media ->
                    when (media.type) {
                        "mdhd" -> track.timescale = readMdhdTimescale(bytes, media) ?: 0L
                        "hdlr" -> track.isVideo = fourCc(bytes, media.dataStart + 8, media.end) == "vide"
                        "minf" -> forEachBox(bytes, media.dataStart, media.end) { info ->
                            if (info.type == "stbl") forEachBox(bytes, info.dataStart, info.end) { table ->
                                when (table.type) {
                                    "stsd" -> readVisualSize(bytes, table)?.let { (w, h) ->
                                        track.width = w
                                        track.height = h
                                    }
                                    "stts" -> track.sttsDelta = readDominantSttsDelta(bytes, table)
                                }
                            }
                        }
                    }
                }
            }
        }
        return track
    }

    /** Calls [action] for each child box in [start, end); a box cut off by the window is the last one seen. */
    private fun forEachBox(bytes: ByteArray, start: Int, end: Int, action: (Box) -> Unit) {
        var pos = start
        while (pos < end) {
            val box = readBox(bytes, pos, end) ?: return
            action(box)
            if (!box.complete) return
            pos = box.end
        }
    }

    private fun readBox(bytes: ByteArray, pos: Int, end: Int): Box? {
        val limit = minOf(end, bytes.size)
        if (pos < 0 || pos + 8 > limit) return null
        val size32 = u32(bytes, pos)
        val type = fourCc(bytes, pos + 4, limit) ?: return null
        var header = 8
        val size = when (size32) {
            0L -> (limit - pos).toLong()
            1L -> {
                if (pos + 16 > limit) return null
                header = 16
                u64(bytes, pos + 8)
            }
            else -> size32
        }
        if (size < header) return null
        val declaredEnd = pos.toLong() + size
        val complete = declaredEnd <= limit
        return Box(type, pos + header, if (complete) declaredEnd.toInt() else limit, complete)
    }

    private fun readTkhdTrackId(bytes: ByteArray, box: Box): Long? {
        val version = byteAt(bytes, box.dataStart, box.end) ?: return null
        val offset = box.dataStart + if (version == 1) 20 else 12
        return if (offset + 4 <= box.end) u32(bytes, offset) else null
    }

    private fun readMdhdTimescale(bytes: ByteArray, box: Box): Long? {
        val version = byteAt(bytes, box.dataStart, box.end) ?: return null
        val offset = box.dataStart + if (version == 1) 20 else 12
        return if (offset + 4 <= box.end) u32(bytes, offset) else null
    }

    /** Width and height of the first visual sample entry (avc1, hvc1, av01, ...). */
    private fun readVisualSize(bytes: ByteArray, box: Box): Pair<Int, Int>? {
        val entry = box.dataStart + 8
        if (entry + 36 > box.end) return null
        return u16(bytes, entry + 32) to u16(bytes, entry + 34)
    }

    /** The sample delta that covers the most samples: a short odd run at the start does not decide the rate. */
    private fun readDominantSttsDelta(bytes: ByteArray, box: Box): Long? {
        if (box.dataStart + 8 > box.end) return null
        val entryCount = u32(bytes, box.dataStart + 4)
        var bestCount = 0L
        var bestDelta: Long? = null
        var offset = box.dataStart + 8
        var index = 0L
        while (index < entryCount && offset + 8 <= box.end) {
            val count = u32(bytes, offset)
            val delta = u32(bytes, offset + 4)
            if (delta > 0L && count > bestCount) {
                bestCount = count
                bestDelta = delta
            }
            offset += 8
            index++
        }
        return bestDelta
    }

    private fun readTrex(bytes: ByteArray, box: Box): Pair<Long, Long>? {
        if (box.dataStart + 16 > box.end) return null
        val duration = u32(bytes, box.dataStart + 12)
        return if (duration > 0L) u32(bytes, box.dataStart + 4) to duration else null
    }

    private fun readTfhd(bytes: ByteArray, box: Box): Pair<Long, Long>? {
        if (box.dataStart + 8 > box.end) return null
        val flags = ((bytes[box.dataStart + 1].toInt() and 0xFF) shl 16) or
            ((bytes[box.dataStart + 2].toInt() and 0xFF) shl 8) or
            (bytes[box.dataStart + 3].toInt() and 0xFF)
        if ((flags and TFHD_DEFAULT_SAMPLE_DURATION) == 0) return null
        var offset = box.dataStart + 8
        if ((flags and TFHD_BASE_DATA_OFFSET) != 0) offset += 8
        if ((flags and TFHD_SAMPLE_DESCRIPTION_INDEX) != 0) offset += 4
        if (offset + 4 > box.end) return null
        val duration = u32(bytes, offset)
        return if (duration > 0L) u32(bytes, box.dataStart + 4) to duration else null
    }

    private fun byteAt(bytes: ByteArray, offset: Int, end: Int): Int? =
        if (offset in 0 until minOf(end, bytes.size)) bytes[offset].toInt() and 0xFF else null

    /** Four printable ASCII characters (a box type or handler type), or null. */
    private fun fourCc(bytes: ByteArray, offset: Int, end: Int): String? {
        if (offset < 0 || offset + 4 > minOf(end, bytes.size)) return null
        val chars = CharArray(4)
        for (i in 0 until 4) {
            val b = bytes[offset + i].toInt() and 0xFF
            if (b < 0x20 || b > 0x7E) return null
            chars[i] = b.toChar()
        }
        return String(chars)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun u64(bytes: ByteArray, offset: Int): Long = (u32(bytes, offset) shl 32) or u32(bytes, offset + 4)
}
