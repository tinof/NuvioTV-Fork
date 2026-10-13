package com.nuvio.tv.ui.v2.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.screens.detail.episodeOverlayBackdropDecodeSize
import com.nuvio.tv.ui.screens.detail.episodeOverlayBackdropMemoryCacheKey
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.v2.appearance.LocalResolvedAppearance
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.quality.LocalVisualQuality
import com.nuvio.tv.ui.v2.quality.VisualQualityTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** About the live Enhanced blur (16 dp at 1080p) on the quarter-size decode. */
private const val PREBLURRED_GLASS_RADIUS_PX = 8

/**
 * Glass on boxes without live blur (Android 11) paints a blurred copy of the page's artwork, decoded
 * once per picture. Pages that show full-window artwork name it in [artworkUrl]; nothing runs per frame.
 */
@Stable
class PreblurredGlassBackdrop {
    var artworkUrl by mutableStateOf<String?>(null)
    var picture by mutableStateOf<PreblurredGlassPicture?>(null)
        internal set
}

@Immutable
class PreblurredGlassPicture(val url: String, val image: ImageBitmap)

val LocalPreblurredGlass = staticCompositionLocalOf<PreblurredGlassBackdrop?> { null }

/** Only where live blur cannot run, outside playback, in the glass style that samples artwork. */
internal fun preblurredGlassWanted(
    tier: VisualQualityTier,
    supportsLiveBlur: Boolean,
    playback: Boolean,
    cinematic: Boolean
): Boolean = tier != VisualQualityTier.PERFORMANCE && !supportsLiveBlur && !playback && cinematic

/**
 * The same checks as the live blur source in [nuvioGlass]. Dialogs sit in their own window, where the
 * page offset does not apply, and focused cards never blur.
 */
internal fun preblurredGlassAllowed(
    wanted: Boolean,
    role: GlassRole,
    dark: Boolean,
    neutralBackdrop: Boolean,
    smoked: Boolean
): Boolean = wanted && !dark && !neutralBackdrop && !smoked &&
    role != GlassRole.MODAL && role != GlassRole.CARD_FOCUS

/** Where to draw the picture, in the surface's own pixels: window-sized, centre Crop like the page. */
internal data class PreblurredGlassPlacement(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun preblurredGlassPlacement(
    imageWidth: Int,
    imageHeight: Int,
    windowWidth: Float,
    windowHeight: Float,
    surfaceX: Float,
    surfaceY: Float
): PreblurredGlassPlacement? {
    if (imageWidth <= 0 || imageHeight <= 0 || windowWidth <= 0f || windowHeight <= 0f) return null
    val scale = maxOf(windowWidth / imageWidth, windowHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PreblurredGlassPlacement(
        left = ((windowWidth - width) / 2f - surfaceX).roundToInt(),
        top = ((windowHeight - height) / 2f - surfaceY).roundToInt(),
        width = width.roundToInt(),
        height = height.roundToInt()
    )
}

/** Names [url] as the page's artwork while this caller is composed; clears only what it wrote. */
@Composable
fun PublishPreblurredGlassArtwork(url: String?) {
    val holder = LocalPreblurredGlass.current ?: return
    DisposableEffect(holder, url) {
        if (url != null) holder.artworkUrl = url
        onDispose { if (url != null && holder.artworkUrl == url) holder.artworkUrl = null }
    }
}

/** Decodes the blurred copy only while some glass can use it, so capable boxes do no extra work. */
@Composable
internal fun PreblurredGlassDecoder() {
    val holder = LocalPreblurredGlass.current ?: return
    val quality = LocalVisualQuality.current
    val wanted = preblurredGlassWanted(
        tier = quality.tier,
        supportsLiveBlur = quality.supportsLiveBlur,
        playback = LocalResolvedAppearance.current?.playbackActive == true,
        cinematic = LocalV2Appearance.current?.visualStyle == VisualStyle.CINEMATIC_GLASS
    )
    val context = LocalContext.current
    LaunchedEffect(holder, wanted) {
        if (!wanted) {
            holder.picture = null
            return@LaunchedEffect
        }
        snapshotFlow { holder.artworkUrl }.collectLatest { url ->
            if (url == null) {
                holder.picture = null
            } else if (holder.picture?.url != url) {
                // The old picture stays until the new one is ready, so glass does not flash plain.
                holder.picture = loadPreblurredGlass(context, url)
            }
        }
    }
}

/**
 * The page has just shown this picture, so it is in the disk cache: read it from there only. A network
 * fetch would store a small TMDB size under the page's own disk key.
 */
private suspend fun loadPreblurredGlass(context: Context, url: String): PreblurredGlassPicture? {
    val metrics = context.resources.displayMetrics
    // The episode overlay's blurred backdrop request, with its own radius and no network.
    val (width, height) = episodeOverlayBackdropDecodeSize(metrics.widthPixels, metrics.heightPixels, blur = true)
    val request = ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(episodeOverlayBackdropMemoryCacheKey(url, width, height, true, PREBLURRED_GLASS_RADIUS_PX))
        .diskCacheKey(url)
        .size(width = width, height = height)
        .transformations(BlurTransformation(PREBLURRED_GLASS_RADIUS_PX))
        .diskCachePolicy(CachePolicy.READ_ONLY)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
    val result = try {
        context.imageLoader.execute(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
    val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return null
    return PreblurredGlassPicture(url, bitmap.asImageBitmap())
}
