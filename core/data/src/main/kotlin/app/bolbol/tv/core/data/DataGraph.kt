package app.bolbol.tv.core.data

import android.content.Context
import app.bolbol.tv.core.database.BolbolDatabase
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.WatchRepository
import app.bolbol.tv.core.network.NetworkModule
import okhttp3.OkHttpClient

/**
 * The data layer's single public entry point; `:app` creates one in its Application and hands the
 * repositories to view models. Everything is created lazily on first use (construction is free), and
 * there is exactly one Room database and one OkHttp client per graph.
 *
 * ```
 * val data = DataGraph(applicationContext)
 * val coil = ImageLoader.Builder(ctx).components { add(OkHttpNetworkFetcherFactory(data.okHttpClient)) }.build()
 * val player = ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(data.okHttpClient)))
 * ```
 */
class DataGraph(
    context: Context,
    mirrors: List<String> = NetworkModule.DEFAULT_MIRRORS,
) {
    private val appContext = context.applicationContext
    private val network by lazy { NetworkModule(appContext, mirrors) }
    private val database by lazy { BolbolDatabase.create(appContext) }

    /** Shared by the API, the image loader and the video player (disk cache, timeouts, mirror failover). */
    val okHttpClient: OkHttpClient by lazy { network.okHttpClient }

    val catalog: CatalogRepository by lazy {
        CatalogRepositoryImpl(network.catalog, RoomHomeCacheStore(database.homeCacheDao()))
    }

    val watch: WatchRepository by lazy { WatchRepositoryImpl(database.watchDao()) }

    val prefs: PrefsRepository by lazy { PrefsRepositoryImpl(database.prefsDao()) }

    /** The API mirror calls currently start with (diagnostics). */
    val currentMirror: String get() = network.currentMirror

    /** Replaces the API mirror list at runtime (site roots such as "https://euploadi.com/"). */
    fun setMirrors(mirrors: List<String>) = network.setMirrors(mirrors)
}
