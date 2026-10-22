package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import com.nuvio.tv.core.player.AacToDtsRouting
import java.lang.reflect.Method

/**
 * The DTS-HD MA part of the FFmpeg renderer's API, reached by reflection.
 *
 * The app builds against the decoder AAR in app/libs, or against ffmpeg-decoder-downmix with
 * USE_LOCAL_FFMPEG_DECODER. The DTS-HD MA methods (setTranscodeAacToDtsHd,
 * setDtsTranscodeTargetOverride, getDtsTranscodeTarget) exist only in AARs built from the module
 * since the DTS-HD port, so a direct call would not compile against an older AAR and would crash
 * at run time with one (DTSHD_PORT.md, R13). Without the methods the renderer writes the DTS core
 * at most, and [writesHd] says so, so the app never asks for DTS-HD MA it cannot get.
 * proguard-rules.pro keeps androidx.media3.decoder.** whole, so the names survive R8.
 */
internal object FfmpegRendererDtsHd {

    private const val TAG = "FfmpegRendererDtsHd"

    private class Api(val setHd: Method, val setOverride: Method, val getTarget: Method)

    private val api: Api? by lazy {
        runCatching {
            val cls = FfmpegAudioRenderer::class.java
            Api(
                setHd = cls.getMethod("setTranscodeAacToDtsHd", Boolean::class.javaPrimitiveType),
                setOverride = cls.getMethod("setDtsTranscodeTargetOverride", Int::class.javaPrimitiveType),
                getTarget = cls.getMethod("getDtsTranscodeTarget")
            )
        }.getOrNull()
    }

    /** Whether the FFmpeg renderer in this build can write DTS-HD MA. */
    val writesHd: Boolean get() = api != null

    /** The module's DtsTranscodeTargets value for an override: -1 automatic, 1 core, 2 HD. */
    internal fun overrideValue(target: AacToDtsRouting.Target?): Int = when (target) {
        null, AacToDtsRouting.Target.NONE -> -1
        AacToDtsRouting.Target.CORE -> 1
        AacToDtsRouting.Target.HD -> 2
    }

    /** The app's target for a DtsTranscodeTargets value; anything unknown counts as none. */
    internal fun targetOf(value: Int): AacToDtsRouting.Target = when (value) {
        2 -> AacToDtsRouting.Target.HD
        1 -> AacToDtsRouting.Target.CORE
        else -> AacToDtsRouting.Target.NONE
    }

    fun apply(renderer: FfmpegAudioRenderer, hdAllowed: Boolean, override: AacToDtsRouting.Target?) {
        val api = api ?: return
        runCatching {
            api.setHd.invoke(renderer, hdAllowed)
            api.setOverride.invoke(renderer, overrideValue(override))
        }.onFailure { Log.w(TAG, "DTS-HD settings not applied: $it") }
    }

    /** The renderer's current target; with an older AAR, the core when it transcodes to DTS. */
    fun target(renderer: FfmpegAudioRenderer): AacToDtsRouting.Target {
        val api = api
        if (api == null) {
            return if (renderer.isTranscodingToDts()) AacToDtsRouting.Target.CORE else AacToDtsRouting.Target.NONE
        }
        return runCatching { targetOf(api.getTarget.invoke(renderer) as Int) }
            .getOrElse { AacToDtsRouting.Target.NONE }
    }
}
