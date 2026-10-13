package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.FrameRateMatchingMode

/**
 * Shield-only advice in the assessment: Shield settings no API reads, and the Nuvio setting that costs a Shield
 * feature. Each rule takes isNvidia, so tests need no Build fields.
 */
internal object ShieldAssessmentNotes {

    /** The Shield's own Match frame rate also switches the display: warn wherever Nuvio's AFR is on or advised on. */
    fun afrNoteApplies(isNvidia: Boolean, currentMode: FrameRateMatchingMode, recommendsOn: Boolean): Boolean =
        isNvidia && (currentMode != FrameRateMatchingMode.OFF || recommendsOn)

    /** A matched resolution reaches the TV without the Shield's AI upscaling, so on a Shield the advice is off. */
    fun resMatchRecommendsOff(isNvidia: Boolean, displaySwitchesResolution: Boolean): Boolean =
        isNvidia && displaySwitchesResolution

    fun withNote(grounds: String, note: String?): String =
        if (note.isNullOrBlank()) grounds else "$grounds. $note"
}
