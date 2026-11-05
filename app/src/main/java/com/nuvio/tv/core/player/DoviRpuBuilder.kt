package com.nuvio.tv.core.player

import android.util.Log

/**
 * Dolby Vision Profile 8.1 / 8.4 RPUs for the conversion of tracks that carry no Dolby Vision
 * (ROADMAP DV2.2): hdr-analyze's `dovi_rpu_builder` in `libnuvio_rpu.so` (`native/rpu_builder`).
 * A library of its own, not part of `dovi_bridge` (`docs/dv-conversion/DESIGN.md` 2.7), built by
 * `scripts/build_rpu_builder.sh` for arm64-v8a only. Without it (the armeabi-v7a APK, a build
 * without cargo-ndk, JVM tests) [isAvailable] is false, conversion stays off and playback is
 * native.
 *
 * Nothing here throws. Every call returns an [Outcome] or a [Code]; a builder lives in the native
 * handle table until [release], and a stale [Handle] is [Code.UNKNOWN_HANDLE], never a crash.
 *
 * DV4.6 adds hdr-analyze's playback manifest (`dovi_rpu_builder::manifest`, `docs/PLAYBACK_MANIFEST.md`
 * there): [parseManifest], the candidate and track checks, the manifest's own builder, and the AU
 * matcher ([createMatcher], [matchAu], [resetMatcher]). A manifest and a matcher live in the same table
 * as builders, and one counter numbers all three, so a handle of the wrong kind is
 * [Code.UNKNOWN_HANDLE] too. [PlaybackManifestSession] owns them for one playback.
 */
internal object DoviRpuBuilder {
    private const val TAG = "DoviRpuBuilder"
    private const val LIB_NAME = "nuvio_rpu"

    /**
     * The library's result codes (`native/rpu_builder/src/api.rs` lists the same numbers), plus
     * two that only Kotlin returns.
     */
    enum class Code(val value: Int) {
        OK(0),
        // 1-7: dovi_rpu_builder's Error::code.
        INVALID_COMPOSER(1),
        BLOCK_LEVEL(2),
        OUT_OF_RANGE(3),
        /** The SEI is not an HDR10+ payload; `hdr10plus_tool` skips it, and so does the caller. */
        NOT_HDR10_PLUS(4),
        HDR10_PLUS_PARSE(5),
        HDR10_PLUS_NO_PEAK(6),
        /** The crate failed after its own checks passed: an internal failure. */
        RPU(7),
        /** The playback manifest does not parse, or does not describe this file, track or composer. */
        MANIFEST(8),
        /** A null or wrong-length array, an unknown enum value, a value that does not fit its field. */
        BAD_ARGUMENT(20),
        /** Never created, or already released. */
        UNKNOWN_HANDLE(21),
        /** A Java array could not be allocated or accessed in the library. */
        JNI_FAILURE(22),
        /** A panic, caught at the JNI boundary. */
        PANIC(99),
        /** Kotlin only: the library is not loaded, or the call into it threw. */
        UNAVAILABLE(-1),
        /** Kotlin only: a number the library should not return. */
        UNKNOWN(-2);

        companion object {
            /** The code of a number the library returned. */
            fun fromNative(value: Int): Code =
                entries.firstOrNull { it.value == value && it.value >= 0 } ?: UNKNOWN
        }
    }

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Failed(val code: Code) : Outcome<Nothing>
    }

    /** A builder in the native table. */
    @JvmInline
    value class Handle internal constructor(val value: Long)

    /** A parsed playback manifest in the native table (DV4.6). */
    @JvmInline
    value class ManifestHandle internal constructor(val value: Long)

    /** A manifest's AU matcher in the native table; it keeps its own copy of the AU table. */
    @JvmInline
    value class MatcherHandle internal constructor(val value: Long)

    /** The manifest coverage class of an injected AU. */
    enum class QualityClass { FULL, APPROXIMATE }

    /** Why the matcher rejected the manifest: `RejectReason::code` of the crate, stable. */
    enum class RejectReason(val value: Int) {
        UNKNOWN_PTS(1),
        SIZE(2),
        CRC(3),
        ORDER(4),
        LACED(5),
        PAST_END(6),
        /** Kotlin only: a number the library should not return. */
        UNKNOWN(-2);

        companion object {
            fun fromNative(value: Long): RejectReason =
                entries.firstOrNull { it.value.toLong() == value && it.value > 0 } ?: UNKNOWN
        }
    }

    /** The matcher's answer for one AU. */
    sealed interface AuVerdict {
        /** Write [l1] into the AU's RPU; [sceneRefresh] on the shot's first picture only. */
        data class Inject(
            val l1: L1,
            val sceneRefresh: Boolean,
            val shot: Int,
            val quality: QualityClass
        ) : AuVerdict

        /** The player's own RPU: waiting for an IRAP, a non-picture AU, or a picture not covered. */
        data object Fallback : AuVerdict

        /**
         * The manifest does not describe this stream, for the rest of the playback (resets too).
         * [decodeIndex] is the table's AU the read was checked against, when there is one.
         */
        data class Rejected(val reason: RejectReason, val decodeIndex: Long?) : AuVerdict
    }

    /** A SHA-256 digest, as the manifest checks take them: 32 raw bytes. */
    const val DIGEST_BYTES = 32

    /** `MAX_MANIFEST_BYTES` of the library: a longer manifest is refused unread. */
    const val MAX_MANIFEST_BYTES = 64 shl 20

    /** The length of `nativeMatcherNext`'s out array (`VERDICT_LEN`, `native/rpu_builder/src/manifest.rs`). */
    internal const val VERDICT_LEN = 9

    enum class HlgComposer(internal val value: Int) {
        /** The `dolby_vision` crate's Profile 8.4 preset (`dovi_tool generate`). */
        PRESET(0),
        /** The BT.2100-fitted composer, mkvdovi's default. */
        BT2100(1),
        /** The P8 playback canary: a TV that applies the composer shows the picture green. */
        CANARY(2)
    }

    enum class CmVersion(internal val value: Int) { V29(29), V40(40) }

    enum class PeakSource(internal val value: Int) {
        HISTOGRAM(0),
        HISTOGRAM_99(1),
        MAX_SCL(2),
        MAX_SCL_LUMINANCE(3)
    }

    /** L1 in 12-bit PQ codes. The library clamps it as `dovi_tool` does. */
    data class L1(val minPq: Int, val avgPq: Int, val maxPq: Int)

    /** L6 as `dovi_rpu_builder`'s `Level6` holds it: nits, the mastering minimum in 0.0001 nits. */
    data class Level6(
        val maxDisplayMasteringLuminance: Int,
        val minDisplayMasteringLuminance: Int,
        val maxContentLightLevel: Int,
        val maxFrameAverageLightLevel: Int
    )

    /** L5 active area offsets in pixels. */
    data class Level5(val left: Int, val right: Int, val top: Int, val bottom: Int)

    sealed interface Profile {
        /** 8.1; without [sourceRange] (PQ codes) the generator derives it from L6. */
        data class P81(val sourceRange: SourceRange? = null) : Profile
        data class P84(val composer: HlgComposer) : Profile
    }

    data class SourceRange(val minPq: Int, val maxPq: Int)

    /**
     * Everything in an RPU apart from L1. CM v4.0 uses the crate's default config and trim
     * targets; [l1AvgCmVersion] picks the L1 average floor (819 under V29, 1229 under V40).
     * [level6] null is mkvdovi's default (1000 / 0.005 nits, MaxCLL 1000, MaxFALL 400).
     * [sourcePrimaryIndex] is L9's under CM v4.0: 0 P3-D65, 1 BT.709, 2 BT.2020 (the crate's default).
     */
    data class Params(
        val profile: Profile,
        val cmVersion: CmVersion,
        val l1AvgCmVersion: CmVersion,
        val level6: Level6? = null,
        val level5: Level5? = null,
        val sourcePrimaryIndex: Int = PRIMARIES_BT2020
    )

    const val PRIMARIES_P3_D65 = 0
    const val PRIMARIES_BT709 = 1
    const val PRIMARIES_BT2020 = 2

    /**
     * Both RPU NALs of one shot (the `0x7C01` header and the escaped payload, no start code),
     * written once: the scene-refresh variant for the shot's first AU, the other for the rest.
     * The arrays are never handed out, only copied.
     */
    class ShotRpu internal constructor(
        private val sceneRefresh: ByteArray,
        private val noRefresh: ByteArray
    ) {
        private fun nal(sceneRefresh: Boolean) = if (sceneRefresh) this.sceneRefresh else noRefresh

        fun size(sceneRefresh: Boolean): Int = nal(sceneRefresh).size

        /** Copies the NAL to [destination] at [offset]: its size, or -1 when it does not fit. */
        fun copyInto(destination: ByteArray, offset: Int, sceneRefresh: Boolean): Int {
            val nal = nal(sceneRefresh)
            if (offset < 0 || offset > destination.size - nal.size) return -1
            nal.copyInto(destination, offset)
            return nal.size
        }

        /** Writes the NAL to [out] (which copies it): its size. */
        fun writeTo(out: java.io.OutputStream, sceneRefresh: Boolean): Int {
            val nal = nal(sceneRefresh)
            out.write(nal)
            return nal.size
        }

        fun toByteArray(sceneRefresh: Boolean): ByteArray = nal(sceneRefresh).copyOf()
    }

    private val nativeLoaded: Boolean by lazy { loadNativeLibrary() }

    val isLibraryLoaded: Boolean
        get() = nativeLoaded

    fun isAvailable(): Boolean = nativeLoaded

    fun versionOrNull(): String? = native("nativeVersion") { nativeVersion() }

    fun createStatic84(composer: HlgComposer): Outcome<Handle> =
        handleOutcome(native("nativeCreateStatic84") { nativeCreateStatic84(composer.value) })

    fun create(params: Params): Outcome<Handle> {
        if (!isValid(params)) return Outcome.Failed(Code.BAD_ARGUMENT)
        // 8.1 has no composer; the library checks the value but does not use it.
        val (profile, composer, sourceRange) = when (val p = params.profile) {
            is Profile.P81 ->
                Triple(81, HlgComposer.PRESET.value, p.sourceRange?.let(::sourceRangeArgs))
            is Profile.P84 -> Triple(84, p.composer.value, null)
        }
        return handleOutcome(
            native("nativeCreate") {
                nativeCreate(
                    profile,
                    composer,
                    params.cmVersion.value,
                    params.l1AvgCmVersion.value,
                    params.level6?.let(::level6Args),
                    sourceRange,
                    params.level5?.let(::level5Args),
                    params.sourcePrimaryIndex
                )
            }
        )
    }

    /** `STATIC_84_L1` (0 / 1283 / 3079), the L1 of every AU of a static 8.4 title. */
    fun static84L1(): Outcome<L1> {
        val values = native("nativeStatic84L1") { nativeStatic84L1() }
            ?: return Outcome.Failed(Code.UNAVAILABLE)
        if (values.size != 3) return Outcome.Failed(Code.UNKNOWN)
        return Outcome.Ok(L1(values[0], values[1], values[2]))
    }

    /** One shot's RPUs: [l1] written with the builder's params, in both scene-refresh variants. */
    fun renderShot(handle: Handle, l1: L1): Outcome<ShotRpu> {
        if (!isValid(l1)) return Outcome.Failed(Code.BAD_ARGUMENT)
        if (!nativeLoaded) return Outcome.Failed(Code.UNAVAILABLE)
        // Stays UNKNOWN if the library returns null without setting it.
        val codeOut = intArrayOf(Code.UNKNOWN.value)
        val nals = try {
            nativeRenderShot(handle.value, l1.minPq, l1.avgPq, l1.maxPq, codeOut)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeRenderShot failed: ${t.message}")
            return Outcome.Failed(Code.UNAVAILABLE)
        } ?: return Outcome.Failed(Code.fromNative(codeOut[0]))
        val sceneRefresh = nals.getOrNull(0)
        val noRefresh = nals.getOrNull(1)
        if (nals.size != 2 || sceneRefresh == null || noRefresh == null) {
            return Outcome.Failed(Code.UNKNOWN)
        }
        return Outcome.Ok(ShotRpu(sceneRefresh, noRefresh))
    }

    /**
     * L1 of one HDR10+ SEI payload: the T.35 RBSP (emulation prevention removed) from the country
     * code `0xB5`, as hdr-analyze `docs/RPU_BUILDER.md` states. [Code.NOT_HDR10_PLUS] means the
     * SEI is something else and is skipped. Use the RPU's L1 average CM version as [cmVersion].
     */
    fun hdr10PlusL1(payload: ByteArray, peakSource: PeakSource, cmVersion: CmVersion): Outcome<L1> {
        val out = IntArray(3)
        val code = native("nativeHdr10PlusL1") {
            nativeHdr10PlusL1(payload, peakSource.value, cmVersion.value, out)
        } ?: return Outcome.Failed(Code.UNAVAILABLE)
        if (code != Code.OK.value) return Outcome.Failed(Code.fromNative(code))
        return Outcome.Ok(L1(out[0], out[1], out[2]))
    }

    /**
     * The identity of an HDR10+ payload's `SceneKey`: the fields `dovi_tool`'s scene rule compares.
     * Equal for every payload of one scene; valid within one library build only.
     */
    data class SceneKey(val high: Long, val low: Long)

    /** One HDR10+ payload: its L1 (as [hdr10PlusL1]) and its [SceneKey]. */
    data class Hdr10PlusFrame(val l1: L1, val key: SceneKey)

    /**
     * [hdr10PlusL1] plus the payload's [SceneKey], from one parse (ROADMAP DV3.1). The codes are
     * those of [hdr10PlusL1].
     */
    fun hdr10PlusFrame(
        payload: ByteArray,
        peakSource: PeakSource,
        cmVersion: CmVersion
    ): Outcome<Hdr10PlusFrame> {
        val out = IntArray(7)
        val code = native("nativeHdr10PlusFrame") {
            nativeHdr10PlusFrame(payload, peakSource.value, cmVersion.value, out)
        } ?: return Outcome.Failed(Code.UNAVAILABLE)
        if (code != Code.OK.value) return Outcome.Failed(Code.fromNative(code))
        return Outcome.Ok(hdr10PlusFrameOf(out))
    }

    /**
     * The Profile 8.1 source range `mkvdovi` derives from the mastering display ([minNits],
     * [maxNits]): `source_range_81`. Null when the values are not a plausible mastering display
     * (the generator's L6 lookup then decides, as `P81(null)`), or without the library.
     */
    fun sourceRange81(minNits: Double, maxNits: Double): SourceRange? {
        val out = IntArray(2)
        val code = native("nativeSourceRange81") { nativeSourceRange81(minNits, maxNits, out) }
        if (code != Code.OK.value || out[0] < 0 || out[1] < 0) return null
        return SourceRange(out[0], out[1])
    }

    /** `[min, avg, max, k0, k1, k2, k3]` of `nativeHdr10PlusFrame`. */
    internal fun hdr10PlusFrameOf(out: IntArray) = Hdr10PlusFrame(
        L1(out[0], out[1], out[2]),
        SceneKey(halves(out[3], out[4]), halves(out[5], out[6]))
    )

    private fun halves(high: Int, low: Int): Long =
        (high.toLong() shl 32) or (low.toLong() and 0xFFFF_FFFFL)

    fun release(handle: Handle): Code = releaseValue(handle.value)

    private fun releaseValue(value: Long): Code =
        native("nativeRelease") { nativeRelease(value) }?.let(Code::fromNative)
            ?: Code.UNAVAILABLE

    /** The manifest's UTF-8 JSON, parsed and checked by the crate's strict parser ([Code.MANIFEST]). */
    fun parseManifest(json: ByteArray): Outcome<ManifestHandle> {
        if (json.size > MAX_MANIFEST_BYTES) return Outcome.Failed(Code.BAD_ARGUMENT)
        return when (val outcome = handleOutcome(native("nativeManifestParse") { nativeManifestParse(json) })) {
            is Outcome.Ok -> Outcome.Ok(ManifestHandle(outcome.value.value))
            is Outcome.Failed -> outcome
        }
    }

    /**
     * The candidate step: the file's size, the SHA-256 of its first and last MiB, and the `lookup_key`
     * the player computed from them and fetched the manifest by. [Code.MANIFEST] when any differs.
     */
    fun checkManifestSource(
        manifest: ManifestHandle,
        sizeBytes: Long,
        headSha256: ByteArray,
        tailSha256: ByteArray,
        lookupKey: ByteArray
    ): Code {
        if (sizeBytes < 0 || !isDigest(headSha256) || !isDigest(tailSha256) || !isDigest(lookupKey)) {
            return Code.BAD_ARGUMENT
        }
        return code(native("nativeManifestCheckSource") {
            nativeManifestCheckSource(manifest.value, sizeBytes, headSha256, tailSha256, lookupKey)
        })
    }

    /**
     * The track step: the SHA-256 of the track's CodecPrivate, its dimensions and its time base
     * ([timeBaseNum] / [timeBaseDen] seconds per tick; for Matroska `TimestampScale / 1_000_000_000`,
     * which the library reduces to lowest terms before comparing). [Code.MANIFEST] when any differs.
     */
    fun checkManifestTrack(
        manifest: ManifestHandle,
        codecPrivateSha256: ByteArray,
        width: Int,
        height: Int,
        timeBaseNum: Int,
        timeBaseDen: Int
    ): Code {
        if (!isDigest(codecPrivateSha256) || width < 0 || height < 0) return Code.BAD_ARGUMENT
        return code(native("nativeManifestCheckTrack") {
            nativeManifestCheckTrack(manifest.value, codecPrivateSha256, width, height, timeBaseNum, timeBaseDen)
        })
    }

    /** [Code.MANIFEST] unless the manifest is 8.4 with the [composer] the player writes. */
    fun checkManifestComposer(manifest: ManifestHandle, composer: HlgComposer): Code =
        code(native("nativeManifestCheckComposer") { nativeManifestCheckComposer(manifest.value, composer.value) })

    /** A builder for the manifest's `rpu`: its RPUs equal mkvdovi's for the same title. Release it. */
    fun createManifestBuilder(manifest: ManifestHandle): Outcome<Handle> =
        handleOutcome(native("nativeManifestCreateBuilder") { nativeManifestCreateBuilder(manifest.value) })

    /** A matcher for the manifest, waiting for its first IRAP. It outlives a released manifest. */
    fun createMatcher(manifest: ManifestHandle): Outcome<MatcherHandle> =
        when (val outcome = handleOutcome(native("nativeMatcherCreate") { nativeMatcherCreate(manifest.value) })) {
            is Outcome.Ok -> Outcome.Ok(MatcherHandle(outcome.value.value))
            is Outcome.Failed -> outcome
        }

    /**
     * The verdict for the next AU in decode order: [pts] in the track's ticks (cluster plus block
     * timecode), the AU's bytes as the container stores them ([length] bytes of [data] from [offset]),
     * and whether its block was [laced]. A refused call ([Code.BAD_ARGUMENT]) leaves the matcher as it
     * was; any other failure may come after the matcher moved on, so stop using the manifest.
     */
    fun matchAu(
        matcher: MatcherHandle,
        pts: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
        laced: Boolean
    ): Outcome<AuVerdict> {
        if (offset < 0 || length < 0 || offset > data.size - length) return Outcome.Failed(Code.BAD_ARGUMENT)
        val out = LongArray(VERDICT_LEN)
        val code = native("nativeMatcherNext") {
            nativeMatcherNext(matcher.value, pts, data, offset, length, laced, out)
        } ?: return Outcome.Failed(Code.UNAVAILABLE)
        if (code != Code.OK.value) return Outcome.Failed(Code.fromNative(code))
        return auVerdictOf(out)?.let { Outcome.Ok(it) } ?: Outcome.Failed(Code.UNKNOWN)
    }

    /** After a seek or a flush: the matcher waits for an IRAP again. A rejection stays. */
    fun resetMatcher(matcher: MatcherHandle): Code =
        code(native("nativeMatcherReset") { nativeMatcherReset(matcher.value) })

    fun releaseManifest(manifest: ManifestHandle): Code = releaseValue(manifest.value)

    fun releaseMatcher(matcher: MatcherHandle): Code = releaseValue(matcher.value)

    /** The verdict in `nativeMatcherNext`'s out array, or null when the fields make no sense. */
    internal fun auVerdictOf(out: LongArray): AuVerdict? {
        if (out.size != VERDICT_LEN) return null
        return when (out[0]) {
            0L -> {
                val l1 = out.slice(1..3)
                if (l1.any { it !in 0L..U16_MAX.toLong() } || out[4] !in 0L..1L || out[5] !in 0L..Int.MAX_VALUE.toLong()) {
                    return null
                }
                val quality = when (out[6]) {
                    0L -> QualityClass.FULL
                    1L -> QualityClass.APPROXIMATE
                    else -> return null
                }
                AuVerdict.Inject(
                    L1(l1[0].toInt(), l1[1].toInt(), l1[2].toInt()),
                    sceneRefresh = out[4] == 1L,
                    shot = out[5].toInt(),
                    quality = quality
                )
            }
            1L -> AuVerdict.Fallback
            2L -> AuVerdict.Rejected(RejectReason.fromNative(out[7]), out[8].takeIf { it >= 0 })
            else -> null
        }
    }

    internal fun isDigest(bytes: ByteArray) = bytes.size == DIGEST_BYTES

    private fun code(value: Int?): Code = value?.let(Code::fromNative) ?: Code.UNAVAILABLE

    /** A handle the library returned (> 0), or its negated code. */
    internal fun handleOutcome(value: Long?): Outcome<Handle> = when {
        value == null -> Outcome.Failed(Code.UNAVAILABLE)
        value > 0 -> Outcome.Ok(Handle(value))
        // 0 is neither a handle nor a failure; Code.OK must not come back as a failure.
        value == 0L || value < -Int.MAX_VALUE -> Outcome.Failed(Code.UNKNOWN)
        else -> Outcome.Failed(Code.fromNative((-value).toInt()))
    }

    private const val U16_MAX = 0xFFFF

    private fun Int.fitsU16() = this in 0..U16_MAX

    /** The checks the library makes on the field types (code 20), made before the call. */
    internal fun isValid(l1: L1): Boolean =
        l1.minPq.fitsU16() && l1.avgPq.fitsU16() && l1.maxPq.fitsU16()

    internal fun isValid(params: Params): Boolean {
        val range = (params.profile as? Profile.P81)?.sourceRange
        val level6 = params.level6
        val level5 = params.level5
        return params.sourcePrimaryIndex in PRIMARIES_P3_D65..PRIMARIES_BT2020 &&
            (range == null || (range.minPq.fitsU16() && range.maxPq.fitsU16())) &&
            (level6 == null || level6Args(level6).all { it >= 0 }) &&
            (level5 == null || level5Args(level5).all { it.fitsU16() })
    }

    /** `[max_dml, min_dml, max_cll, max_fall]`, the order of `Level6` and of `nativeCreate`. */
    internal fun level6Args(level6: Level6) = intArrayOf(
        level6.maxDisplayMasteringLuminance,
        level6.minDisplayMasteringLuminance,
        level6.maxContentLightLevel,
        level6.maxFrameAverageLightLevel
    )

    internal fun level5Args(level5: Level5) =
        intArrayOf(level5.left, level5.right, level5.top, level5.bottom)

    internal fun sourceRangeArgs(range: SourceRange) = intArrayOf(range.minPq, range.maxPq)

    /** [call]'s value, or null when the library is not loaded or the call threw. */
    private inline fun <T> native(name: String, call: () -> T): T? {
        if (!nativeLoaded) return null
        return try {
            call()
        } catch (t: Throwable) {
            Log.w(TAG, "$name failed: ${t.message}")
            null
        }
    }

    private fun loadNativeLibrary(): Boolean {
        return try {
            System.loadLibrary(LIB_NAME)
            Log.i(TAG, "Loaded native library: $LIB_NAME")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load native library $LIB_NAME: ${t.message}")
            false
        }
    }

    @JvmStatic
    private external fun nativeVersion(): String?

    // A handle > 0, or the negated code.
    @JvmStatic
    private external fun nativeCreateStatic84(composer: Int): Long

    @JvmStatic
    private external fun nativeCreate(
        profile: Int,
        composer: Int,
        cmVersion: Int,
        l1AvgCmVersion: Int,
        level6: IntArray?,
        sourceRange: IntArray?,
        level5: IntArray?,
        sourcePrimaries: Int
    ): Long

    @JvmStatic
    private external fun nativeStatic84L1(): IntArray?

    // [sceneRefresh, noRefresh], or null with codeOut[0] set.
    @JvmStatic
    private external fun nativeRenderShot(
        handle: Long,
        minPq: Int,
        avgPq: Int,
        maxPq: Int,
        codeOut: IntArray
    ): Array<ByteArray>?

    @JvmStatic
    private external fun nativeHdr10PlusL1(
        payload: ByteArray,
        peakSource: Int,
        cmVersion: Int,
        out: IntArray
    ): Int

    // [min, avg, max, k0, k1, k2, k3]; the code.
    @JvmStatic
    private external fun nativeHdr10PlusFrame(
        payload: ByteArray,
        peakSource: Int,
        cmVersion: Int,
        out: IntArray
    ): Int

    // [min, max], or [-1, -1] for no range; the code.
    @JvmStatic
    private external fun nativeSourceRange81(minNits: Double, maxNits: Double, out: IntArray): Int

    // A builder, a manifest or a matcher.
    @JvmStatic
    private external fun nativeRelease(handle: Long): Int

    // A manifest handle > 0, or the negated code.
    @JvmStatic
    private external fun nativeManifestParse(json: ByteArray): Long

    @JvmStatic
    private external fun nativeManifestCheckSource(
        manifest: Long,
        sizeBytes: Long,
        headSha256: ByteArray,
        tailSha256: ByteArray,
        lookupKey: ByteArray
    ): Int

    @JvmStatic
    private external fun nativeManifestCheckTrack(
        manifest: Long,
        codecPrivateSha256: ByteArray,
        width: Int,
        height: Int,
        timeBaseNum: Int,
        timeBaseDen: Int
    ): Int

    @JvmStatic
    private external fun nativeManifestCheckComposer(manifest: Long, composer: Int): Int

    // A builder handle > 0, or the negated code.
    @JvmStatic
    private external fun nativeManifestCreateBuilder(manifest: Long): Long

    // A matcher handle > 0, or the negated code.
    @JvmStatic
    private external fun nativeMatcherCreate(manifest: Long): Long

    // The verdict's VERDICT_LEN fields in out; the code.
    @JvmStatic
    private external fun nativeMatcherNext(
        matcher: Long,
        pts: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
        laced: Boolean,
        out: LongArray
    ): Int

    @JvmStatic
    private external fun nativeMatcherReset(matcher: Long): Int
}
