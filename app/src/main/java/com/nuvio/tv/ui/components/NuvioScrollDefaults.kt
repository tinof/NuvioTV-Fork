package com.nuvio.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.nuvio.tv.ui.theme.NuvioFocus

@OptIn(ExperimentalFoundationApi::class)
object NuvioScrollDefaults {
    val smoothScrollSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        // A duration token (180) used as stiffness made scrolls lag behind held D-pad focus.
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMediumLow
        )

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            if (containerSize <= 0f || size <= 0f) return 0f
            val itemCenter = offset + size / 2f
            val viewportTarget = containerSize * NuvioFocus.tokens.scrollViewportTarget
            val distance = itemCenter - viewportTarget
            if (offset >= 0f && offset + size <= containerSize && distance > 0f && distance < 12f) {
                return 0f
            }
            return distance
        }
    }
}
