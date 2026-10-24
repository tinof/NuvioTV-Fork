package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Debug dump of the Dolby Vision RPU the decoder gets, switched on with `adb shell setprop [PROPERTY] <N>`
 * (unset or 0 = off) and read once per playback. It logs the RPU NAL of the first N video AUs of every
 * generation and of every AU whose RPU sets `scene_refresh_flag`, with the AU's presentation time, in the
 * [RpuDumpFormat] lines; `scripts/rpu_dump_to_bin.py` rebuilds an `RPU.bin` from them for `dovi_tool info`.
 *
 * The video renderer (`DecodeOrderPtsVideoRenderer`, and `AmlDvFelVideoRenderer` on the native FEL path) feeds
 * it every input buffer as it queues it, after Media3 has moved any Matroska BlockAdditional data out of the
 * sample, and every position reset, both in media time (the renderer's stream offset taken off), so it sees
 * what the decoder gets for every source (progressive, HLS, DASH): native Profile 8 and 5 tracks, the tracks
 * the Dolby Vision factory converts and, later, those DV2.7 converts. A generation starts at a
 * position reset (the start and every seek, also one Media3 serves from samples it already extracted) or a
 * change of the video Format; one with no AU yet starts none. `trk` is the Format's id when it is a number
 * (the Matroska track number), else 0. At a codec reconfiguration the buffer starts with the parameter sets,
 * which the RPU search skips. It only reads the buffer: position, limit and
 * bytes stay as they were. Runs on the playback thread; while on, each HEVC / Dolby Vision AU is copied once.
 */
@UnstableApi
internal class RpuDumpRecorder(
    private val firstAus: Int,
    private val reader: RpuSceneRefreshReader = DoviBridgeSceneRefreshReader,
    /** Takes one finished line (tests collect them); the default writes it to logcat under [RpuDumpFormat.TAG]. */
    private val log: (String) -> Unit = { android.util.Log.i(RpuDumpFormat.TAG, it) },
) {
    companion object {
        const val PROPERTY = "debug.nuvio.rpu_dump"

        /** Larger than any remux AU; a larger one is passed on without a dump. */
        private const val MAX_AU = 16 * 1024 * 1024
        private const val NAL_LENGTH_FIELD = 4
        /** Every position reset, the start included (Media3 starts playback with a seek to its position). */
        const val EVENT_SEEK = "seek"
        const val EVENT_FORMAT = "format"

        /** The recorder for [firstAus] AUs per generation, or null when it is off (<= 0). */
        fun create(firstAus: Int): RpuDumpRecorder? = if (firstAus > 0) RpuDumpRecorder(firstAus) else null
    }

    private var seq = 0L
    private var generations = 0
    private var generation = -1
    private var generationLogged = false

    /** An AU arrived in [generation]; the next reset or Format change starts a new one. */
    private var used = true
    private var event = EVENT_SEEK
    private var resetUs = -1L
    private var au = 0

    /** HEVC or Dolby Vision over HEVC: the codecs whose NAL type 62 is the RPU. */
    private var supported = false
    private var codecs: String? = null
    private var trackId = 0

    private var sample = ByteArray(0)

    // Same RPU bytes as the previous parsed one: same flag, without another libdovi parse.
    private var lastRpu: ByteArray? = null
    private var lastRefresh: Boolean? = null

    /** The renderer's position reset at media time [positionUs]: the start of playback or a seek. */
    fun onPositionReset(positionUs: Long) {
        startGeneration(EVENT_SEEK, positionUs)
    }

    /** The renderer's new input Format. */
    fun onFormat(format: Format?) {
        val mime = format?.sampleMimeType
        val nowSupported = mime == MimeTypes.VIDEO_H265 ||
            (mime == MimeTypes.VIDEO_DOLBY_VISION && format?.codecs.orEmpty().lowercase().startsWith("dvh"))
        val changed = nowSupported != supported || format?.codecs != codecs
        supported = nowSupported
        codecs = format?.codecs
        trackId = format?.id?.toIntOrNull() ?: 0
        if (changed) startGeneration(EVENT_FORMAT, -1L)
    }

    /**
     * One input buffer as the decoder gets it: [data] from its position to its limit (after `flip`), at the
     * media time [timeUs] (renderer time minus the stream offset). An [encrypted] AU is counted but not dumped.
     */
    fun onQueueInputBuffer(data: ByteBuffer?, timeUs: Long, encrypted: Boolean) {
        if (!supported || data == null) return
        if (generation < 0) startGeneration(event, resetUs)
        used = true
        if (!generationLogged) {
            log(RpuDumpFormat.generationLine(generation, trackId, event, resetUs, firstAus, codecs))
            generationLogged = true
        }
        val size = data.remaining()
        if (!encrypted && size in 1..MAX_AU) inspect(data, size, timeUs)
        au++
    }

    private fun startGeneration(event: String, resetUs: Long) {
        if (used) {
            generation = generations++
            used = false
            generationLogged = false
            au = 0
        } else if (event == EVENT_FORMAT) {
            // No AU yet: the reset that started this generation names it (the start's Format comes after it).
            return
        }
        this.event = event
        this.resetUs = resetUs
    }

    private fun inspect(data: ByteBuffer, size: Int, timeUs: Long) {
        if (sample.size < size) sample = ByteArray(maxOf(size, 256 * 1024))
        data.duplicate().get(sample, 0, size)
        val annexB = size >= 4 && sample[0].toInt() == 0 && sample[1].toInt() == 0 &&
            (sample[2].toInt() == 1 || (sample[2].toInt() == 0 && sample[3].toInt() == 1))
        val found = if (annexB) {
            HevcDvRpuStripper.findRpuNalAnnexB(sample, size)
        } else {
            HevcDvRpuStripper.findRpuNalLengthDelimited(sample, size, NAL_LENGTH_FIELD)
        } ?: return
        val nalOffset = found.first
        var nalLength = found.second
        // A NAL never ends in 0x00; trailing zero bytes before the next start code are not the RPU's.
        while (nalLength > 0 && sample[nalOffset + nalLength - 1].toInt() == 0) nalLength--
        if (nalLength < 3) return
        val refresh = sceneRefresh(nalOffset, nalLength)
        val why = when {
            au < firstAus -> RpuDumpFormat.WHY_START
            refresh == true -> RpuDumpFormat.WHY_REFRESH
            else -> return
        }
        RpuDumpFormat.lines(seq++, trackId, generation, au, timeUs, refresh, why, sample, nalOffset, nalLength)
            .forEach(log)
    }

    private fun sceneRefresh(offset: Int, length: Int): Boolean? {
        val last = lastRpu
        if (last != null && last.size == length && sameBytes(last, offset)) return lastRefresh
        val refresh = reader.sceneRefresh(sample, offset, length)
        lastRpu = sample.copyOfRange(offset, offset + length)
        lastRefresh = refresh
        return refresh
    }

    private fun sameBytes(a: ByteArray, offset: Int): Boolean {
        for (i in a.indices) if (a[i] != sample[offset + i]) return false
        return true
    }
}

/** Reads `scene_refresh_flag` from an RPU NAL (2-byte header included); null when it cannot be read. */
fun interface RpuSceneRefreshReader {
    fun sceneRefresh(nal: ByteArray, offset: Int, length: Int): Boolean?
}

/** libdovi's parse through [DoviBridge.getRpuDmInfo]; null on a stub build or an RPU without DM data. */
object DoviBridgeSceneRefreshReader : RpuSceneRefreshReader {
    override fun sceneRefresh(nal: ByteArray, offset: Int, length: Int): Boolean? =
        DoviBridge.getRpuDmInfo(nal, offset, length)?.sceneRefresh
}

/**
 * The dump's logcat lines, version `v1`. One record per dumped AU, split into parts of [CHUNK_BYTES]:
 * `v1 seq=<n> trk=<id> gen=<g> au=<i> pts=<us> sr=<0|1|?> why=<start|refresh> len=<bytes> crc=<crc32>
 * part=<i>/<n> <hex>`. `seq` counts records per playback from 0, so a lost record shows as a gap; `au` is the
 * AU's ordinal in its generation, counting every AU; `sr=?` means the flag could not be read; `why=start` is
 * the first-N rule, `why=refresh` a scene refresh after it. `len` and `crc` (CRC32, 8 lowercase hex digits)
 * cover the whole NAL with its `7C 01` header; the payload is lowercase hex. The recorder trims 0x00 bytes after the
 * NAL's last non-zero byte, which dovi_tool keeps as `trailing_zeroes`: if a byte compare with an extracted
 * `RPU.bin` fails on a padded RPU, look there first. One generation line comes before
 * a generation's first record of a track: `v1 gen=<g> trk=<id> event=<seek|format> seekUs=<us|-1> n=<N>
 * codecs=<codecs|->`: `seek` for a position reset (the start too) at `seekUs`, `format` for a Format change. Hex because `android.util.Base64` is a stub in JVM tests and `java.util.Base64` needs
 * API 26; a part of 1500 bytes keeps a line under logd's ~4 KB limit.
 */
internal object RpuDumpFormat {
    const val TAG = "NuvioRpuDump"
    const val CHUNK_BYTES = 1500
    const val WHY_START = "start"
    const val WHY_REFRESH = "refresh"

    fun lines(
        seq: Long,
        trk: Int,
        gen: Int,
        au: Int,
        ptsUs: Long,
        sceneRefresh: Boolean?,
        why: String,
        nal: ByteArray,
        offset: Int,
        length: Int
    ): List<String> {
        require(length > 0 && offset >= 0 && offset + length <= nal.size)
        val crc = CRC32().apply { update(nal, offset, length) }.value
        val sr = when (sceneRefresh) { true -> "1"; false -> "0"; null -> "?" }
        val head = "v1 seq=$seq trk=$trk gen=$gen au=$au pts=$ptsUs sr=$sr why=$why len=$length " +
            "crc=${"%08x".format(crc)}"
        val parts = (length + CHUNK_BYTES - 1) / CHUNK_BYTES
        return List(parts) { i ->
            val from = offset + i * CHUNK_BYTES
            val count = minOf(CHUNK_BYTES, offset + length - from)
            "$head part=${i + 1}/$parts ${hex(nal, from, count)}"
        }
    }

    fun generationLine(gen: Int, trk: Int, event: String, seekUs: Long, firstAus: Int, codecs: String?): String =
        "v1 gen=$gen trk=$trk event=$event seekUs=$seekUs n=$firstAus codecs=${codecs?.takeIf { it.isNotBlank() } ?: "-"}"

    private val DIGITS = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray, offset: Int, length: Int): String {
        val out = CharArray(length * 2)
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt() and 0xFF
            out[2 * i] = DIGITS[b ushr 4]
            out[2 * i + 1] = DIGITS[b and 0x0F]
        }
        return String(out)
    }
}
