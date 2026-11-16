package com.nuvio.tv.ui.screens.player.iec

/**
 * What the sink logs when a TrueHD or DTS-HD format goes to Android's own passthrough instead of the IEC 61937
 * packer. "HBR DIRECT" when the packer was never meant to run ([hbrAllowed] false: System passthrough or the
 * output route; or the format is not carried on IEC); "HBR RAW … fallback" only when it was meant to and did not.
 */
internal object IecForwardLines {
    class Lines(val log: String, val event: String)

    fun of(
        mime: String?,
        hbrAllowed: Boolean,
        iecCarries: Boolean,
        iecFailedThisSession: Boolean,
        tunnelReady: Boolean
    ): Lines = if (!hbrAllowed || !iecCarries) {
        Lines(
            log = "HBR DIRECT mime=$mime (platform passthrough, IEC off by design)",
            event = "iec_hbr_direct mime=$mime hbrAllowed=$hbrAllowed iecCarries=$iecCarries"
        )
    } else {
        Lines(
            log = "HBR RAW mime=$mime (compressed, not PCM)",
            event = "iec_hbr_raw_fallback mime=$mime " +
                "iecFailedThisSession=$iecFailedThisSession tunnelReady=$tunnelReady"
        )
    }
}
