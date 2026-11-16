package com.nuvio.tv.core.player.dvmkv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatroskaResyncLogTest {

    @Test
    fun `the skip line names the bytes skipped and both positions`() {
        assertEquals(
            "MKV_RESYNC: skipped 4096 bytes from byte 1000000 to the cluster at byte 1004096",
            MatroskaResyncLog.skippedLine(1_000_000L, 1_004_096L),
        )
    }

    @Test
    fun `the cluster line gives the first cluster time in ms`() {
        assertEquals(
            "MKV_RESYNC: first cluster after the 4096-byte skip starts at 4500 ms",
            MatroskaResyncLog.clusterTimeLine(4_096L, 4_500_999L),
        )
    }

    @Test
    fun `a cluster time is logged once after a resync and not otherwise`() {
        val log = MatroskaResyncLog("test")
        assertNull(log.onClusterTime(1_000_000L))
        log.onSkipped(2_000L, 2_512L)
        assertEquals(
            "MKV_RESYNC: first cluster after the 512-byte skip starts at 4187 ms",
            log.onClusterTime(4_187_000L),
        )
        assertNull(log.onClusterTime(5_187_000L))
    }

    @Test
    fun `a seek after a resync drops the pending cluster time`() {
        val log = MatroskaResyncLog("test")
        log.onSkipped(2_000L, 2_512L)
        log.onSeek()
        assertNull(log.onClusterTime(9_000_000L))
    }
}
