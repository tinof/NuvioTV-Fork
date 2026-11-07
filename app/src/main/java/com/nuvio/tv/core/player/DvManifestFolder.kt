package com.nuvio.tv.core.player

import java.net.URI
import java.net.URISyntaxException

/**
 * ROADMAP DV4.8: where converted HLG tracks look up their playback manifests. The saved player setting
 * (a toggle and an `http(s)://` folder, per profile) survives a reboot; the `debug.nuvio.dv_manifest_url`
 * setprop ([ShotListManifests.URL_SWITCH]) stays as a debug override and wins whenever it is set, also
 * with a `file://` folder and also when the saved toggle is off.
 */
internal object DvManifestFolder {

    data class Resolved(val url: String, val fromSetting: Boolean)

    /** [input] trimmed when it is an `http(s)://` URL with a host and no query or fragment, else null. */
    fun normalize(input: String): String? {
        val url = input.trim().takeIf { it.isNotEmpty() } ?: return null
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        return url
    }

    /** The folder to look up, or null for no lookup. A non-blank [property] wins over the saved setting. */
    fun resolve(property: String?, savedEnabled: Boolean, savedUrl: String): Resolved? {
        property?.trim()?.takeIf { it.isNotEmpty() }?.let { return Resolved(it, fromSetting = false) }
        if (!savedEnabled) return null
        return normalize(savedUrl)?.let { Resolved(it, fromSetting = true) }
    }
}
