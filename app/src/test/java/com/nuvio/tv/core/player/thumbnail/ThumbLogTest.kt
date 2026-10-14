package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-thumbnail line: slow decodes always, the rest only with the tag at DEBUG. */
class ThumbLogTest {

    @Test fun fastDecodeStaysOutOfLogcat() {
        assertFalse(ThumbLog.perThumbnail(decodeMs = 0, debugLoggable = false))
        assertFalse(ThumbLog.perThumbnail(decodeMs = 499, debugLoggable = false))
    }

    @Test fun slowDecodeIsAlwaysLogged() {
        assertTrue(ThumbLog.perThumbnail(decodeMs = 500, debugLoggable = false))
        assertTrue(ThumbLog.perThumbnail(decodeMs = 2_400, debugLoggable = false))
    }

    @Test fun debugTagLogsEveryThumbnail() {
        assertTrue(ThumbLog.perThumbnail(decodeMs = 40, debugLoggable = true))
    }
}
