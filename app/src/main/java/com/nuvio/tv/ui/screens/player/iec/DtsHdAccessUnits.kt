package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.extractor.DtsUtil
import java.nio.ByteBuffer

/**
 * Splits a buffer of DTS access units that start with a core: a core frame, zero-padded to 4
 * bytes, optionally followed by an extension substream (ExSS) that carries the rest of the unit,
 * as DTS-HD MA writes them (the AAC-to-DTS transcode puts 2 to 4 such units in one buffer).
 *
 * ExSS header fields (big-endian bit offsets from its sync word 64 58 20 25, dts-hdma
 * docs/WRITER_SPEC.md 3.1): UserDefinedBits 32..39, nExtSSIndex 40..41, bHeaderSizeType 42, then
 * the header size (8 or 12 bits) and nuExtSSFsize - 1 in 16 bits at 51 (short form) or 20 bits at
 * 55 (long form).
 */
internal object DtsHdAccessUnits {

    const val EXSS_SYNC = 0x64582025
    private const val CORE_HEADER_BYTES = 16
    private const val EXSS_HEADER_BYTES = 12

    /** The units found from [start]; [consumed] bytes in all, [withExtension] of them with an ExSS. */
    class Walk(val sizes: IntArray, val withExtension: Int, val consumed: Int) {
        val count: Int get() = sizes.size
    }

    /**
     * Walks the units from absolute index [start] to [end] (the buffer's limit by default). It
     * stops at the first unit that does not start with a core sync word, has a size of 0 or less,
     * or does not fit.
     */
    fun walk(buffer: ByteBuffer, start: Int = 0, end: Int = buffer.limit()): Walk {
        val sizes = ArrayList<Int>(4)
        var withExtension = 0
        var offset = start
        val header = ByteArray(CORE_HEADER_BYTES)
        while (end - offset >= CORE_HEADER_BYTES) {
            for (i in 0 until CORE_HEADER_BYTES) header[i] = buffer.get(offset + i)
            if (DtsUtil.getFrameType(int32(buffer, offset)) != DtsUtil.FRAME_TYPE_CORE) break
            val core = runCatching { DtsUtil.getDtsFrameSize(header) }.getOrDefault(0)
            if (core <= 0) break
            var size = (core + 3) and 3.inv()
            if (offset + size + 4 <= end && int32(buffer, offset + size) == EXSS_SYNC) {
                if (offset + size + EXSS_HEADER_BYTES > end) break
                val exss = extensionSize(buffer, offset + size)
                if (exss <= 0) break
                size += exss
                if (offset + size > end) break
                withExtension++
            } else if (offset + size > end) {
                break
            }
            sizes.add(size)
            offset += size
        }
        return Walk(sizes.toIntArray(), withExtension, offset - start)
    }

    /** nuExtSSFsize of the ExSS at [offset]: its whole size in bytes. */
    fun extensionSize(buffer: ByteBuffer, offset: Int): Int {
        val longHeader = bits(buffer, offset, 42, 1) == 1L
        return if (longHeader) {
            bits(buffer, offset, 55, 20).toInt() + 1
        } else {
            bits(buffer, offset, 51, 16).toInt() + 1
        }
    }

    private fun int32(buffer: ByteBuffer, offset: Int): Int =
        ((buffer.get(offset).toInt() and 0xFF) shl 24) or
            ((buffer.get(offset + 1).toInt() and 0xFF) shl 16) or
            ((buffer.get(offset + 2).toInt() and 0xFF) shl 8) or
            (buffer.get(offset + 3).toInt() and 0xFF)

    private fun bits(buffer: ByteBuffer, offset: Int, position: Int, count: Int): Long {
        var value = 0L
        for (i in 0 until count) {
            val bit = position + i
            val byte = buffer.get(offset + (bit ushr 3)).toInt() and 0xFF
            value = (value shl 1) or ((byte ushr (7 - (bit and 7))) and 1).toLong()
        }
        return value
    }
}
