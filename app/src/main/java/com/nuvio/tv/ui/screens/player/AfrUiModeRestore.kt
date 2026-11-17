package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.FrameRateMatchingMode

/**
 * What `PlayerScreen` does with the display mode, decided before and after the player
 * settings load. A new player's `PlayerUiState` starts at [FrameRateMatchingMode.OFF] until
 * `initializePlayer` copies the real mode in, so the OFF default must not count as the setting.
 */
internal object AfrUiModeRestore {

    /**
     * The player restores the UI mode at once only when the loaded setting is OFF. Before the
     * settings load, the default OFF would switch the TV back on every new player, before a
     * START or START_STOP setting could keep the original mode for its own playback.
     */
    fun restoreWhileOpen(mode: FrameRateMatchingMode, settingsLoaded: Boolean): Boolean =
        settingsLoaded && mode == FrameRateMatchingMode.OFF

    /** What the player does with the display mode when it closes. */
    enum class OnClose {
        /** The switch-back after Stop (`FrameRateUtils.scheduleRestoreOriginalDisplayMode`). */
        SCHEDULE_RESTORE,

        /** Forget the original mode and leave the display as it is. */
        CLEAR
    }

    /**
     * START_STOP switches back after Stop. A player that closes before its settings load
     * switches back too: it never restored while open, so a mode the last player left (its
     * switch-back cancelled when this one attached) would otherwise stay on the TV.
     */
    fun onClose(mode: FrameRateMatchingMode, settingsLoaded: Boolean): OnClose =
        if (!settingsLoaded || mode == FrameRateMatchingMode.START_STOP) {
            OnClose.SCHEDULE_RESTORE
        } else {
            OnClose.CLEAR
        }
}
