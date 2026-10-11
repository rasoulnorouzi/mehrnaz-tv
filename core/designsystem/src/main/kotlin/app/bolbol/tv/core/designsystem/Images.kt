package app.bolbol.tv.core.designsystem

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

/**
 * Image loader configured for the 2 GB TV (docs/perf-budget.md): memory cache capped at 15 %
 * of the app's heap class, 64 MB disk cache, hardware bitmaps (Coil's default on API 26+),
 * no crossfade. Install once in the Application:
 * `SingletonImageLoader.setSafe { bolbolImageLoader(it) }`.
 */
fun bolbolImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("bolbol_images").toOkioPath())
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .build()

/**
 * The only way components draw remote images: a Coil [AsyncImage] whose request carries an
 * **explicit pixel size** (so the decode is never larger than the layout, and a memory-cache hit
 * is shown without a blank frame), `crossfade(false)` and `ContentScale.Crop`. There is no
 * placeholder painter: the parent's solid surface colour is the placeholder.
 */
@Composable
internal fun CoverImage(
    url: String,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier = Modifier,
    onSuccess: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val request = remember(url, widthPx, heightPx, context) {
        ImageRequest.Builder(context)
            .data(url)
            .size(widthPx, heightPx)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        onSuccess = onSuccess?.let { cb -> { _ -> cb() } },
        onError = onError?.let { cb -> { _ -> cb() } },
    )
}

/** [CoverImage] sized from the layout size in dp at the current density. */
@Composable
internal fun CoverImage(
    url: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val w = with(density) { width.roundToPx() }
    val h = with(density) { height.roundToPx() }
    CoverImage(url = url, widthPx = w, heightPx = h, modifier = modifier)
}
