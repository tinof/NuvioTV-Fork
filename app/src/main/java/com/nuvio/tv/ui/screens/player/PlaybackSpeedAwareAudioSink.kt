package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.Ac3Util
import com.nuvio.tv.core.player.AacToDtsRouting
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.ui.screens.player.iec.HbrPayload
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import java.nio.ByteBuffer

/**
 * Audio sink wrapper that forces a decode-to-PCM path when:
 * - Playback speed != 1x for bitstream formats that cannot be tempo-adjusted in passthrough, or
 * - Bluetooth media output is active (Media3 policy: Bluetooth only supports PCM).
 *
 * Bluetooth cannot carry TrueHD / Atmos / DTS-HD passthrough. Forcing PCM lets MediaCodec/FFmpeg
 * decode to the format the BT stack actually accepts; the system then encodes to SBC/AAC/aptX/LDAC.
 */
internal class PlaybackSpeedAwareAudioSink(
    sink: AudioSink,
    initialForcePcm: Boolean = false,
    forcePcmForBluetooth: Boolean = false,
    passthroughPolicy: AudioPassthroughPolicy = AudioPassthroughPolicy.ALLOW_ALL,
    private val onDiagnosticEvent: ((String) -> Unit)? = null,
    // Claims AC-3 up to 5.1 whatever the platform reports, for Force AC-3 and for
    // denied formats that are transcoded.
    private val forceAc3Support: Boolean = false,
    // "Transcode AAC to DTS": mono and stereo AAC go to the FFmpeg renderer while DTS can play.
    // This and the next two are fixed for the player's life; the FFmpeg renderer is given the
    // same values, so both pick the same target (see commitSurroundResolution).
    val transcodeAacToDts: Boolean = false,
    // DTS-HD MA may be written for this stream (D5 step-down, debug switch, the renderer's AAR).
    val transcodeAacToDtsHd: Boolean = false,
    // debug.nuvio.aac_dts_target (D6), null for automatic.
    val dtsTargetOverride: AacToDtsRouting.Target? = null,
    // Test harness: refuses the first passthrough buffer of this MIME as a failed open.
    private val faultInjectRejectMime: String? = null,
    reducedStartThresholdFrames: Int = 0,
    platformSink: DefaultAudioSink? = null,
    // Encodings the platform sink was pinned to when built; null when it follows the output live.
    val pinnedOutputEncodings: Set<Int>? = null
) : ForwardingAudioSink(sink) {

    // Set when the sink is built with forcePcm (error recovery). Don't clear on speed reset.
    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var bluetoothForcePcm: Boolean = forcePcmForBluetooth

    @Volatile
    private var passthroughPolicy: AudioPassthroughPolicy = passthroughPolicy

    @Volatile
    private var currentInputFormat: Format? = null

    val activeInputFormat: Format?
        get() = currentInputFormat

    @Volatile
    var currentTunnelAudioClass: String? = null
        private set

    @Volatile
    private var listener: AudioSink.Listener? = null

    /** Called, on the thread that changed it, after this sink tells Media3 its formats changed. */
    @Volatile
    var onCapabilitiesNotified: (() -> Unit)? = null

    private val passthroughPacer = PassthroughWaterLevelPacer(onDiagnosticEvent)
    private val iecSink: IecPassthroughAudioSink? = sink as? IecPassthroughAudioSink
    private val monitor = PlaybackAudioSinkMonitor(platformSink, iecSink, reducedStartThresholdFrames)

    @Volatile
    private var isCurrentlyPassthrough: Boolean = false

    private var faultInjectFiredForCurrentConfig: Boolean = false

    // A DTS-HD MA transcode configured for an output that does not list DTS-HD (see
    // refuseDtsHdTranscodeWithoutDtsHdOutput).
    private var dtsHdTranscodeWithoutDtsHdOutput: Boolean = false

    private var forwardAnchorPending: Boolean = false
    private var forwardUnsyncedChunks: Int = 0
    private var forwardFirstPtsUs: Long = C.TIME_UNSET
    private var forwardLastEvaluatedPtsUs: Long = C.TIME_UNSET
    private var forwardForceResync: Boolean = false
    private var forwardArmedBy: String = "configure"

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    /**
     * Update Bluetooth policy without rebuilding the player.
     * Call [notifyAudioProcessingRequirementChanged] after a change so Media3 reselects
     * decode-to-PCM vs passthrough on the live renderer.
     *
     * @return true when the effective PCM/passthrough policy changed.
     */
    fun setBluetoothForcePcm(enabled: Boolean): Boolean {
        val wasBluetoothForce = bluetoothForcePcm
        val wasSessionForce = forcePcmForCurrentSession
        bluetoothForcePcm = enabled
        if (enabled) {
            forcePcmForCurrentSession = true
        } else if (!startedWithForcedPcm && playbackSpeed == 1f) {
            // Session was not built as PCM-only; leaving Bluetooth can restore passthrough.
            forcePcmForCurrentSession = false
        }
        return wasBluetoothForce != bluetoothForcePcm || wasSessionForce != forcePcmForCurrentSession
    }

    fun isBluetoothForcePcm(): Boolean = bluetoothForcePcm

    fun setPassthroughPolicy(policy: AudioPassthroughPolicy): Boolean {
        if (policy == passthroughPolicy) return false
        passthroughPolicy = policy
        return true
    }

    fun isIecHbrActive(): Boolean = iecSink?.isIecActive == true

    fun iecHbrPayload(): HbrPayload? = iecSink?.iecPayload()

    fun iecInvolved(): Boolean = iecSink?.iecInvolved == true

    fun isDirectPlaybackActive(): Boolean = isCurrentlyPassthrough

    fun setPlaybackActive(active: Boolean) = monitor.setPlaybackActive(active)

    fun audioClockJitter(): AudioClockJitter? = monitor.audioClockJitter()

    fun nativeAudioTrackUnderrunCount(): Int? = monitor.nativeAudioTrackUnderrunCount()

    fun sampleAudioRoute(): AudioRouteSnapshot? = monitor.sampleAudioRoute()

    fun routedDevice(): android.media.AudioDeviceInfo? = monitor.routedDevice()

    fun platformOutputTimestamp(): AudioOutputTimestamp? = monitor.platformOutputTimestamp()

    fun demandsNonTunnelledVideo(format: Format): Boolean = iecSink?.claimsHbr(format) == true

    fun hbrDemandsNonTunnelledVideo(format: Format): Boolean =
        IecPassthroughAudioSink.isHbrPassthrough(format)

    fun tunnelAudioClass(format: Format): String {
        val mime = format.sampleMimeType ?: return TUNNEL_AUDIO_CLASS_PCM
        if (mime == MimeTypes.AUDIO_RAW) return TUNNEL_AUDIO_CLASS_PCM
        return if (getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) {
            mime
        } else {
            TUNNEL_AUDIO_CLASS_PCM
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        passthroughPacer.onFormat(inputFormat, nowMs())
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        currentTunnelAudioClass = tunnelAudioClass(inputFormat)
        isCurrentlyPassthrough =
            isEncodedPassthroughCandidate(inputFormat) && !shouldRejectDirectPlayback(inputFormat)
        faultInjectFiredForCurrentConfig = false
        dtsHdTranscodeWithoutDtsHdOutput = AacToDtsRouting.isDtsHdTranscodeOutput(inputFormat) &&
            !outputListsDtsHd() && dtsTargetOverride != AacToDtsRouting.Target.HD
        monitor.onTrackReset()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        passthroughPacer.setIecPacked(iecSink?.isIecActive == true)
        armForwardAnchor(armedBy = "configure")
    }

    override fun play() {
        passthroughPacer.onPlay(nowMs())
        super.play()
    }

    override fun pause() {
        passthroughPacer.onPause(nowMs())
        super.pause()
    }

    override fun flush() {
        passthroughPacer.onTimelineReset(nowMs())
        monitor.onTrackReset()
        super.flush()
        armForwardAnchor(armedBy = "flush")
    }

    override fun reset() {
        passthroughPacer.onReset()
        super.reset()
    }

    override fun playToEndOfStream() {
        val iecWasActive = iecSink?.isIecActive == true
        super.playToEndOfStream()
        noteIecFallbackIfFlipped(iecWasActive)
    }

    override fun handleDiscontinuity() {
        passthroughPacer.onTimelineReset(nowMs())
        super.handleDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        refuseDtsHdTranscodeWithoutDtsHdOutput()
        if (isCurrentlyPassthrough) {
            monitor.maybeApplyStartThreshold()
            maybeInjectOpenFailure()
        }
        if (forwardAnchorPending && presentationTimeUs != forwardLastEvaluatedPtsUs) {
            forwardLastEvaluatedPtsUs = presentationTimeUs
            evaluateForwardAnchor(buffer, presentationTimeUs)
        }
        val passthrough = passthroughPacer.appliesTo(currentInputFormat)
        if (passthrough &&
            !passthroughPacer.shouldAcceptBuffer(presentationTimeUs, nowMs(), playbackSpeed)
        ) {
            return false
        }
        val encodedBytes = if (currentInputFormat?.sampleMimeType != MimeTypes.AUDIO_RAW) {
            buffer.remaining()
        } else {
            0
        }
        val iecWasActive = iecSink?.isIecActive == true
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        noteIecFallbackIfFlipped(iecWasActive)
        if (handled && passthrough) {
            passthroughPacer.onBufferAccepted(presentationTimeUs)
        }
        if (handled && encodedBytes > 0) {
            PlayerAudioBitrateMeter.record(encodedBytes, presentationTimeUs)
        }
        return handled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val sinkPositionUs = super.getCurrentPositionUs(sourceEnded)
        monitor.onPositionRead(sinkPositionUs, playbackSpeed)
        if (!passthroughPacer.appliesTo(currentInputFormat)) {
            return sinkPositionUs
        }
        return passthroughPacer.clampPositionUs(sinkPositionUs, nowMs(), playbackSpeed)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        val wasAt1x = playbackSpeed == 1f
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        var shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
        // An AAC-to-DTS transcode that started at a saved speed writes PCM, so no session latch is
        // set; crossing 1x must still let the renderers pick DTS or PCM again.
        if (transcodeAacToDts && wasAt1x != (playbackSpeed == 1f)) {
            shouldNotify = true
        }
        // Going above 1x latches forcePcm for the session. Clear it when back at 1.0x
        // so passthrough can recover (unless recovery built us with forcePcm).
        if (playbackSpeed == 1f && forcePcmForCurrentSession && !startedWithForcedPcm) {
            forcePcmForCurrentSession = false
            shouldNotify = true
        }
        currentInputFormat?.let { currentTunnelAudioClass = tunnelAudioClass(it) }
        super.setPlaybackParameters(playbackParameters)
        if (shouldNotify) {
            listener?.onAudioCapabilitiesChanged()
            onCapabilitiesNotified?.invoke()
        }
    }

    fun notifyAudioProcessingRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
        onCapabilitiesNotified?.invoke()
    }

    override fun getFormatSupport(format: Format): Int {
        if (shouldRejectDirectPlayback(format)) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        val support = super.getFormatSupport(format)
        // Media3 says DTS-HD plays directly when the output takes only DTS, and then opens the
        // core; the own IEC engine says yes whenever its probe passed. For the transcode's own
        // DTS-HD query that would write DTS-HD MA to a receiver that may not decode it, so it
        // counts only when the output lists DTS-HD.
        if (support == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY &&
            AacToDtsRouting.isDtsHdTranscodeOutput(format) &&
            !outputListsDtsHd()
        ) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        if (forceAc3Support &&
            support == AudioSink.SINK_FORMAT_UNSUPPORTED &&
            format.sampleMimeType == MimeTypes.AUDIO_AC3 &&
            format.channelCount <= 6
        ) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return support
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (shouldRejectDirectPlayback(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    fun shouldForcePcmForFormat(format: Format): Boolean {
        return shouldRejectDirectPlayback(format)
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        if (!isEncodedPassthroughCandidate(format)) {
            return false
        }
        // Bluetooth: always decode to PCM (Media3 DEFAULT_AUDIO_CAPABILITIES policy).
        if (bluetoothForcePcm || forcePcmForCurrentSession) {
            return true
        }
        // Non-1x speed cannot be applied to bitstream passthrough tracks.
        if (playbackSpeed != 1f) {
            return true
        }
        return isPolicyDeniedPassthrough(format)
    }

    fun isPolicyDeniedPassthrough(format: Format): Boolean {
        return passthroughPolicy.deniesPassthrough(format.sampleMimeType)
    }

    /**
     * Whether the output decodes DTS-HD: its pinned capabilities list DTS-HD. That holds for both
     * routes. The own IEC engine can pack DTS-HD for any output with an IEC61937 path, and the
     * passthrough policy lets DTS-HD through on a DTS-only output so that Media3 can play the core
     * of a real DTS-HD track; neither says the receiver decodes DTS-HD MA bursts. With live
     * capabilities (phones, tablets) it is not known here, so the transcode stays on the core.
     */
    private fun outputListsDtsHd(): Boolean = pinnedOutputEncodings?.contains(C.ENCODING_DTS_HD) == true

    /**
     * The DTS target for [format]: DTS-HD MA, the DTS core or none (PCM). The checks go through
     * [getFormatSupport], so Bluetooth, speed != 1x, the session PCM latch and the passthrough
     * policy all turn it off, and DTS-HD counts only when the output lists it. The FFmpeg renderer
     * asks the same sink the same questions, so both renderers agree.
     */
    fun dtsTranscodeTarget(format: Format): AacToDtsRouting.Target = AacToDtsRouting.chooseTarget(
        enabled = transcodeAacToDts,
        eligible = AacToDtsRouting.isEligibleSource(format),
        hdAllowed = transcodeAacToDtsHd,
        forced = dtsTargetOverride,
        hdDirect = {
            getFormatSupport(AacToDtsRouting.DTS_HD_OUTPUT_FORMAT) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        },
        coreDirect = {
            getFormatSupport(AacToDtsRouting.DTS_OUTPUT_FORMAT) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
    )

    /** Whether [format] should be transcoded to DTS (core or DTS-HD MA) by the FFmpeg renderer. */
    fun prefersDtsTranscode(format: Format): Boolean =
        dtsTranscodeTarget(format) != AacToDtsRouting.Target.NONE

    /** The sink format the transcode of [format] would write, or null when it plays as PCM. */
    fun dtsTranscodeOutputFormat(format: Format): Format? =
        AacToDtsRouting.outputFormatOf(dtsTranscodeTarget(format))

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || !isEncodedPassthroughCandidate(format)) {
            return false
        }
        if (bluetoothForcePcm) {
            val wasForcingPcm = forcePcmForCurrentSession
            forcePcmForCurrentSession = true
            return !wasForcingPcm
        }
        if (speed == 1f) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Formats that devices may try to play via passthrough/offload and that Bluetooth cannot carry.
     * Matches Media3 surround encodings that need decode-to-PCM on A2DP/LE Audio.
     */
    private fun isEncodedPassthroughCandidate(format: Format): Boolean {
        if (PassthroughWaterLevelPacer.isPassthroughMime(format.sampleMimeType)) return true
        val codecs = format.codecs
        if (codecs != null) {
            return codecs.contains("ac-3", ignoreCase = true) ||
                codecs.contains("ac-4", ignoreCase = true) ||
                codecs.contains("ec-3", ignoreCase = true) ||
                codecs.contains("dts", ignoreCase = true) ||
                codecs.contains("truehd", ignoreCase = true) ||
                codecs.contains("dtshd", ignoreCase = true)
        }
        return false
    }

    private fun maybeInjectOpenFailure() {
        val rejectMime = faultInjectRejectMime ?: return
        val format = currentInputFormat ?: return
        if (faultInjectFiredForCurrentConfig || format.sampleMimeType != rejectMime) return
        faultInjectFiredForCurrentConfig = true
        Log.w(TAG, "FAULT_INJECT: refusing passthrough AudioTrack for $rejectMime (simulated HAL rejection)")
        throw AudioSink.InitializationException(
            "AudioTrack init failed ${AudioTrack.STATE_UNINITIALIZED} fault-injected $rejectMime",
            AudioTrack.STATE_UNINITIALIZED,
            format,
            true,
            null
        )
    }

    /**
     * The target choice never picks DTS-HD MA for an output that does not list DTS-HD, unless the
     * debug switch forces it. Should a DTS-HD MA transcode still arrive here, the own IEC engine
     * would pack bursts the receiver may not decode, or Media3 would open a DTS core AudioTrack
     * for it; both play nothing useful and raise no error. Refusing it as a failed AudioTrack
     * open sends the stream down the D5 chain (tryAacToDtsStepDown: the DTS core next) instead.
     * Every buffer is refused until the next configure, so a retry cannot slip past it.
     */
    private fun refuseDtsHdTranscodeWithoutDtsHdOutput() {
        if (!dtsHdTranscodeWithoutDtsHdOutput) return
        val format = currentInputFormat ?: return
        Log.w(TAG, "DTS-HD MA transcode refused: the output does not list DTS-HD")
        onDiagnosticEvent?.invoke("aac_dts_hd_refused reason=output_lacks_dtshd")
        throw AudioSink.InitializationException(
            "AudioTrack init failed ${AudioTrack.STATE_UNINITIALIZED} DTS-HD MA transcode, output lacks DTS-HD",
            AudioTrack.STATE_UNINITIALIZED,
            format,
            false,
            null
        )
    }

    private fun noteIecFallbackIfFlipped(iecWasActive: Boolean) {
        if (iecWasActive && iecSink?.isIecActive == false) {
            passthroughPacer.setIecPacked(false)
            armForwardAnchor(armedBy = "fallback", forceResync = true)
        }
    }

    private fun armForwardAnchor(armedBy: String, forceResync: Boolean = false) {
        forwardAnchorPending =
            currentInputFormat?.sampleMimeType == MimeTypes.AUDIO_TRUEHD &&
                iecSink?.isIecActive != true
        forwardUnsyncedChunks = 0
        forwardFirstPtsUs = C.TIME_UNSET
        forwardLastEvaluatedPtsUs = C.TIME_UNSET
        forwardForceResync = forwardAnchorPending && forceResync
        forwardArmedBy = armedBy
    }

    private fun evaluateForwardAnchor(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (presentationTimeUs == C.TIME_UNSET) return
        if (forwardFirstPtsUs == C.TIME_UNSET) forwardFirstPtsUs = presentationTimeUs
        if (Ac3Util.findTrueHdSyncframeOffset(buffer) == C.INDEX_UNSET) {
            forwardUnsyncedChunks++
            return
        }
        val deltaUs = presentationTimeUs - forwardFirstPtsUs
        val resynced = forwardUnsyncedChunks > 0 || forwardForceResync
        if (resynced) {
            handleDiscontinuity()
        }
        val line = "forward_anchor mime=true-hd droppedChunks=$forwardUnsyncedChunks " +
            "deltaUs=$deltaUs resynced=$resynced armedBy=$forwardArmedBy"
        onDiagnosticEvent?.invoke(line)
        Log.i(TAG, line)
        forwardAnchorPending = false
        forwardForceResync = false
    }

    companion object {
        const val TUNNEL_AUDIO_CLASS_PCM = "pcm"
        private const val TAG = "PassthroughSink"
    }
}
