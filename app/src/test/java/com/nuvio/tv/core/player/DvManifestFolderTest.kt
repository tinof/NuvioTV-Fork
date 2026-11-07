package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ROADMAP DV4.8: the saved manifest folder, and the setprop that wins over it. */
class DvManifestFolderTest {

    @Test
    fun `a saved folder is an http or https URL with a host`() {
        assertEquals("https://subs.example/dv/", DvManifestFolder.normalize("  https://subs.example/dv/ "))
        assertEquals("http://192.0.2.7:8080/m", DvManifestFolder.normalize("http://192.0.2.7:8080/m"))
        assertEquals("HTTPS://subs.example/dv", DvManifestFolder.normalize("HTTPS://subs.example/dv"))
    }

    @Test
    fun `a saved folder rejects other schemes, no host, a query or a fragment`() {
        listOf(
            "", "   ", "subs.example/dv", "ftp://subs.example/dv", "file:///sdcard/dv",
            "https://", "https:///dv", "https://subs.example/dv?key=1", "https://subs.example/dv#x",
            "https://subs example/dv",
        ).forEach { assertNull(it, DvManifestFolder.normalize(it)) }
    }

    @Test
    fun `the setprop wins over the saved folder, also when the setting is off`() {
        assertEquals(
            DvManifestFolder.Resolved("http://lan:8/m", fromSetting = false),
            DvManifestFolder.resolve("http://lan:8/m", savedEnabled = true, savedUrl = "https://subs.example/dv/"),
        )
        assertEquals(
            DvManifestFolder.Resolved("http://lan:8/m", fromSetting = false),
            DvManifestFolder.resolve("http://lan:8/m", savedEnabled = false, savedUrl = ""),
        )
    }

    @Test
    fun `the setprop is trimmed and keeps the debug schemes`() {
        assertEquals("http://lan:8/m", DvManifestFolder.resolve("  http://lan:8/m \n", false, "")?.url)
        assertEquals("file:///sdcard/dv", DvManifestFolder.resolve("file:///sdcard/dv", false, "")?.url)
    }

    @Test
    fun `a blank setprop falls through to the saved folder`() {
        val saved = DvManifestFolder.Resolved("https://subs.example/dv/", fromSetting = true)
        assertEquals(saved, DvManifestFolder.resolve(null, savedEnabled = true, savedUrl = "https://subs.example/dv/"))
        assertEquals(saved, DvManifestFolder.resolve("   ", savedEnabled = true, savedUrl = "https://subs.example/dv/"))
    }

    @Test
    fun `no lookup when the setting is off, has no folder or holds an invalid one`() {
        assertNull(DvManifestFolder.resolve(null, savedEnabled = false, savedUrl = "https://subs.example/dv/"))
        assertNull(DvManifestFolder.resolve(null, savedEnabled = true, savedUrl = ""))
        assertNull(DvManifestFolder.resolve(null, savedEnabled = true, savedUrl = "ftp://subs.example/dv"))
    }
}
