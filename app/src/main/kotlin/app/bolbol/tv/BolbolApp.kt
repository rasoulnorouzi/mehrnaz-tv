package app.bolbol.tv

import android.app.Application
import android.content.ComponentCallbacks2
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import app.bolbol.tv.core.data.DataGraph
import app.bolbol.tv.core.player.PlayerFactory
import okio.Path.Companion.toOkioPath

/**
 * Application = the dependency graph (manual DI, docs/decisions.md §Architecture).
 * Everything is lazy so cold start does no I/O before the first frame.
 */
class BolbolApp : Application(), SingletonImageLoader.Factory {

    /** Repositories + the one shared OkHttp client (API, images, video). */
    val data: DataGraph by lazy { DataGraph(this) }

    /** Builds the (single, per player screen) ExoPlayer on the shared OkHttp client. */
    val playerFactory: PlayerFactory by lazy { PlayerFactory(this, data.okHttpClient) }

    /**
     * Coil on the shared OkHttp client, so images get the CDN failover interceptor and share
     * connections with the API. RGB_565 halves poster memory on the 2 GB TV; memory cache 15 % of heap.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { data.okHttpClient })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("bolbol_images").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true)
            .crossfade(false)
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            SingletonImageLoader.get(this).memoryCache?.trimToSize(0)
        }
    }
}
