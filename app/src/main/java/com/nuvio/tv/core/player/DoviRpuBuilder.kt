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
     */
    data class Params(
        val profile: Profile,
        val cmVersion: CmVersion,
        val l1AvgCmVersion: CmVersion,
        val level6: Level6? = null,
        val level5: Level5? = null
    )

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
                    params.level5?.let(::level5Args)
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

    fun release(handle: Handle): Code =
        native("nativeRelease") { nativeRelease(handle.value) }?.let(Code::fromNative)
            ?: Code.UNAVAILABLE

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
        return (range == null || (range.minPq.fitsU16() && range.maxPq.fitsU16())) &&
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
        level5: IntArray?
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

    @JvmStatic
    private external fun nativeRelease(handle: Long): Int
}
