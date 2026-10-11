package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.network.mapper.ImageUrls
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * [AccountRemoteDataSource] on Retrofit. Mirror failover and the bearer token are handled below it by the shared
 * OkHttp client. Only the **shape** (key names and JSON types) of an answer is ever logged, and only when
 * `adb shell setprop log.tag.BolbolAccount DEBUG` was set; values, tokens and passwords never are.
 */
internal class RetrofitAccountRemote(
    private val service: AccountService,
    private val images: ImageUrls = ImageUrls.Default,
    private val log: (String) -> Unit = AccountLog::debug,
) : AccountRemoteDataSource {

    override suspend fun login(username: String, password: String): LoginOutcome = try {
        val response = service.login(username, password)
        val body = response.bodyText()
        trace("login", response.code(), body)
        AccountParsing.classifyLogin(response.code(), body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoginOutcome.Failed(failureMessage(e))
    }

    override suspend fun profile(): RemoteResult<RemoteProfile> = call("profile", { service.profile() }) { json ->
        if (json !is JsonObject) null else AccountParsing.parseProfile(json)
    }

    override suspend fun favorites(): RemoteResult<RemoteFavorites> {
        val first = favoritesPage(null)
        if (first !is RemoteResult.Ok) return first.passThrough()
        val perPage = first.value.perPage
        val total = first.value.totalPages
        val all = LinkedHashMap<Long, RemoteFavorite>()
        first.value.items.forEach { all.putIfAbsent(it.id, it) }
        var lastSize = first.value.items.size
        var page = 1
        var complete = true
        while (true) {
            val more = when {
                total != null -> page < total
                perPage != null -> lastSize >= perPage // a full page: there may be another
                else -> false
            }
            if (!more) break
            if (page >= AccountWire.MAX_LIST_PAGES) {
                complete = false
                break
            }
            page++
            val next = favoritesPage(page)
            if (next !is RemoteResult.Ok) return next.passThrough()
            val added = next.value.items.count { all.putIfAbsent(it.id, it) == null }
            if (added == 0) { // the server ignores `page`, or repeats itself: we cannot prove we saw everything
                complete = false
                break
            }
            lastSize = next.value.items.size
        }
        return RemoteResult.Ok(RemoteFavorites(all.values.toList(), complete))
    }

    private class FavoritesPage(val items: List<RemoteFavorite>, val totalPages: Int?, val perPage: Int?)

    private suspend fun favoritesPage(page: Int?): RemoteResult<FavoritesPage> =
        call("favorites", { service.favorites(page) }) { json ->
            AccountParsing.extractFavorites(json, images)
                ?.let { FavoritesPage(it, AccountParsing.totalPages(json), AccountParsing.perPage(json)) }
        }

    override suspend fun toggleFavorite(postId: Long): RemoteResult<Unit> =
        call("favorite-toggle", { service.toggleFavorite(postId) }) { Unit }

    override suspend fun progressList(): RemoteResult<List<RemoteProgressItem>> =
        call("watch-list", { service.progressList() }) { json ->
            AccountParsing.extractProgress(json, images)?.take(AccountWire.MAX_PROGRESS_ITEMS)
        }

    override suspend fun saveProgress(postId: Long, positionSeconds: Int, season: Int, episode: Int): RemoteResult<Unit> {
        val parts = buildList {
            add(MultipartBody.Part.createFormData(AccountWire.FIELD_POST_ID, postId.toString()))
            add(MultipartBody.Part.createFormData(AccountWire.FIELD_CURRENT_TIME, positionSeconds.coerceAtLeast(0).toString()))
            if (season > 0 && episode > 0) {
                add(MultipartBody.Part.createFormData(AccountWire.FIELD_SEASON, season.toString()))
                add(MultipartBody.Part.createFormData(AccountWire.FIELD_EPISODE, episode.toString()))
            }
        }
        return call("save-progress", { service.saveProgress(parts) }) { Unit }
    }

    // --- plumbing -------------------------------------------------------------------------------------

    /**
     * Runs [request] and turns the answer into a [RemoteResult]: 401/403 is [RemoteResult.Unauthorized]; any other
     * non-2xx, non-JSON body, `success:false` or unreadable shape ([parse] returns null) is [RemoteResult.Failed].
     */
    private suspend fun <T : Any> call(
        name: String,
        request: suspend () -> Response<ResponseBody>,
        parse: (JsonElement) -> T?,
    ): RemoteResult<T> = try {
        val response = request()
        val code = response.code()
        val body = response.bodyText()
        val json = AccountParsing.parseJson(body)
        trace(name, code, body, json)
        when {
            code == 401 || code == 403 -> RemoteResult.Unauthorized
            code !in 200..299 || json == null -> RemoteResult.Failed
            AccountParsing.successFlag(json) == false -> RemoteResult.Failed
            else -> parse(json)?.let { RemoteResult.Ok(it) } ?: RemoteResult.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        RemoteResult.Failed
    }

    /** Re-types a non-Ok result of another type. */
    private fun RemoteResult<*>.passThrough(): RemoteResult<Nothing> = when (this) {
        is RemoteResult.Unauthorized -> RemoteResult.Unauthorized
        else -> RemoteResult.Failed
    }

    private fun Response<ResponseBody>.bodyText(): String =
        (body() ?: errorBody())?.use { it.string() }.orEmpty()

    private fun trace(name: String, code: Int, body: String, json: JsonElement? = AccountParsing.parseJson(body)) {
        log("$name HTTP $code shape=${AccountParsing.describeShape(json)}")
    }

    private fun failureMessage(e: Exception): String = when (e.toCatalogException()) {
        is CatalogException.Offline -> AccountParsing.MSG_OFFLINE
        is CatalogException.AllMirrorsDown -> AccountParsing.MSG_SERVER
        else -> AccountParsing.MSG_BAD_RESPONSE
    }
}

/** Debug-only shape logging. Silent unless the tag was enabled with `setprop log.tag.BolbolAccount DEBUG`. */
internal object AccountLog {
    private const val TAG = "BolbolAccount"

    fun debug(message: String) {
        try {
            if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) android.util.Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // JVM unit tests: android.util.Log is not available.
        }
    }
}
