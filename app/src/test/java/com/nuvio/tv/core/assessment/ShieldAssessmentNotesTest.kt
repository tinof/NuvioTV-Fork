package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.FrameRateMatchingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldAssessmentNotesTest {

    @Test
    fun `AFR note only on Nvidia while Nuvio's AFR is on or advised on`() {
        assertTrue(ShieldAssessmentNotes.afrNoteApplies(true, FrameRateMatchingMode.START_STOP, recommendsOn = false))
        assertTrue(ShieldAssessmentNotes.afrNoteApplies(true, FrameRateMatchingMode.START, recommendsOn = false))
        assertTrue(ShieldAssessmentNotes.afrNoteApplies(true, FrameRateMatchingMode.OFF, recommendsOn = true))
        assertFalse(ShieldAssessmentNotes.afrNoteApplies(true, FrameRateMatchingMode.OFF, recommendsOn = false))
        assertFalse(ShieldAssessmentNotes.afrNoteApplies(false, FrameRateMatchingMode.START_STOP, recommendsOn = true))
    }

    @Test
    fun `resolution matching is advised off only on a Shield whose display can switch`() {
        assertTrue(ShieldAssessmentNotes.resMatchRecommendsOff(isNvidia = true, displaySwitchesResolution = true))
        assertFalse(ShieldAssessmentNotes.resMatchRecommendsOff(isNvidia = true, displaySwitchesResolution = false))
        assertFalse(ShieldAssessmentNotes.resMatchRecommendsOff(isNvidia = false, displaySwitchesResolution = true))
    }

    @Test
    fun `note follows the grounds as a second sentence`() {
        assertEquals("Grounds. Note", ShieldAssessmentNotes.withNote("Grounds", "Note"))
        assertEquals("Grounds", ShieldAssessmentNotes.withNote("Grounds", null))
        assertEquals("Grounds", ShieldAssessmentNotes.withNote("Grounds", ""))
    }
}
