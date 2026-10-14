package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The inventory line names whether a decoder only does secure playback. */
class DecoderInventoryLogTest {

    @Test fun secureOnlyDecoderSaysSo() {
        assertEquals(
            "OMX.Nvidia.h265.decode.secure video/hevc hw tunneled=true secure=true secureRequired=true max=4096x2304",
            DecoderInventoryLog.line(
                "OMX.Nvidia.h265.decode.secure", "video/hevc", "hw",
                tunneled = true, secure = true, secureRequired = true, extra = " max=4096x2304"
            )
        )
    }

    @Test fun regularDecoderKeepsTheOldFieldsInOrder() {
        assertEquals(
            "OMX.Nvidia.h265.decode video/hevc hw tunneled=true secure=false secureRequired=false",
            DecoderInventoryLog.line(
                "OMX.Nvidia.h265.decode", "video/hevc", "hw",
                tunneled = true, secure = false, secureRequired = false
            )
        )
    }
}
