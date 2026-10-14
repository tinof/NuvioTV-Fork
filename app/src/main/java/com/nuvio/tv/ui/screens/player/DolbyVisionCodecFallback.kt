package com.nuvio.tv.core.player

import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.nuvio.tv.core.performance.DeviceProfile

/**
 * Finds Dolby Vision hardware decoders that exist on the device but don't
 * advertise Profile 8 in their profileLevels array.
 *
 * Problem: devices like the Xiaomi Mi Box S have a working DV decoder
 * (c2.amlogic.dolby-vision.dvhe.decoder) that handles video/dolby-vision
 * but only advertises Profile 7 support. When libdovi rewrites DV7 RPUs
 * to DV8.1, ExoPlayer's standard codec selector rejects this decoder
 * because Profile 8 isn't in its advertised profile list. Result: fallback
 * to the generic HEVC decoder, giving HDR10 instead of DV.
 *
 * Solution: when CONVERT_TO_DV81 is active and the standard selector
 * returns no decoders for video/dolby-vision, query MediaCodecList
 * directly (ignoring profile) and return any DV-capable decoder with
 * its real capabilities. The hardware accepts the DV8.1 stream even
 * though it doesn't advertise Profile 8.
 *
 * Safety: we use the decoder's actual [android.media.MediaCodecInfo.CodecCapabilities]
 * from the platform (never null), and optionally verify the decoder can be
 * instantiated via [MediaCodec.createByCodecName] before returning it.
 *
 * Integration point: call [findDvDecodersIgnoringProfile] from your custom
 * [androidx.media3.exoplayer.mediacodec.MediaCodecSelector] when the
 * standard selector returns empty for video/dolby-vision AND the DV7
 * handling mode is CONVERT_TO_DV81.
 */
@UnstableApi
object DolbyVisionCodecFallback {

    private const val TAG = "DvCodecFallback"

    /**
     * [prefersDvDecoders] for this playback, set per playback. The renderer sorts the
     * selector's list by format support (a DV decoder that does not list the stream's profile
     * goes behind HEVC), so the order is applied to the renderer's sorted list, not here.
     */
    @Volatile
    var preferDvDecoders: Boolean = false

    /**
     * Hardware DV decoders go ahead of HEVC only on a DV display: by default on Nvidia,
     * debug.nuvio.dv_decoder_first=1/0 forces it either way there.
     */
    fun prefersDvDecoders(
        switch: Boolean?,
        displayDv: Boolean,
        manufacturer: String? = Build.MANUFACTURER
    ): Boolean = displayDv && (switch ?: DeviceProfile.isNvidia(manufacturer))

    /** Hardware decoders queried under video/dolby-vision, moved ahead of the rest, order kept. */
    fun dvDecodersFirst(format: Format, decoders: List<MediaCodecInfo>): List<MediaCodecInfo> =
        dvDecodersFirst(preferDvDecoders, format.sampleMimeType, decoders) {
            it.mimeType == DV_MIME && it.hardwareAccelerated && !it.softwareOnly
        }

    internal fun <T> dvDecodersFirst(
        enabled: Boolean,
        sampleMimeType: String?,
        decoders: List<T>,
        isHardwareDvDecoder: (T) -> Boolean
    ): List<T> {
        if (!enabled || sampleMimeType != DV_MIME || decoders.size < 2) return decoders
        val (dv, rest) = decoders.partition(isHardwareDvDecoder)
        return if (dv.isEmpty()) decoders else dv + rest
    }

    /**
     * Returns Media3 [MediaCodecInfo] entries for every hardware decoder that
     * handles `video/dolby-vision`, regardless of which DV profiles it advertises.
     *
     * Each returned entry uses the decoder's **real** [android.media.MediaCodecInfo.CodecCapabilities]
     * from the platform — not null, not fabricated. This is safe: the decoder
     * genuinely exists and supports the MIME type; we're only bypassing
     * ExoPlayer's profile-level check because the hardware can handle DV8.1
     * even though it doesn't say so in its advertisement.
     *
     * Like media3's own selector, a decoder qualifies only when it supports the secure and
     * tunneled playback this query asks for, and does not require one it does not ask for.
     *
     * Returns an empty list if no DV decoder qualifies or on any error.
     */
    fun findDvDecodersIgnoringProfile(
        requiresSecure: Boolean,
        requiresTunneling: Boolean
    ): List<MediaCodecInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()

        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val results = mutableListOf<MediaCodecInfo>()

            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue

                val supportsDv = info.supportedTypes.any { type ->
                    type.equals(DV_MIME, ignoreCase = true)
                }
                if (!supportsDv) continue

                val caps = runCatching {
                    info.getCapabilitiesForType(DV_MIME)
                }.getOrNull()
                if (caps == null) {
                    Log.w(TAG, "Skipping ${info.name}: getCapabilitiesForType returned null")
                    continue
                }

                val featuresMatch = matchesPlaybackFeatures(
                    requiresSecure = requiresSecure,
                    secureSupported = caps.isFeatureSupported(FEATURE_SECURE),
                    secureRequired = caps.isFeatureRequired(FEATURE_SECURE),
                    requiresTunneling = requiresTunneling,
                    tunnelingSupported = caps.isFeatureSupported(FEATURE_TUNNELED),
                    tunnelingRequired = caps.isFeatureRequired(FEATURE_TUNNELED)
                )
                if (!featuresMatch) {
                    Log.i(
                        TAG,
                        "Skipping ${info.name}: secure=$requiresSecure tunneling=$requiresTunneling not matched"
                    )
                    continue
                }

                if (!probeCodecInstantiation(info.name)) {
                    Log.w(TAG, "Skipping ${info.name}: probe instantiation failed")
                    continue
                }

                val isHardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google.", ignoreCase = true)
                }

                val isSoftwareOnly = if (Build.VERSION.SDK_INT >= 29) {
                    info.isSoftwareOnly
                } else {
                    info.name.startsWith("OMX.google.", ignoreCase = true)
                }

                val isVendor = if (Build.VERSION.SDK_INT >= 29) {
                    info.isVendor
                } else {
                    !info.name.startsWith("OMX.google.", ignoreCase = true)
                }

                // Media3 1.9+ uses a 9-parameter newInstance. If your version
                // uses 7 parameters, remove the last two (forceDisableAdaptive,
                // forceSecure) arguments.
                val media3Info = MediaCodecInfo.newInstance(
                    /* name= */ info.name,
                    /* mimeType= */ DV_MIME,
                    /* codecMimeType= */ DV_MIME,
                    /* capabilities= */ caps,
                    /* hardwareAccelerated= */ isHardwareAccelerated,
                    /* softwareOnly= */ isSoftwareOnly,
                    /* vendor= */ isVendor,
                    /* forceDisableAdaptive= */ false,
                    /* forceSecure= */ false
                )

                Log.i(TAG, "Found hidden DV decoder: ${info.name} (hw=$isHardwareAccelerated)")
                results.add(media3Info)
            }
            results
        }.getOrElse { e ->
            Log.e(TAG, "Error probing DV decoders", e)
            emptyList()
        }
    }

    /** media3 MediaCodecUtil's rule: what the query asks for is supported, nothing else is required. */
    internal fun matchesPlaybackFeatures(
        requiresSecure: Boolean,
        secureSupported: Boolean,
        secureRequired: Boolean,
        requiresTunneling: Boolean,
        tunnelingSupported: Boolean,
        tunnelingRequired: Boolean
    ): Boolean {
        if (requiresSecure && !secureSupported) return false
        if (!requiresSecure && secureRequired) return false
        if (requiresTunneling && !tunnelingSupported) return false
        if (!requiresTunneling && tunnelingRequired) return false
        return true
    }

    /**
     * Verifies that a codec can actually be instantiated by the platform.
     * Creates and immediately releases. Returns true if the codec is usable.
     *
     * This catches cases where a decoder is listed in MediaCodecList but the
     * firmware refuses to create it (seen on some Chinese Amlogic ROMs with
     * incomplete DV licensing).
     */
    private fun probeCodecInstantiation(componentName: String): Boolean {
        return runCatching {
            val codec = MediaCodec.createByCodecName(componentName)
            codec.release()
            true
        }.getOrElse { e ->
            Log.w(TAG, "Codec probe failed for $componentName: ${e.message}")
            false
        }
    }

    private const val DV_MIME = MimeTypes.VIDEO_DOLBY_VISION
    private const val FEATURE_SECURE = android.media.MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback
    private const val FEATURE_TUNNELED = android.media.MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback
}