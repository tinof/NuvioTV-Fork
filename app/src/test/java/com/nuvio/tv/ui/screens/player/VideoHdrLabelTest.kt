package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoHdrLabelTest {

    @Test
    fun `native dv on a dv decoder or unknown decoder reads dolby vision`() {
        assertEquals(
            "Dolby Vision",
            friendlyVideoHdrType(MimeTypes.VIDEO_DOLBY_VISION, C.COLOR_TRANSFER_ST2084, "OFF", false, true)
        )
        assertEquals(
            "Dolby Vision",
            friendlyVideoHdrType(MimeTypes.VIDEO_DOLBY_VISION, C.COLOR_TRANSFER_ST2084, "OFF", false, null)
        )
    }

    @Test
    fun `dv on the hevc decoder says the dv decoder was not used`() {
        assertEquals(
            "HDR10 (DV decoder not used)",
            friendlyVideoHdrType(MimeTypes.VIDEO_DOLBY_VISION, C.COLOR_TRANSFER_ST2084, "OFF", false, false)
        )
        assertEquals(
            "HDR10 (DV decoder not used)",
            friendlyVideoHdrType(MimeTypes.VIDEO_DOLBY_VISION, null, "DV81_LIBDOVI", true, false)
        )
    }

    @Test
    fun `stripped modes never mention the dv decoder`() {
        assertEquals(
            "HDR10",
            friendlyVideoHdrType(MimeTypes.VIDEO_DOLBY_VISION, C.COLOR_TRANSFER_ST2084, "STRIP_DV", false, false)
        )
        assertEquals(
            "HDR10",
            friendlyVideoHdrType(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084, "DV81_LIBDOVI", false, false)
        )
    }
}
