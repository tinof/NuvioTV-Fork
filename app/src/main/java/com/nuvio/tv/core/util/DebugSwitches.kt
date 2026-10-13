package com.nuvio.tv.core.util

/**
 * debug.* system properties, settable with `adb shell setprop debug.nuvio.<name> <value>`
 * (no root). They let one box A/B a code path without a settings screen.
 */
object DebugSwitches {

    /** Reads an Android system property; null when unset or unavailable (unit tests, old builds). */
    fun property(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** "1"/"true" → true, "0"/"false" → false, anything else (including unset) → null. */
    fun flag(name: String): Boolean? = parseFlag(property(name))

    fun int(name: String): Int? = property(name)?.trim()?.toIntOrNull()

    internal fun parseFlag(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> null
    }
}
