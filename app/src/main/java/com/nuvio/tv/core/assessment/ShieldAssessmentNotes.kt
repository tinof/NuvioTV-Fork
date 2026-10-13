package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.FrameRateMatchingMode

/**
 * Shield-only advice in the assessment: Shield settings no API reads, and the Nuvio setting that costs a Shield
 * feature. Each rule takes the device check as a Boolean, so tests need no Build fields.
 */
internal object ShieldAssessmentNotes {

    /** The Shield's own Match frame rate also switches the display: warn wherever Nuvio's AFR is on or advised on. */
    fun afrNoteApplies(isNvidia: Boolean, currentMode: FrameRateMatchingMode, recommendsOn: Boolean): Boolean =
        isNvidia && (currentMode != FrameRateMatchingMode.OFF || recommendsOn)

    /**
     * A matched resolution reaches the TV without the Shield's AI upscaling, so on a 2019 Shield (the only ones
     * with it) the advice is off. Older Shields keep the usual "your call".
     */
    fun resMatchRecommendsOff(isShield2019: Boolean, displaySwitchesResolution: Boolean): Boolean =
        isShield2019 && displaySwitchesResolution

    /** The apply plan's value for the res_match row: turn matching off only where the row advises it and it is on. */
    fun resMatchPlan(recommendsOff: Boolean, resolutionMatchingEnabled: Boolean): Boolean? =
        false.takeIf { recommendsOff && resolutionMatchingEnabled }

    fun withNote(grounds: String, note: String?): String =
        if (note.isNullOrBlank()) grounds else "$grounds. $note"
}
