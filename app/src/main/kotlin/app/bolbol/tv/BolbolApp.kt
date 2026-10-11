package app.bolbol.tv

import android.app.Application
import android.content.ComponentCallbacks2
import app.bolbol.tv.core.data.DataGraph
import app.bolbol.tv.core.player.PlayerFactory
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.feature.details.MyListActions
import app.bolbol.tv.feature.settings.AppSettings
import app.bolbol.tv.feature.tvhome.WatchNextPublisher
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

/**
 * Application = the dependency graph (manual DI, docs/decisions.md §Architecture).
 * Everything is lazy so cold start does no I/O before the first frame.
 */
class BolbolApp : Application(), SingletonImageLoader.Factory {

    /** Long-lived background work (Watch Next publishing). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Repositories + the one shared OkHttp client (API, images, video). */
    val data: DataGraph by lazy { DataGraph(this) }

    /** Builds the (single, per player screen) ExoPlayer on the shared OkHttp client. */
    val playerFactory: PlayerFactory by lazy { PlayerFactory(this, data.okHttpClient) }

    /** App-level switches (performance overlay). */
    val appSettings: AppSettings by lazy { AppSettings(this) }

    val versionName: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
    }

    /** «لیست من» button on details → local list (+ best-effort sync with the site's favourites when logged in). */
    val myListActions: MyListActions by lazy {
        object : MyListActions {
            override fun isInMyList(postId: Long): Flow<Boolean> = data.account.isInMyList(postId)
            override suspend fun setInMyList(title: TitleSummary, inList: Boolean) {
                data.account.setInMyList(title, inList)
                appScope.launch { data.account.syncMyList() }
            }
        }
    }

    /**
     * After the player closes: send the saved position to the site's watch-list (logged-in only; no-op otherwise),
     * so the phone app / website can continue from the same point. Runs in the background, never blocks Back.
     */
    fun pushProgressAfterPlayback(postId: Long, season: Int, episode: Int) {
        appScope.launch {
            // Give the player's final save (on dispose) a moment to land in Room.
            kotlinx.coroutines.delay(1_500)
            data.watch.progress(WatchKey(postId, season, episode))?.let { data.pushProgress(it) }
        }
    }

    private var backgroundStarted = false

    /**
     * Started by MainActivity after the first frame (keeps cold start free of DB/IO work):
     * mirrors Continue Watching into the Android TV home "Watch Next" row.
     */
    @OptIn(FlowPreview::class)
    fun startBackgroundWork() {
        if (backgroundStarted) return
        backgroundStarted = true
        val publisher = WatchNextPublisher(this)
        // Account sync (no-ops when logged out): pull the site's progress + favourites once per app start.
        appScope.launch {
            runCatching { data.pullProgress() }
            runCatching { data.account.syncMyList() }
        }
        appScope.launch {
            data.watch.continueWatching().debounce(2_000).collect { publisher.publish(it) }
        }
    }

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
