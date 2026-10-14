package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which NET_CONN lines reach logcat; the in-app ring gets all of them. */
class NetConnLogcatTest {

    @Test fun fastSuccessfulCallStaysOutOfLogcat() {
        assertFalse(netConnToLogcat("ok", headersMs = 180, ttfbMs = 90, code = 206, debugLoggable = false))
        assertFalse(netConnToLogcat("ok", headersMs = 999, ttfbMs = 999, code = 200, debugLoggable = false))
    }

    @Test fun pooledCallWithUnknownTimingsStaysOut() {
        assertFalse(netConnToLogcat("ok", headersMs = 40, ttfbMs = -1, code = 206, debugLoggable = false))
    }

    @Test fun slowHeadersOrTtfbIsLogged() {
        assertTrue(netConnToLogcat("ok", headersMs = 1_000, ttfbMs = 200, code = 206, debugLoggable = false))
        assertTrue(netConnToLogcat("ok", headersMs = 400, ttfbMs = 1_000, code = 206, debugLoggable = false))
    }

    @Test fun httpErrorIsLoggedEvenWhenFast() {
        assertTrue(netConnToLogcat("ok", headersMs = 120, ttfbMs = 60, code = 403, debugLoggable = false))
        assertTrue(netConnToLogcat("ok", headersMs = 90, ttfbMs = 40, code = 503, debugLoggable = false))
    }

    @Test fun failedOrCanceledIsLoggedEvenWithoutTimings() {
        assertTrue(netConnToLogcat("failed", headersMs = -1, ttfbMs = -1, code = -1, debugLoggable = false))
        assertTrue(netConnToLogcat("canceled", headersMs = 12, ttfbMs = -1, code = -1, debugLoggable = false))
    }

    @Test fun debugTagLogsEveryLine() {
        assertTrue(netConnToLogcat("ok", headersMs = 30, ttfbMs = 10, code = 206, debugLoggable = true))
    }
}
