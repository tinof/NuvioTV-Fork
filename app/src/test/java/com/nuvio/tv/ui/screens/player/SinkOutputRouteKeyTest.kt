package com.nuvio.tv.ui.screens.player

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The key the audio sink reads, driven through the controller's own fields. A sink lives on across a
 * source switch, and the pin is written for the new url, so nothing here may be read at build.
 */
class SinkOutputRouteKeyTest {

    private val hdmi = AudioOutputRoute("type:hdmi|name:shield", "HDMI", isBluetooth = false)
    private val idleBluetooth = AudioOutputRoute("type:bluetooth_a2dp|name:buds", "Buds", isBluetooth = true)
    private val otherBluetooth = AudioOutputRoute("type:bluetooth_a2dp|name:bar", "Bar", isBluetooth = true)

    private var url = STREAM_A
    private var pin: HdmiRouteUnderIdleBluetooth? = null
    private var detected: AudioOutputRoute? = idleBluetooth

    private val runtime = mockk<PlayerRuntimeController>(relaxed = true) {
        every { currentStreamUrl } answers { url }
        every { hdmiRouteUnderIdleBluetooth } answers { pin }
    }

    private fun pinFor(stream: String) = HdmiRouteUnderIdleBluetooth(stream, hdmi, idleBluetooth.key)

    @Before fun setUp() {
        mockkObject(AudioOutputRouteDetector)
        every { AudioOutputRouteDetector.detect(any()) } answers { detected }
    }

    @After fun tearDown() {
        unmockkObject(AudioOutputRouteDetector)
    }

    @Test fun aSourceSwitchOnALivePlayerMeetsThePinWrittenForTheNewUrl() {
        val read = runtime.sinkOutputRouteKey()
        assertEquals(idleBluetooth.key, read())

        url = STREAM_B
        pin = pinFor(STREAM_B)

        assertEquals(hdmi.key, read())
    }

    @Test fun aPinOfTheOtherStreamLeavesTheDetectedRoute() {
        val read = runtime.sinkOutputRouteKey()
        pin = pinFor(STREAM_B)
        assertEquals(idleBluetooth.key, read())

        url = STREAM_B
        pin = pinFor(STREAM_A)
        assertEquals(idleBluetooth.key, read())
    }

    @Test fun thePinOnlyStandsWhileTheIdleSinkIsDetected() {
        val read = runtime.sinkOutputRouteKey()
        pin = pinFor(STREAM_A)
        assertEquals(hdmi.key, read())

        detected = otherBluetooth
        assertEquals(otherBluetooth.key, read())

        detected = hdmi.copy(key = "type:hdmi|name:other")
        assertEquals("type:hdmi|name:other", read())
    }

    @Test fun noDetectedRouteIsNoKey() {
        val read = runtime.sinkOutputRouteKey()
        detected = null
        assertNull(read())

        pin = pinFor(STREAM_A)
        assertNull(read())
    }

    private companion object {
        const val STREAM_A = "https://example.test/a.mkv"
        const val STREAM_B = "https://example.test/b.mkv"
    }
}
