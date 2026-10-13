package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.core.performance.DeviceProfile
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {
    /**
     * The device's ABIs with the kind this install runs as first, so an update keeps the APK type that is
     * installed (a 32-bit install on a 64-bit box stays 32-bit). Exception: on Nvidia boxes with a 64-bit
     * userland (Shield on Android 11) the update moves a 32-bit install to the 64-bit APK.
     */
    fun installedFirst(
        deviceAbis: List<String>,
        is64Bit: Boolean = android.os.Process.is64Bit(),
        nvidia: Boolean = DeviceProfile.isNvidia(),
        supports64Bit: Boolean = Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() == true
    ): List<String> {
        val prefer64 = is64Bit || (nvidia && supports64Bit)
        val (same, other) = deviceAbis.partition { it.contains("64") == prefer64 }
        return same + other
    }

    fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()
    ): GitHubAssetDto? {
        // Only release/full artifacts from our workflow. Never guess an ABI or flavor.
        for (abi in supportedAbis) {
            assets.firstOrNull { it.name == "app-full-$abi-release.apk" }?.let { return it }
        }
        return assets.firstOrNull { it.name == "app-full-universal-release.apk" }
    }
}
