package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROADMAP DV4.8: the manifest folder is reachable only from this device's network, so it stays on the
 * device. A profile import keeps local-only keys, so a remote snapshot cannot reset it either.
 */
class DvManifestSyncPolicyTest {
    @Test
    fun `the manifest toggle and folder are device-local player settings`() {
        listOf("dv_manifest_enabled", "dv_manifest_url").forEach { key ->
            assertTrue(key, shouldExcludePreferenceFromProfileSettingsSync("player_settings", key))
            assertFalse(key, shouldExcludePreferenceFromProfileSettingsSync("layout_settings", key))
        }
    }
}
