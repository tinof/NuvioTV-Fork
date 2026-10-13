package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import com.nuvio.tv.core.player.PassthroughBufferCaps
import com.nuvio.tv.core.player.SurroundFormatResolver
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioShieldPassTest {

    private val fiveOne = DirectSupport(ac3 = true, eac3 = true, trueHd = false, dts = true, dtsHd = false)
    private val anyLayout = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = false)

    // --- probe union (debug.nuvio.audio_probe_union) ---

    @Test
    fun `without the switch the decision reads the 5_1 probe and the API 31 pcm read`() {
        val inputs = SurroundFormatResolver.decisionInputs(fiveOne, null, anyLayout, 8, union = false)
        assertEquals(fiveOne, inputs.direct)
        assertNull(inputs.maxPcmChannels)
    }

    @Test
    fun `with the switch any layout counts and the plug count fills a missing pcm read`() {
        val inputs = SurroundFormatResolver.decisionInputs(fiveOne, null, anyLayout, 8, union = true)
        assertTrue(inputs.direct!!.trueHd)
        assertEquals(8, inputs.maxPcmChannels)
    }

    @Test
    fun `the api 31 pcm read wins over the plug count`() {
        val inputs = SurroundFormatResolver.decisionInputs(fiveOne, 2, anyLayout, 8, union = true)
        assertEquals(2, inputs.maxPcmChannels)
    }

    @Test
    fun `union keeps whichever probe answered`() {
        assertEquals(anyLayout, SurroundFormatResolver.decisionInputs(null, null, anyLayout, null, true).direct)
        assertEquals(fiveOne, SurroundFormatResolver.decisionInputs(fiveOne, null, null, null, true).direct)
        assertNull(SurroundFormatResolver.decisionInputs(null, null, null, null, true).direct)
    }

    // --- report ---

    @Test
    fun `report adds the heuristic lines only when given`() {
        val text = AudioCapabilityReport.format(
            supported = listOf("AC3"),
            absent = listOf("TrueHD"),
            negotiated = "unknown",
            surroundMode = "AUTO (setting, <API 31)",
            maxPcm = "unknown",
            layoutLine = "2.0: AC3 · 7.1: AC3 TrueHD",
            plugMaxChannels = 8
        )
        val lines = text.split("\n")
        assertEquals("Box opens in other layouts (heuristic): 2.0: AC3 · 7.1: AC3 TrueHD", lines[5])
        assertEquals("HDMI plug max channels (heuristic, any format): 8 ch", lines[6])
        assertTrue(lines.last().startsWith("HDMI device = "))
    }

    @Test
    fun `layout line names every layout, none when empty`() {
        assertEquals(
            "2.0: AC3 EAC3 · 7.1: none",
            AudioCapabilityReport.layoutLine(listOf("2.0" to listOf("AC3", "EAC3"), "7.1" to emptyList()))
        )
        assertNull(AudioCapabilityReport.layoutLine(emptyList()))
    }

    @Test
    fun `surround setting below api 31 is labelled with its source`() {
        assertEquals("AUTO (setting, <API 31)", AudioCapabilityReport.surroundSettingLabel(0))
        assertEquals("NEVER (setting, <API 31)", AudioCapabilityReport.surroundSettingLabel(1))
        assertEquals("ALWAYS (setting, <API 31)", AudioCapabilityReport.surroundSettingLabel(2))
        assertEquals("MANUAL (setting, <API 31)", AudioCapabilityReport.surroundSettingLabel(3))
        assertEquals("n/a (<API 31)", AudioCapabilityReport.surroundSettingLabel(-1))
    }

    // --- HDMI route under a Bluetooth pin ---

    @Test
    fun `hdmi routed track under a bluetooth pin below api 33`() {
        assertTrue(PassthroughOutputReturn.hdmiRoutedUnderBluetoothPin(30, true, AudioDeviceInfo.TYPE_HDMI))
        assertTrue(PassthroughOutputReturn.hdmiRoutedUnderBluetoothPin(30, true, AudioDeviceInfo.TYPE_HDMI_ARC))
        assertFalse(PassthroughOutputReturn.hdmiRoutedUnderBluetoothPin(33, true, AudioDeviceInfo.TYPE_HDMI))
        assertFalse(PassthroughOutputReturn.hdmiRoutedUnderBluetoothPin(30, false, AudioDeviceInfo.TYPE_HDMI))
        assertFalse(PassthroughOutputReturn.hdmiRoutedUnderBluetoothPin(30, true, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(PassthroughOutputReturn.hdmiRoutedUnderBluetoothPin(30, true, null))
    }

    @Test
    fun `a pcm only bluetooth build rebuilds when hdmi takes the track`() {
        val hdmi = setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)
        assertTrue(
            PassthroughOutputReturn.shouldRebuild(
                encoding = C.ENCODING_E_AC3_JOC,
                pinned = emptySet(),
                live = hdmi,
                alreadyBitstream = false,
                claimedByIec = false,
                policyDenies = false,
                forcedPcm = false,
                rebuildsSoFar = 0
            )
        )
    }

    private val hdmiRoute = AudioOutputRoute("type:hdmi|name:tv", "TV", isBluetooth = false)
    private val idleSpeaker = AudioOutputRoute("type:bluetooth_a2dp|name:speaker", "Speaker", isBluetooth = true)
    private val headphones = AudioOutputRoute("type:bluetooth_a2dp|name:headphones", "Headphones", isBluetooth = true)
    private val pin = HdmiRouteUnderIdleBluetooth("stream-a", hdmiRoute, idleSpeaker.key)

    @Test
    fun `the same idle bluetooth sink keeps the stream on its hdmi route`() {
        assertEquals(hdmiRoute, resolveRouteUnderIdleBluetooth(pin, "stream-a", idleSpeaker))
    }

    @Test
    fun `another sink, another stream or no pin reads detect as is`() {
        assertEquals(headphones, resolveRouteUnderIdleBluetooth(pin, "stream-a", headphones))
        assertEquals(idleSpeaker, resolveRouteUnderIdleBluetooth(pin, "stream-b", idleSpeaker))
        assertEquals(idleSpeaker, resolveRouteUnderIdleBluetooth(null, "stream-a", idleSpeaker))
        val builtIn = AudioOutputRoute("type:built_in_speaker|name:box", "Box", isBluetooth = false)
        assertEquals(builtIn, resolveRouteUnderIdleBluetooth(pin, "stream-a", builtIn))
        assertNull(resolveRouteUnderIdleBluetooth(pin, "stream-a", null))
    }

    @Test
    fun `only events for the idle bluetooth sink or no bluetooth sink keep the pin`() {
        assertTrue(idleBluetoothPinSurvives(pin, emptyList()))
        assertTrue(idleBluetoothPinSurvives(pin, listOf(idleSpeaker.key)))
        assertFalse(idleBluetoothPinSurvives(pin, listOf(headphones.key)))
        assertFalse(idleBluetoothPinSurvives(pin, listOf(idleSpeaker.key, headphones.key)))
    }

    // --- debug.nuvio.hbr_buffer_scale ---

    @Test
    fun `hbr scale multiplies truehd and dts-hd caps only`() {
        assertEquals(2 * 61_440, PassthroughBufferCaps.capBytes(C.ENCODING_DOLBY_TRUEHD))
        assertEquals(2 * 61_440 * 3, PassthroughBufferCaps.capBytes(C.ENCODING_DOLBY_TRUEHD, 3))
        assertEquals(4 * 30_720 * 2, PassthroughBufferCaps.capBytes(C.ENCODING_DTS_HD, 2))
        assertEquals(2 * 10_752, PassthroughBufferCaps.capBytes(C.ENCODING_E_AC3, 4))
    }

    @Test
    fun `hbr scale ignores values below one and clamps large ones`() {
        assertEquals(2 * 61_440, PassthroughBufferCaps.capBytes(C.ENCODING_DOLBY_TRUEHD, 0))
        assertEquals(2 * 61_440, PassthroughBufferCaps.capBytes(C.ENCODING_DOLBY_TRUEHD, -2))
        assertEquals(
            2 * 61_440 * PassthroughBufferCaps.MAX_HBR_SCALE,
            PassthroughBufferCaps.capBytes(C.ENCODING_DOLBY_TRUEHD, 1_000)
        )
    }
}
