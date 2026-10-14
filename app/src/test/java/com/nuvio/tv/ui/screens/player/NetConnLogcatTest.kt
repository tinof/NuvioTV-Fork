package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which NET_CONN lines reach logcat; the in-app ring gets all of them. */
class NetConnLogcatTest {

    @Test fun fastSuccessfulCallStaysOutOfLogcat() {
        assertFalse(netConnToLogcat("ok", totalMs = 180, ttfbMs = 90, debugLoggable = false))
        assertFalse(netConnToLogcat("ok", totalMs = 999, ttfbMs = 999, debugLoggable = false))
    }

    @Test fun pooledCallWithUnknownTimingsStaysOut() {
        assertFalse(netConnToLogcat("ok", totalMs = 40, ttfbMs = -1, debugLoggable = false))
    }

    @Test fun slowTotalOrTtfbIsLogged() {
        assertTrue(netConnToLogcat("ok", totalMs = 1_000, ttfbMs = 200, debugLoggable = false))
        assertTrue(netConnToLogcat("ok", totalMs = 400, ttfbMs = 1_000, debugLoggable = false))
    }

    @Test fun failedOrCanceledIsLoggedEvenWithoutTimings() {
        assertTrue(netConnToLogcat("failed", totalMs = -1, ttfbMs = -1, debugLoggable = false))
        assertTrue(netConnToLogcat("canceled", totalMs = 12, ttfbMs = -1, debugLoggable = false))
    }

    @Test fun debugTagLogsEveryLine() {
        assertTrue(netConnToLogcat("ok", totalMs = 30, ttfbMs = 10, debugLoggable = true))
    }
}
