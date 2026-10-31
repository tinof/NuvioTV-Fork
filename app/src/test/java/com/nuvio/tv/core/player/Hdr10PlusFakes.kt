package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuBuilder.Code
import com.nuvio.tv.core.player.DoviRpuBuilder.Handle
import com.nuvio.tv.core.player.DoviRpuBuilder.Hdr10PlusFrame
import com.nuvio.tv.core.player.DoviRpuBuilder.L1
import com.nuvio.tv.core.player.DoviRpuBuilder.Outcome
import com.nuvio.tv.core.player.DoviRpuBuilder.Params
import com.nuvio.tv.core.player.DoviRpuBuilder.SceneKey
import com.nuvio.tv.core.player.DoviRpuBuilder.ShotRpu
import com.nuvio.tv.core.player.DoviRpuBuilder.SourceRange

/**
 * A stand-in for libnuvio_rpu.so's HDR10+ calls (ROADMAP DV3.1), which the JVM tests cannot load.
 * Payload `B5 00 3C 00 01 04 01 s l` ([fakeHdr10PlusPayload]): scene key s, L1 avg 1000 + l (so two
 * scenes can share an L1). A payload whose provider code is not 0x003C, or whose application identifier
 * (byte 5) is not 4, is not HDR10+, as the library's T.35 identity check says; s = 0xEE fails
 * to parse. Each shot names its L1 avg ([fakeAvgOf]).
 */
internal class FakeHdr10PlusNative : Hdr10PlusNative {
    val created = mutableListOf<Params>()
    val released = mutableListOf<Handle>()
    val rendered = mutableListOf<L1>()
    var frames = 0
    var failRender = false

    /** The n-th create call and later ones fail. */
    var failCreateFrom = Int.MAX_VALUE

    override fun frame(payload: ByteArray): Outcome<Hdr10PlusFrame> {
        frames++
        if (payload.size < 9 || payload[2] != 0x3C.toByte() || payload[5] != 0x04.toByte()) {
            return Outcome.Failed(Code.NOT_HDR10_PLUS)
        }
        val scene = payload[7].toInt() and 0xFF
        if (scene == 0xEE) return Outcome.Failed(Code.HDR10_PLUS_PARSE)
        val l1 = L1(0, 1000 + (payload[8].toInt() and 0xFF), 3079)
        return Outcome.Ok(Hdr10PlusFrame(l1, SceneKey(scene.toLong(), 0)))
    }

    override fun create(params: Params): Outcome<Handle> {
        if (created.size + 1 >= failCreateFrom) return Outcome.Failed(Code.RPU)
        created += params
        return Outcome.Ok(Handle(created.size.toLong()))
    }

    override fun renderShot(handle: Handle, l1: L1): Outcome<ShotRpu> {
        if (failRender) return Outcome.Failed(Code.RPU)
        rendered += l1
        return Outcome.Ok(fakeShotOf(l1))
    }

    override fun release(handle: Handle) {
        released += handle
    }

    override fun sourceRange81(minNits: Double, maxNits: Double): SourceRange? =
        if (maxNits in 100.0..10_000.0 && minNits in 0.0..1.0) {
            SourceRange((minNits * 10_000).toInt(), maxNits.toInt())
        } else {
            null
        }
}

internal fun fakeHdr10PlusPayload(scene: Int, l1: Int = scene) =
    byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, scene.toByte(), l1.toByte())

/** A prefix SEI NAL with one T.35 message per payload (the payloads hold no 00 00 pair). */
internal fun fakeHdr10PlusSei(vararg payloads: ByteArray): ByteArray {
    var nal = byteArrayOf(0x4E, 0x01)
    for (p in payloads) nal += byteArrayOf(0x04, p.size.toByte()) + p
    return nal + byteArrayOf(0x80.toByte())
}

/** A prefix SEI NAL of raw messages (payloadType, RBSP payload), escaped as a coded NAL is (DV3.4). */
internal fun fakeSeiNal(vararg messages: Pair<Int, ByteArray>): ByteArray {
    val rbsp = java.io.ByteArrayOutputStream()
    for ((type, payload) in messages) {
        rbsp.write(type)
        rbsp.write(payload.size)
        rbsp.write(payload)
    }
    rbsp.write(0x80)
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf(0x4E, 0x01))
    var zeros = 0
    for (byte in rbsp.toByteArray()) {
        val value = byte.toInt() and 0xFF
        if (zeros >= 2 && value <= 3) {
            out.write(3)
            zeros = 0
        }
        out.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    return out.toByteArray()
}

/** Chromaticities of the mastering display SEI in 0.00002 units, in H.265's G, B, R order. */
internal val SEI_P3_GBR = listOf(13250 to 34500, 7500 to 3000, 34000 to 16000)
internal val SEI_D65 = 15635 to 16450

/** A mastering display colour volume payload: three pairs as given, the white point, max and min luminance. */
internal fun fakeMasteringPayload(
    pairs: List<Pair<Int, Int>> = SEI_P3_GBR,
    white: Pair<Int, Int> = SEI_D65,
    maxUnits: Long = 10_000_000,
    minUnits: Long = 1,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun u16(value: Int) {
        out.write(value ushr 8)
        out.write(value)
    }
    fun u32(value: Long) {
        for (shift in 24 downTo 0 step 8) out.write((value ushr shift).toInt() and 0xFF)
    }
    for ((x, y) in pairs + white) {
        u16(x)
        u16(y)
    }
    u32(maxUnits)
    u32(minUnits)
    return out.toByteArray()
}

internal fun fakeLightLevelPayload(maxCll: Int, maxFall: Int) =
    byteArrayOf((maxCll ushr 8).toByte(), maxCll.toByte(), (maxFall ushr 8).toByte(), maxFall.toByte())

/** The prefix SEI NAL of the DV3.1 title's S01E02 values: P3 D65, 0.0001..1000 nits, MaxCLL 108, MaxFALL 53. */
internal fun fakeStaticSei(maxCll: Int = 108, maxFall: Int = 53) =
    fakeSeiNal(137 to fakeMasteringPayload(), 144 to fakeLightLevelPayload(maxCll, maxFall))

/** A fake RPU that names its L1 avg: `7C 01 F0|F1 avg_hi avg_lo` (F1: scene refresh). */
internal fun fakeShotOf(l1: L1) = ShotRpu(
    byteArrayOf(0x7C, 0x01, 0xF1.toByte(), (l1.avgPq ushr 8).toByte(), l1.avgPq.toByte()),
    byteArrayOf(0x7C, 0x01, 0xF0.toByte(), (l1.avgPq ushr 8).toByte(), l1.avgPq.toByte()),
)

internal fun fakeAvgOf(shot: ShotRpu): Int = fakeAvgOfNal(shot.toByteArray(false))

/** The L1 avg of a fake RPU NAL, and whether it is the scene-refresh variant. */
internal fun fakeAvgOfNal(nal: ByteArray): Int = ((nal[3].toInt() and 0xFF) shl 8) or (nal[4].toInt() and 0xFF)

internal fun fakeIsRefresh(nal: ByteArray): Boolean = nal[2] == 0xF1.toByte()
