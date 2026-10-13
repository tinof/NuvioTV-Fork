package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.ui.screens.player.iec.LiveDirectAudioPlayback
import com.nuvio.tv.ui.screens.player.iec.PlatformIecAudioTrackFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounce window so flapping add/remove events coalesce into one route decision. */
private const val AUDIO_ROUTE_CHANGE_DEBOUNCE_MS = 700L

internal enum class BluetoothRoutePlaybackAction {
    NONE,
    UPDATE_SINK_IN_PLACE,
    UPDATE_MPV_IN_PLACE,
}

/**
 * Bluetooth connect/disconnect must never rebuild the player: that restarts video from a
 * seek point and shows the loading overlay. Update PCM/passthrough (Exo) or stereo/delay
 * (MPV) on the live engine instead.
 */
internal fun decideBluetoothRoutePlaybackAction(
    wasBluetooth: Boolean,
    isBluetooth: Boolean,
    usingMpv: Boolean,
    oldRouteKey: String? = null,
    newRouteKey: String? = null
): BluetoothRoutePlaybackAction {
    val bluetoothStateChanged = wasBluetooth != isBluetooth
    val bluetoothDeviceChanged = isBluetooth &&
        !oldRouteKey.isNullOrBlank() &&
        !newRouteKey.isNullOrBlank() &&
        oldRouteKey != newRouteKey
    if (!bluetoothStateChanged && !bluetoothDeviceChanged) {
        return BluetoothRoutePlaybackAction.NONE
    }
    return if (usingMpv) {
        BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE
    } else {
        BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE
    }
}

/**
 * Android answers every callback registration with an "added" event listing the outputs already
 * connected. Each new player registers again, so that echo is not a route change and must not
 * reset what was learned about the output during the same playback.
 */
internal fun addsOnlyKnownOutputs(knownOutputIds: Set<Int>, addedOutputIds: Collection<Int>): Boolean =
    addedOutputIds.isNotEmpty() && addedOutputIds.all { it in knownOutputIds }

internal object MpvBluetoothAudioPolicy {
    const val STEREO_CHANNELS = "stereo"
    const val AUTO_CHANNELS = "auto"

    fun audioChannels(isBluetooth: Boolean): String {
        return if (isBluetooth) STEREO_CHANNELS else AUTO_CHANNELS
    }

    fun shouldClearAudioSpdif(isBluetooth: Boolean): Boolean = isBluetooth
}

internal fun audioDelayMsToSeconds(delayMs: Int): Double {
    return delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS) / 1000.0
}

/**
 * Below API 33 detect() ranks a listed Bluetooth sink first even while the track plays on HDMI.
 * Once this stream's track was seen on HDMI under that idle sink, the HDMI route stands for as
 * long as detect() still names the same Bluetooth sink.
 */
internal data class HdmiRouteUnderIdleBluetooth(
    val streamUrl: String,
    val hdmiRoute: AudioOutputRoute,
    val idleBluetoothRouteKey: String
)

/** In-place unpins per stream; past this the stream stays PCM, the safe side. */
internal const val MAX_HDMI_IN_PLACE_UNPINS_PER_STREAM = 2

/** [detected], or the stream's pinned HDMI route while [detected] is still the idle Bluetooth sink. */
internal fun resolveRouteUnderIdleBluetooth(
    pin: HdmiRouteUnderIdleBluetooth?,
    streamUrl: String?,
    detected: AudioOutputRoute?
): AudioOutputRoute? {
    if (pin == null || pin.streamUrl != streamUrl) return detected
    if (detected == null || !detected.isBluetooth || detected.key != pin.idleBluetoothRouteKey) return detected
    return pin.hdmiRoute
}

/**
 * Whether a device event leaves [pin] standing. Any Bluetooth sink being added is a real route
 * change, the idle one included: Android routes media to a newly connected A2DP sink, and a
 * bitstream sent there fails. A Bluetooth sink other than the idle one going away is one too:
 * detect() breaks ties between Bluetooth sinks by list order, so it may still name the idle sink
 * while media moves elsewhere.
 */
internal fun idleBluetoothPinSurvives(
    pin: HdmiRouteUnderIdleBluetooth,
    changedBluetoothRouteKeys: Collection<String>,
    added: Boolean = false,
    nonBluetoothSinkRemoved: Boolean = false
): Boolean = if (nonBluetoothSinkRemoved) {
    // The HDMI sink the pin stands for may be the one that went away (TV off, input switch).
    false
} else if (added) {
    changedBluetoothRouteKeys.isEmpty()
} else {
    changedBluetoothRouteKeys.all { it == pin.idleBluetoothRouteKey }
}

/** The media output route for the current stream: detect(), corrected by the idle-Bluetooth HDMI pin. */
internal fun PlayerRuntimeController.detectAudioOutputRouteForStream(): AudioOutputRoute? =
    resolveRouteUnderIdleBluetooth(
        pin = hdmiRouteUnderIdleBluetooth,
        streamUrl = currentStreamUrl,
        detected = AudioOutputRouteDetector.detect(context)
    )

/** [knownRoute] is the route to load the delay for; null detects it for the current stream. */
internal suspend fun PlayerRuntimeController.applyStoredAudioDelayForCurrentRouteIfEnabled(
    knownRoute: AudioOutputRoute? = null
) {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = knownRoute ?: detectAudioOutputRouteForStream() ?: return
    currentAudioOutputRoute = route

    val storedDelayMs = audioDelayRouteDataStore.loadDelayMs(route.key) ?: 0
    applyAudioDelay(storedDelayMs, persistForCurrentRoute = false)
    Log.d(
        PlayerRuntimeController.TAG,
        "Applied audio delay ${storedDelayMs}ms for route=${route.key}"
    )
}

internal fun PlayerRuntimeController.persistAudioDelayForCurrentRoute(delayMs: Int) {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = detectAudioOutputRouteForStream() ?: currentAudioOutputRoute ?: return
    currentAudioOutputRoute = route
    scope.launch {
        audioDelayRouteDataStore.saveDelayMs(route.key, delayMs)
    }
}

internal fun PlayerRuntimeController.registerAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioOutputRouteCallback != null) return

    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val addedOutputs = addedDevices.filter { it.isSink }.map { it.id }
            val repeat = addsOnlyKnownOutputs(knownAudioOutputDeviceIds, addedOutputs)
            knownAudioOutputDeviceIds = knownAudioOutputDeviceIds + addedOutputs
            if (repeat) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Audio device added (count=${addedDevices.size}, outputs already connected); output findings kept"
                )
                if (!isReleasingPlayer && _exoPlayer != null && currentAudioOutputRoute?.isBluetooth != true) {
                    schedulePassthroughReturnCheck("player start")
                }
                return
            }
            onAudioOutputRouteMaybeChanged("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            knownAudioOutputDeviceIds = knownAudioOutputDeviceIds - removedDevices.map { it.id }.toSet()
            onAudioOutputRouteMaybeChanged("removed", removedDevices)
        }
    }

    runCatching {
        knownAudioOutputDeviceIds = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()
        audioManager.registerAudioDeviceCallback(callback, null)
        audioOutputRouteCallback = callback
        Log.d(PlayerRuntimeController.TAG, "Registered audio output route callback")
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to register audio route callback", it)
    }
}

/**
 * Each player gets its own IEC61937 probe and the tunnel watch's quiet time after an output change. A bitstream
 * track of the previous player whose release was not reported counts as closed from here.
 */
internal fun PlayerRuntimeController.rearmOutputChecksForNewPlayer() {
    LiveDirectAudioPlayback.noteOwnTrackClosed()
    PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo()
    noteOutputChangeForTunnelWatch()
}

internal fun PlayerRuntimeController.unregisterAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val callback = audioOutputRouteCallback ?: return
    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        audioManager.unregisterAudioDeviceCallback(callback)
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to unregister audio route callback", it)
    }
    audioOutputRouteCallback = null
}

private fun PlayerRuntimeController.onAudioOutputRouteMaybeChanged(
    reason: String,
    devices: Array<out AudioDeviceInfo>
) {
    if (isReleasingPlayer) return
    if (_exoPlayer == null && !isUsingMpvEngine()) return
    if (!SurroundRecheckGuard.eventHasOutput(devices.map { it.isSink })) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Audio device $reason ignored (count=${devices.size}, no output device)"
        )
        return
    }
    noteOutputChangeForTunnelWatch()

    Log.d(
        PlayerRuntimeController.TAG,
        "Audio device $reason (count=${devices.size}); scheduling route re-probe"
    )

    val changedBluetoothRouteKeys = devices
        .filter { it.isSink && AudioOutputRouteDetector.isBluetoothType(it.type) }
        .map { AudioOutputRouteDetector.routeOf(it).key }
    val nonBluetoothSinkRemoved = reason == "removed" &&
        devices.any { it.isSink && !AudioOutputRouteDetector.isBluetoothType(it.type) }
    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = scope.launch {
        delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)
        if (isReleasingPlayer) return@launch

        hdmiRouteUnderIdleBluetooth?.let { pin ->
            if (!idleBluetoothPinSurvives(
                    pin,
                    changedBluetoothRouteKeys,
                    added = reason == "added",
                    nonBluetoothSinkRemoved = nonBluetoothSinkRemoved
                )
            ) {
                hdmiRouteUnderIdleBluetooth = null
            }
        }
        val oldRoute = currentAudioOutputRoute
        // The same idle Bluetooth sink still listed keeps this stream's HDMI route: no re-pin.
        val newRoute = detectAudioOutputRouteForStream()
        if (newRoute != null) {
            currentAudioOutputRoute = newRoute
        }
        AudioRejectionReverifier.ledger.invalidate()
        saveAudioRefusalEvidence()
        AudioChainProbe.invalidate()
        PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo()
        applySurroundResolutionInPlace(reason)

        if (rememberAudioDelayPerDeviceEnabled) {
            applyStoredAudioDelayForCurrentRouteIfEnabled()
        }

        val wasBluetooth = oldRoute?.isBluetooth == true
        val isBluetooth = (newRoute ?: currentAudioOutputRoute)?.isBluetooth == true
        when (
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = wasBluetooth,
                isBluetooth = isBluetooth,
                usingMpv = isUsingMpvEngine(),
                oldRouteKey = oldRoute?.key,
                newRouteKey = (newRoute ?: currentAudioOutputRoute)?.key
            )
        ) {
            BluetoothRoutePlaybackAction.NONE -> {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Audio route after device $reason: bluetooth=$isBluetooth (was=$wasBluetooth) " +
                        "hdmiUnderIdleBluetooth=${hdmiRouteUnderIdleBluetooth != null}; player stays running"
                )
                if (reason == "added" && !isBluetooth) schedulePassthroughReturnCheck("device added")
            }
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE -> {
                if (_exoPlayer == null) return@launch
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating PCM policy in place (player stays running)"
                )
                applyBluetoothAudioRouteInPlace(isBluetooth)
                if (isBluetooth) {
                    hdmiRouteUnderBluetoothPin = null
                    hdmiRouteUnderIdleBluetooth = null
                    scheduleHdmiRouteUnderBluetoothPinCheck("device $reason")
                }
            }
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE -> {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating MPV stereo/delay in place (player stays running)"
                )
                applyMpvBluetoothAudioRouteInPlace(isBluetooth)
            }
        }
    }
}

/**
 * Rebuilds the player when the HDMI output is back and takes the current track as bitstream,
 * but the player was built while it did not (TV on another input, box asleep). Runs again a
 * little later because a receiver can report its formats a moment after the device appears.
 */
internal fun PlayerRuntimeController.schedulePassthroughReturnCheck(reason: String) {
    if (_exoPlayer == null || isUsingMpvEngine()) return
    passthroughReturnCheckJob?.cancel()
    passthroughReturnCheckJob = scope.launch {
        if (rebuildForReturnedPassthroughIfNeeded(reason)) return@launch
        delay(PassthroughOutputReturn.SECOND_CHECK_DELAY_MS)
        rebuildForReturnedPassthroughIfNeeded("$reason, second check")
    }
}

private fun PlayerRuntimeController.rebuildForReturnedPassthroughIfNeeded(reason: String): Boolean {
    if (isReleasingPlayer || isInBackground || playbackRecoveryJob?.isActive == true) return false
    if (isUsingMpvEngine()) return false
    val player = _exoPlayer ?: return false
    val sink = playbackSpeedAwareAudioSink ?: return false
    val format = player.audioFormat ?: return false
    val encoding = PassthroughOutputReturn.encodingOf(format) ?: return false
    if (AudioOutputRouteDetector.isBluetoothMediaOutput(context)) return false
    val live = PassthroughOutputReturn.liveEncodings(context)
    val rebuildsSoFar = passthroughReturnRebuilds[currentStreamUrl] ?: 0
    val rebuild = PassthroughOutputReturn.shouldRebuild(
        encoding = encoding,
        pinned = sink.pinnedOutputEncodings,
        live = live,
        alreadyBitstream = sink.isDirectPlaybackActive() || sink.isIecHbrActive(),
        claimedByIec = sink.demandsNonTunnelledVideo(format),
        policyDenies = currentAudioPassthroughPolicy?.deniesPassthrough(format.sampleMimeType) == true,
        forcedPcm = hasTriedAudioPcmFallback ||
            _uiState.value.playbackSpeed != 1f ||
            currentStreamUrl in preferFfmpegAudioStreamUrls,
        rebuildsSoFar = rebuildsSoFar
    )
    if (!rebuild) return false
    passthroughReturnRebuilds[currentStreamUrl] = rebuildsSoFar + 1
    Log.i(
        PlayerRuntimeController.TAG,
        "PASSTHROUGH_RETURN rebuild reason=$reason encoding=$encoding " +
            "pinned=${sink.pinnedOutputEncodings} live=$live route=${currentAudioOutputRoute?.key}"
    )
    scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
    return true
}

/**
 * Below API 33 a listed but idle Bluetooth sink pins the player to PCM while the track plays on
 * HDMI. Once the track has started, read its routed device; when that is HDMI and the output
 * takes the current track as bitstream, rebuild with the Bluetooth pin dropped for this stream.
 */
internal fun PlayerRuntimeController.scheduleHdmiRouteUnderBluetoothPinCheck(reason: String) {
    if (Build.VERSION.SDK_INT >= 33) return
    if (_exoPlayer == null || isUsingMpvEngine()) return
    if (playbackSpeedAwareAudioSink?.isBluetoothForcePcm() != true) return
    // Only TVs pin capabilities at build time; elsewhere media3's live receiver decides.
    if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return
    hdmiRouteCheckJob?.cancel()
    hdmiRouteCheckJob = scope.launch {
        delay(PassthroughOutputReturn.SECOND_CHECK_DELAY_MS)
        rebuildForHdmiRouteUnderBluetoothPin(reason)
    }
}

private suspend fun PlayerRuntimeController.rebuildForHdmiRouteUnderBluetoothPin(reason: String): Boolean {
    if (isReleasingPlayer || isInBackground || playbackRecoveryJob?.isActive == true) return false
    if (isUsingMpvEngine()) return false
    val player = _exoPlayer ?: return false
    val sink = playbackSpeedAwareAudioSink ?: return false
    val device = sink.routedDevice() ?: return false
    if (!PassthroughOutputReturn.hdmiRoutedUnderBluetoothPin(
            sdkInt = Build.VERSION.SDK_INT,
            bluetoothPinned = sink.isBluetoothForcePcm(),
            routedDeviceType = device.type
        )
    ) {
        return false
    }
    if (sink.pinnedOutputEncodings != null) {
        // Built with HDMI capabilities and pinned to PCM in place by a route event: unpin in place.
        val unpinsSoFar = hdmiInPlaceUnpins[currentStreamUrl] ?: 0
        if (unpinsSoFar >= MAX_HDMI_IN_PLACE_UNPINS_PER_STREAM) {
            Log.i(
                PlayerRuntimeController.TAG,
                "PASSTHROUGH_RETURN hdmi-under-bluetooth-pin reason=$reason in place skipped: " +
                    "$unpinsSoFar unpins this stream; stays PCM"
            )
            return false
        }
        hdmiInPlaceUnpins[currentStreamUrl] = unpinsSoFar + 1
        Log.i(
            PlayerRuntimeController.TAG,
            "PASSTHROUGH_RETURN hdmi-under-bluetooth-pin reason=$reason in place routed=${device.type}"
        )
        val hdmiRoute = AudioOutputRouteDetector.routeOf(device)
        hdmiRouteUnderIdleBluetooth = AudioOutputRouteDetector.detect(context)
            ?.takeIf { it.isBluetooth }
            ?.let { idle -> HdmiRouteUnderIdleBluetooth(currentStreamUrl, hdmiRoute, idle.key) }
        currentAudioOutputRoute = hdmiRoute
        applyBluetoothAudioRouteInPlace(false)
        applyStoredAudioDelayForCurrentRouteIfEnabled(hdmiRoute)
        applySurroundResolutionInPlace("hdmi under bluetooth pin")
        return true
    }
    val format = player.audioFormat ?: return false
    val encoding = PassthroughOutputReturn.encodingOf(format) ?: return false
    val live = PassthroughOutputReturn.liveEncodings(context, device)
    val rebuildsSoFar = passthroughReturnRebuilds[currentStreamUrl] ?: 0
    // The Bluetooth sink was built PCM-only, so nothing was pinned as bitstream.
    val rebuild = PassthroughOutputReturn.shouldRebuild(
        encoding = encoding,
        pinned = emptySet(),
        live = live,
        alreadyBitstream = sink.isDirectPlaybackActive() || sink.isIecHbrActive(),
        claimedByIec = false,
        policyDenies = currentAudioPassthroughPolicy?.deniesPassthrough(format.sampleMimeType) == true,
        forcedPcm = hasTriedAudioPcmFallback ||
            _uiState.value.playbackSpeed != 1f ||
            currentStreamUrl in preferFfmpegAudioStreamUrls,
        rebuildsSoFar = rebuildsSoFar
    )
    Log.i(
        PlayerRuntimeController.TAG,
        "PASSTHROUGH_RETURN hdmi-under-bluetooth-pin reason=$reason rebuild=$rebuild encoding=$encoding " +
            "routed=${device.type} live=$live route=${currentAudioOutputRoute?.key}"
    )
    if (!rebuild) return false
    hdmiRouteUnderBluetoothPin = currentStreamUrl to device
    passthroughReturnRebuilds[currentStreamUrl] = rebuildsSoFar + 1
    scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
    return true
}

internal fun PlayerRuntimeController.applyBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    val sink = playbackSpeedAwareAudioSink
    if (sink != null && sink.isBluetoothForcePcm() == isBluetooth) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Bluetooth PCM policy already $isBluetooth; " +
                if (wasPlaying) "player keeps running" else "player stays paused"
        )
        if (!wasPlaying || userPausedManually) {
            _exoPlayer?.let { player ->
                player.playWhenReady = false
                player.pause()
            }
        }
        return
    }

    sink?.setBluetoothForcePcm(isBluetooth)

    val settings = currentPlayerSettingsForReport
    val forceOptical = !isBluetooth &&
        settings.forceOpticalPassthrough &&
        settings.decoderPriority != 0
    val downmixEnabled = settings.effectiveDownmixEnabled || isBluetooth
    val outputChannels = if (isBluetooth) {
        AudioOutputChannels.CHANNELS_2_0
    } else {
        settings.audioOutputChannels
    }
    ffmpegAudioRenderer?.setForceOpticalPassthrough(forceOptical)
    if (downmixEnabled) {
        ffmpegAudioRenderer?.setAudioOutputChannels(
            outputChannels.ffmpegLayoutName,
            outputChannels.channelCount
        )
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(!settings.maintainOriginalAudioOnDownmix)
    } else {
        ffmpegAudioRenderer?.setAudioOutputChannels(null, 0)
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(false)
    }

    sink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        if (!wasPlaying || userPausedManually) {
            player.playWhenReady = false
            player.pause()
        }
    }
}

internal fun PlayerRuntimeController.applyMpvBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val view = mpvView ?: return
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    view.applyBluetoothAudioRoute(isBluetooth, reloadOutput = true)
    // ao-reload can drop live properties; re-pin the current per-route delay.
    view.setAudioDelayMs(_uiState.value.audioDelayMs)
    view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
    if (!wasPlaying || userPausedManually) {
        view.setPaused(true)
    }
}
