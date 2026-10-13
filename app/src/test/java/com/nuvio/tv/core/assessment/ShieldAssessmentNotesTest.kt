package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.FrameRateMatchingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `resolution matching is advised off only on a 2019 Shield whose display can switch`() {
        assertTrue(ShieldAssessmentNotes.resMatchRecommendsOff(isShield2019 = true, displaySwitchesResolution = true))
        assertFalse(ShieldAssessmentNotes.resMatchRecommendsOff(isShield2019 = true, displaySwitchesResolution = false))
        assertFalse(ShieldAssessmentNotes.resMatchRecommendsOff(isShield2019 = false, displaySwitchesResolution = true))
    }

    @Test
    fun `apply turns resolution matching off only where the row advises it and it is on`() {
        assertEquals(false, ShieldAssessmentNotes.resMatchPlan(recommendsOff = true, resolutionMatchingEnabled = true))
        assertNull(ShieldAssessmentNotes.resMatchPlan(recommendsOff = true, resolutionMatchingEnabled = false))
        assertNull(ShieldAssessmentNotes.resMatchPlan(recommendsOff = false, resolutionMatchingEnabled = true))
        assertNull(ShieldAssessmentNotes.resMatchPlan(recommendsOff = false, resolutionMatchingEnabled = false))
    }

    @Test
    fun `note follows the grounds as a second sentence`() {
        assertEquals("Grounds. Note", ShieldAssessmentNotes.withNote("Grounds", "Note"))
        assertEquals("Grounds", ShieldAssessmentNotes.withNote("Grounds", null))
        assertEquals("Grounds", ShieldAssessmentNotes.withNote("Grounds", ""))
    }
}
