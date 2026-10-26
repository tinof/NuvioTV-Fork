package com.nuvio.tv.ui.util

/**
 * Whether a key is held down and auto-repeating. MainActivity feeds it from
 * dispatchKeyEvent; screens only read it, at the moment they choose a delay.
 */
object KeyHoldState {
    @Volatile
    var held: Boolean = false
        private set

    fun onKey(down: Boolean, repeatCount: Int) {
        held = isHeld(down, repeatCount)
    }

    /** A fresh press or any key-up ends a hold; only repeats of a down count. */
    internal fun isHeld(down: Boolean, repeatCount: Int): Boolean = down && repeatCount > 0
}
