package com.nuvio.tv.data.mediaserver.silo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiloStartBodyAv1Test {

    private fun body(direct: Boolean, hardwareAv1: Boolean) = siloStartBody(
        installationId = "i",
        fileId = "f",
        profileId = "p",
        attemptId = "a",
        direct = direct,
        audioIndex = null,
        subtitleIndex = null,
        model = "SHIELD Android TV",
        appVersion = "1",
        hardwareAv1 = hardwareAv1
    )

    private fun JsonObject.videoCodecs(): List<String> {
        val capabilities = getValue("client_capabilities").jsonObject
        return capabilities.getValue("codecs_video").jsonArray.map { it.jsonPrimitive.content } +
            capabilities.getValue("codecs_video_hardware").jsonArray.map { it.jsonPrimitive.content } +
            capabilities.getValue("video_decode").jsonArray.map { it.jsonObject.getValue("codec").jsonPrimitive.content } +
            getValue("client_playback_context").jsonObject.getValue("deliveries").jsonObject
                .getValue("original_http").jsonObject
                .getValue("video_codecs").jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `direct playback asks for AV1 only with a hardware decoder`() {
        assertTrue("av1" in body(direct = true, hardwareAv1 = true).videoCodecs())
        val withoutHardware = body(direct = true, hardwareAv1 = false).videoCodecs()
        assertFalse("av1" in withoutHardware)
        assertTrue("hevc" in withoutHardware)
    }
}
