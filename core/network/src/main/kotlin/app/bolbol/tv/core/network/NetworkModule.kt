package app.bolbol.tv.core.network

import android.content.Context
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Entry point of :core:network. Create one per process (see `DataGraph` in :core:data).
 *
 * - [okHttpClient] is the one shared client: it carries the 50 MB disk cache, the timeouts and the
 *   mirror failover (which only touches `/wp-json/` calls). Hand the same instance to the image
 *   loader and the video player so they share connections and cache.
 * - [catalog] is the typed API; its calls fail only with `CatalogException`.
 *
 * Both are created lazily, so constructing the module is free.
 */
class NetworkModule(
    context: Context,
    mirrors: List<String> = DEFAULT_MIRRORS,
) {
    private val appContext = context.applicationContext
    private val mirrorList = MirrorList(mirrors)

    val okHttpClient: OkHttpClient by lazy {
        createOkHttpClient(File(appContext.cacheDir, "http"), mirrorList)
    }

    val catalog: CatalogRemoteDataSource by lazy {
        RetrofitCatalogRemote(createService(okHttpClient, mirrorList))
    }

    /** The mirror API calls currently start with (the last one that answered properly). */
    val currentMirror: String get() = mirrorList.current().toString()

    /** Replaces the mirror list at runtime (e.g. a future settings screen). Entries are site roots. */
    fun setMirrors(mirrors: List<String>) = mirrorList.update(mirrors)

    companion object {
        /** Site roots of the API mirrors, in order of preference (docs/api.md section 1). */
        val DEFAULT_MIRRORS: List<String> = listOf("https://euploadi.com/", "https://uuploadi.ir/")
    }
}

private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024
private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 20L

internal fun createOkHttpClient(cacheDir: File, mirrors: MirrorList): OkHttpClient =
    OkHttpClient.Builder()
        .cache(Cache(cacheDir, CACHE_SIZE_BYTES))
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(MirrorInterceptor(mirrors))
        .build()

internal fun createService(client: OkHttpClient, mirrors: MirrorList): BolboljanService =
    Retrofit.Builder()
        .baseUrl(mirrors.apiBaseUrl())
        .client(client)
        .addConverterFactory(BolboljanJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
        .build()
        .create(BolboljanService::class.java)
