package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * Debug dump of the Dolby Vision RPU the decoder gets, switched on with `adb shell setprop [PROPERTY] <N>`
 * (unset or 0 = off) and read once per playback. It logs the RPU NAL of the first N video AUs of every
 * generation and of every AU whose RPU sets `scene_refresh_flag`, with the AU's presentation time, in the
 * [RpuDumpFormat] lines; `scripts/rpu_dump_to_bin.py` rebuilds an `RPU.bin` from them for `dovi_tool info`.
 *
 * A generation starts at an extractor `init` or `seek`; an `init` or `seek` before the generation's first AU
 * starts none (Media3's first load seeks right after `init`). Wraps the outermost ExtractorsFactory, inside
 * [com.nuvio.tv.core.player.thumbnail.PlaybackTap], so it sees what the decoder gets: native Profile 8 and 5
 * tracks as well as the tracks the Dolby Vision factory converts. Media3 can seek inside samples it already
 * extracted; those AUs are not extracted again and appear once. It only observes: every sample byte reaches
 * the delegate unchanged. While on, each HEVC / Dolby Vision AU is buffered once.
 */
@UnstableApi
object RpuDumpTap {
    const val PROPERTY = "debug.nuvio.rpu_dump"

    /**
     * [firstAus] <= 0 returns [delegate] itself. [log] takes one finished line (tests collect them); the
     * default writes it to logcat under [RpuDumpFormat.TAG].
     */
    fun wrap(
        delegate: ExtractorsFactory,
        firstAus: Int,
        reader: RpuSceneRefreshReader = DoviBridgeSceneRefreshReader,
        log: (String) -> Unit = { android.util.Log.i(RpuDumpFormat.TAG, it) }
    ): ExtractorsFactory =
        if (firstAus <= 0) delegate else RpuDumpExtractorsFactory(delegate, firstAus, reader, log)
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
 * cover the whole NAL with its `7C 01` header; the payload is lowercase hex. One generation line comes before
 * a generation's first record of a track: `v1 gen=<g> trk=<id> event=<init|seek> seekUs=<us|-1> n=<N>
 * codecs=<codecs|->`. Hex because `android.util.Base64` is a stub in JVM tests and `java.util.Base64` needs
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

@UnstableApi
private class RpuDumpExtractorsFactory(
    private val delegate: ExtractorsFactory,
    val firstAus: Int,
    val reader: RpuSceneRefreshReader,
    val log: (String) -> Unit
) : ExtractorsFactory {
    /** Per playback: records and generations are numbered across all of its extractors. */
    val seq = AtomicLong()
    val generations = AtomicInteger()

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { RpuDumpExtractor(it, this) }.toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { RpuDumpExtractor(it, this) }.toTypedArray()
}

/** Generation state lives here; init, seek and the track outputs all run on the extractor's loading thread. */
@UnstableApi
private class RpuDumpExtractor(
    private val delegate: Extractor,
    val factory: RpuDumpExtractorsFactory
) : Extractor {
    var generation = -1
        private set
    var event = "init"
        private set
    var seekUs = -1L
        private set

    /** An AU arrived in [generation]; the next init or seek starts a new one. */
    var used = true

    override fun init(output: ExtractorOutput) {
        startGeneration("init", -1L)
        delegate.init(RpuDumpExtractorOutput(output, this))
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        startGeneration("seek", timeUs)
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    override fun getSniffFailureDetails() = delegate.sniffFailureDetails

    private fun startGeneration(event: String, seekUs: Long) {
        if (used) {
            generation = factory.generations.getAndIncrement()
            used = false
        }
        this.event = event
        this.seekUs = seekUs
    }
}

@UnstableApi
private class RpuDumpExtractorOutput(
    private val delegate: ExtractorOutput,
    private val extractor: RpuDumpExtractor
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) RpuDumpTrackOutput(track, id, extractor) else track
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class RpuDumpTrackOutput(
    private val delegate: TrackOutput,
    private val trackId: Int,
    private val extractor: RpuDumpExtractor
) : TrackOutput by delegate {
    private companion object {
        /** Larger than any remux AU; a larger one is passed through without a dump. */
        const val MAX_BUFFER = 16 * 1024 * 1024
        const val NAL_LENGTH_FIELD = 4
    }

    private val factory = extractor.factory

    /** HEVC or Dolby Vision over HEVC: the codecs whose NAL type 62 is the RPU. */
    private var supported = false
    private var codecs: String? = null

    // Main sample part since the last sampleMetadata (plus carried offset bytes); the size prefix and
    // BlockAdditional data Matroska writes as the supplemental part are only counted.
    private var buf = ByteArray(0)
    private var len = 0
    private var sideBytes = 0
    private var overflow = false
    private var scratch = ByteArray(0)
    private val forward = ParsableByteArray()

    private var generation = -1
    private var au = 0

    // Same RPU bytes as the previous parsed one: same flag, without another libdovi parse.
    private var lastRpu: ByteArray? = null
    private var lastRefresh: Boolean? = null

    override fun format(format: Format) {
        val mime = format.sampleMimeType
        val lower = format.codecs.orEmpty().lowercase()
        supported = mime == MimeTypes.VIDEO_H265 ||
            (mime == MimeTypes.VIDEO_DOLBY_VISION && lower.startsWith("dvh"))
        codecs = format.codecs
        if (!supported) len = 0
        delegate.format(format)
    }

    @Throws(IOException::class)
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!supported || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            val written = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
            if (supported && written > 0) sideBytes += written
            return written
        }
        if (scratch.size < length) scratch = ByteArray(maxOf(length, 64 * 1024))
        val read = input.read(scratch, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        append(scratch, 0, read)
        forward.reset(scratch, read)
        delegate.sampleData(forward, read, sampleDataPart)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (supported && length > 0) {
            if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) append(data.data, data.position, length)
            else sideBytes += length
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        if (!supported) return
        if (generation != extractor.generation) {
            generation = extractor.generation
            au = 0
            factory.log(
                RpuDumpFormat.generationLine(
                    generation, trackId, extractor.event, extractor.seekUs, factory.firstAus, codecs
                )
            )
        }
        extractor.used = true
        val mainSize = size - sideBytes
        val end = len - offset
        val start = end - mainSize
        if (!overflow && mainSize > 0 && start >= 0 && end <= len &&
            (flags and C.BUFFER_FLAG_ENCRYPTED) == 0 && cryptoData == null
        ) {
            inspect(start, mainSize, timeUs)
        }
        au++
        // Bytes already written for the next sample (offset) stay buffered.
        if (!overflow && offset in 1..len && end >= 0) {
            System.arraycopy(buf, end, buf, 0, offset)
            len = offset
        } else {
            len = 0
        }
        sideBytes = 0
        overflow = false
    }

    private fun inspect(start: Int, size: Int, timeUs: Long) {
        val sample = if (start == 0) buf else buf.copyOfRange(start, start + size)
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
        val refresh = sceneRefresh(sample, nalOffset, nalLength)
        val why = when {
            au < factory.firstAus -> RpuDumpFormat.WHY_START
            refresh == true -> RpuDumpFormat.WHY_REFRESH
            else -> return
        }
        RpuDumpFormat.lines(
            factory.seq.getAndIncrement(), trackId, generation, au, timeUs, refresh, why,
            sample, nalOffset, nalLength
        ).forEach(factory.log)
    }

    private fun sceneRefresh(sample: ByteArray, offset: Int, length: Int): Boolean? {
        val last = lastRpu
        if (last != null && last.size == length && sameBytes(last, sample, offset)) return lastRefresh
        val refresh = factory.reader.sceneRefresh(sample, offset, length)
        lastRpu = sample.copyOfRange(offset, offset + length)
        lastRefresh = refresh
        return refresh
    }

    private fun sameBytes(a: ByteArray, b: ByteArray, bOffset: Int): Boolean {
        for (i in a.indices) if (a[i] != b[bOffset + i]) return false
        return true
    }

    private fun append(src: ByteArray, at: Int, count: Int) {
        if (overflow) return
        if (len + count > MAX_BUFFER) {        // runaway sample: no dump for it
            overflow = true
            len = 0
            return
        }
        if (buf.size < len + count) {
            var n = if (buf.isEmpty()) 256 * 1024 else buf.size
            while (n < len + count) n = n shl 1
            buf = buf.copyOf(n)
        }
        System.arraycopy(src, at, buf, len, count)
        len += count
    }
}
