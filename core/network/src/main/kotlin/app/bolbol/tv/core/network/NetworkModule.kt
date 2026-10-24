package app.bolbol.tv.core.network

import android.content.Context
import app.bolbol.tv.core.network.mapper.ImageUrls
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
 * - [okHttpClient] is the one shared client: it carries the 50 MB disk cache, the timeouts, the API mirror
 *   failover (`/wp-json/` only) and the image CDN failover (`/wp-content/uploads/` only). Hand the same
 *   instance to the image loader and the video player so they share connections and cache.
 * - [catalog] is the typed API; its calls fail only with `CatalogException`. Every image URL it returns
 *   (poster, backdrop, photos, related) already points at the primary image host.
 *
 * Both are created lazily, so constructing the module is free.
 */
class NetworkModule(
    context: Context,
    mirrors: List<String> = DEFAULT_MIRRORS,
    imageHosts: List<String> = DEFAULT_IMAGE_HOSTS,
    private val tokenProvider: TokenProvider = TokenProvider { null },
) {
    private val appContext = context.applicationContext
    private val learnedPrefs = appContext.getSharedPreferences(LEARNED_PREFS, Context.MODE_PRIVATE)

    /** Hosts the site moved to (learned from redirects, newest first) + the built-in list. */
    private val mirrorList = MirrorList(
        (learnedPrefs.getString(LEARNED_KEY, null).orEmpty().lines() + mirrors).map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.removeSuffix("/").substringAfter("://") },
    ).also { list ->
        list.onLearned = { all -> learnedPrefs.edit().putString(LEARNED_KEY, (all - mirrors.toSet()).joinToString("\n")).apply() }
    }
    private val imageHostList = MirrorList(imageHosts)

    val okHttpClient: OkHttpClient by lazy {
        createOkHttpClient(File(appContext.cacheDir, "http"), mirrorList, imageHostList, tokenProvider)
    }

    val catalog: CatalogRemoteDataSource by lazy {
        RetrofitCatalogRemote(
            service = createService(okHttpClient, mirrorList),
            images = ImageUrls { imageHostList.primary() },
        )
    }

    /**
     * The optional account (login, favourites = My List, server progress). The login token comes from the
     * [TokenProvider] given to this module and is attached by the shared client to `user/` calls only.
     */
    val account: AccountRemoteDataSource by lazy {
        RetrofitAccountRemote(
            service = createAccountService(okHttpClient, mirrorList),
            images = ImageUrls { imageHostList.primary() },
        )
    }

    /** The mirror API calls currently start with (the last one that answered properly). */
    val currentMirror: String get() = mirrorList.current().toString()

    /** The image host image requests currently start with (the last one that served an image). */
    val currentImageHost: String get() = imageHostList.current().toString()

    /** Replaces the mirror list at runtime (e.g. a future settings screen). Entries are site roots. */
    fun setMirrors(mirrors: List<String>) = mirrorList.update(mirrors)

    /** Replaces the image host list at runtime; the first entry becomes the host written into mapped URLs. */
    fun setImageHosts(imageHosts: List<String>) = imageHostList.update(imageHosts)

    companion object {
        private const val LEARNED_PREFS = "bolbol_mirrors"
        private const val LEARNED_KEY = "learned"

        /**
         * Site roots of the API mirrors, in order of preference (docs/api.md section 1). The last two are the hosts the
         * official TV app uses (docs/auth.md section 0); both serve the same `mapi/v1` JSON.
         */
        val DEFAULT_MIRRORS: List<String> = listOf(
            "https://euploadi.com/",
            "https://uuploadi.ir/",
            "https://playgen.ir/",
            "https://arzanycenter.ir/",
        )

        /**
         * Image CDN site roots, in order of preference (docs/api.md quirk 7). The API's own hosts 404 for uploads,
         * so mapped image URLs get the first host and `ImageCdnInterceptor` falls back to the next ones.
         */
        val DEFAULT_IMAGE_HOSTS: List<String> = listOf("https://majnoonbazar.ir/", "https://seo2024.ir/")
    }
}

private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024
private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 20L

internal fun createOkHttpClient(
    cacheDir: File,
    mirrors: MirrorList,
    imageHosts: MirrorList,
    tokens: TokenProvider = TokenProvider { null },
): OkHttpClient =
    OkHttpClient.Builder()
        .cache(Cache(cacheDir, CACHE_SIZE_BYTES))
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(MirrorInterceptor(mirrors))
        .addInterceptor(SearchIndexInterceptor(mirrors))
        .addInterceptor(ImageCdnInterceptor(imageHosts))
        // Network interceptor: sees the real mirror of every attempt and every redirect hop (see AuthTokenInterceptor).
        .addNetworkInterceptor(AuthTokenInterceptor(mirrors, tokens))
        .build()

private fun retrofit(client: OkHttpClient, mirrors: MirrorList): Retrofit =
    Retrofit.Builder()
        .baseUrl(mirrors.apiBaseUrl())
        .client(client)
        .addConverterFactory(BolboljanJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
        .build()

internal fun createService(client: OkHttpClient, mirrors: MirrorList): BolboljanService =
    retrofit(client, mirrors).create(BolboljanService::class.java)

internal fun createAccountService(client: OkHttpClient, mirrors: MirrorList): AccountService =
    retrofit(client, mirrors).create(AccountService::class.java)
