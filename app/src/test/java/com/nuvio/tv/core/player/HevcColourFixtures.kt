package com.nuvio.tv.core.player

/**
 * The x265 parameter sets and SEI of `src/test/resources/hevc-colour` (see its README): NAL units with
 * their 2-byte header and emulation prevention bytes, keyed by case and NAL name; and the SPS-only
 * vectors of its `sps_more.txt`.
 */
object HevcColourFixtures {

    private val nals: Map<String, ByteArray> by lazy { read("hevc-colour/nals.txt") }

    /** `sps_more.txt`: SPS-only vectors from Media3's tests and hand-built ones, keyed by case. */
    private val moreSps: Map<String, ByteArray> by lazy { read("hevc-colour/sps_more.txt") }

    private fun read(resource: String): Map<String, ByteArray> {
        val text = HevcColourFixtures::class.java.classLoader!!
            .getResourceAsStream(resource)!!
            .bufferedReader()
            .use { it.readText() }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (key, hex) = line.split('=', limit = 2)
                key to hex.hexToBytes()
            }
    }

    val cases: List<String>
        get() = nals.keys.map { it.substringBefore('.') }.distinct()

    val moreSpsCases: List<String>
        get() = moreSps.keys.toList()

    fun moreSps(case: String): ByteArray = checkNotNull(moreSps[case]) { "no SPS $case" }.copyOf()

    fun vps(case: String): ByteArray = nal(case, "vps")
    fun sps(case: String): ByteArray = nal(case, "sps")
    fun pps(case: String): ByteArray = nal(case, "pps")
    fun sei(case: String): ByteArray? = nals["$case.sei"]?.copyOf()

    private fun nal(case: String, name: String): ByteArray =
        checkNotNull(nals["$case.$name"]) { "no $name for $case" }.copyOf()

    /**
     * An hvcC (ISO/IEC 14496-15 HEVCDecoderConfigurationRecord) holding [arrays] of (NAL type, NALs),
     * with 4-byte NAL lengths. Media3's HevcConfig reads only byte 21 of the 22-byte header.
     */
    fun hvcC(vararg arrays: Pair<Int, List<ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(1) // configurationVersion
        repeat(20) { out.write(0) }
        out.write(0xFF) // lengthSizeMinusOne = 3
        out.write(arrays.size)
        for ((type, units) in arrays) {
            out.write(0x80 or type)
            out.write(units.size ushr 8)
            out.write(units.size and 0xFF)
            for (unit in units) {
                out.write(unit.size ushr 8)
                out.write(unit.size and 0xFF)
                out.write(unit)
            }
        }
        return out.toByteArray()
    }

    /** The hvcC of a fixture case: VPS, SPS, PPS and, when the case has one, the prefix SEI. */
    fun hvcCOf(case: String, withSei: Boolean = true): ByteArray {
        val arrays = mutableListOf(
            32 to listOf(vps(case)),
            33 to listOf(sps(case)),
            34 to listOf(pps(case))
        )
        val sei = sei(case)
        if (withSei && sei != null) arrays += 39 to listOf(sei)
        return hvcC(*arrays.toTypedArray())
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
