package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ROADMAP DV4.8: the manifest rows of the player settings and the folder dialog's Save. */
class DvManifestSettingsTest {

    @Test
    fun `the rows are enabled only with the conversion on, the internal player and no strip mode`() {
        assertTrue(dvManifestRowsEnabled(parentEnabled = true, stripMode = false, convertOn = true))
        assertFalse(dvManifestRowsEnabled(parentEnabled = true, stripMode = false, convertOn = false))
        assertFalse(dvManifestRowsEnabled(parentEnabled = true, stripMode = true, convertOn = true))
        // External player: the parent row state is off although ExoPlayer stays the internal engine.
        assertFalse(dvManifestRowsEnabled(parentEnabled = false, stripMode = false, convertOn = true))
    }

    @Test
    fun `Save stores a normalised folder`() {
        assertEquals(DvManifestUrlInput.Save("https://subs.example/dv/"), dvManifestUrlInput(" https://subs.example/dv/ "))
    }

    @Test
    fun `Save of an empty field clears the folder`() {
        assertEquals(DvManifestUrlInput.Clear, dvManifestUrlInput(""))
        assertEquals(DvManifestUrlInput.Clear, dvManifestUrlInput("   "))
    }

    @Test
    fun `Save of an invalid folder keeps the dialog open and writes nothing`() {
        listOf("subs.example/dv", "file:///sdcard/dv", "https://subs.example/dv?key=1").forEach {
            assertEquals(it, DvManifestUrlInput.Invalid, dvManifestUrlInput(it))
        }
    }
}
