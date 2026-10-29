package app.bolbol.tv.core.network

import app.bolbol.tv.core.network.dto.SearchIndexEntryDto
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.ExperimentalSerializationApi
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.mapper.ImageUrls
import app.bolbol.tv.core.network.mapper.PlayableLinks
import app.bolbol.tv.core.network.mapper.mapLinks
import app.bolbol.tv.core.network.mapper.withPlayableLinks
import app.bolbol.tv.core.network.mapper.emptyPage
import app.bolbol.tv.core.network.mapper.toDetails
import app.bolbol.tv.core.network.mapper.toHomeSections
import app.bolbol.tv.core.network.mapper.toPage
import app.bolbol.tv.core.network.mapper.toSummary
import app.bolbol.tv.core.network.mapper.toIndexed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal class RetrofitCatalogRemote(
    private val service: BolboljanService,
    private val json: Json = BolboljanJson,
    private val images: ImageUrls = ImageUrls.Default,
) : CatalogRemoteDataSource {

    override suspend fun fetchHomeJson(): String = guarded {
        service.home().use { it.string() }
    }

    override fun parseHome(json: String): List<HomeSection> = guardedBlocking {
        val envelope = this.json.decodeFromString(HomeEnvelopeDto.serializer(), json)
        if (!envelope.success && envelope.data == null) throw CatalogException.BadResponse()
        envelope.toHomeSections(images)
    }

    /**
     * `post/{id}` (metadata, and streams when the server fills them) and `post/player-links?id=` (what the official
     * app plays from) are requested in parallel. Streams/seasons come from player-links when that call succeeds and
     * has links of the title's kind, otherwise from the detail's own links. A failing player-links call never fails
     * the details; a failing detail call always does (NotFound, Offline ...).
     */
    override suspend fun details(id: Long): TitleDetails = guarded {
        coroutineScope {
            val links = async { fetchPlayerLinks(id) }
            val envelope = service.detail(id)
            val data = envelope.data
            if (!envelope.success || data == null) throw CatalogException.NotFound()
            data.toDetails(images).withPlayableLinks(links.await())
        }
    }

    /** Null when the call fails or has no links; never throws except for cancellation. */
    private suspend fun fetchPlayerLinks(id: Long): PlayableLinks? = try {
        val envelope = service.playerLinks(id)
        if (!envelope.success) null else mapLinks(envelope.data.playerLinks, envelope.data.downloadLinks).takeUnless { it.isEmpty }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> = try {
        guarded {
            service.list(
                category = query.category.path(),
                page = page,
                country = query.country,
                genres = query.genre,
                imdbTop250 = if (query.imdbTop250) 1 else null,
            ).toPage(page, query.category, images)
        }
    } catch (e: CatalogException.NotFound) {
        emptyPage(page)
    }

    override suspend fun search(text: String, page: Int): Page<TitleSummary> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyPage(page)
        return try {
            guarded { service.search(trimmed, page).toPage(page, category = null, images = images) }
        } catch (e: CatalogException.NotFound) {
            emptyPage(page)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun fetchSearchIndex(ifModifiedSince: String?, sink: (IndexedTitle) -> Unit): SearchIndexFetch =
        guarded {
            val response = service.searchIndex(ifModifiedSince)
            if (response.code() == 304) return@guarded SearchIndexFetch.NotModified
            if (!response.isSuccessful) throw HttpException(response)
            val body = response.body() ?: throw IOException("empty search index")
            var count = 0
            body.use { b ->
                json.decodeToSequence(b.byteStream(), SearchIndexEntryDto.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
                    .forEach { dto -> dto.toIndexed(images)?.let { sink(it); count++ } }
            }
            SearchIndexFetch.Updated(response.headers()["Last-Modified"], count)
        }

    private fun Category.path(): String = when (this) {
        Category.MOVIES -> "movies"
        Category.SERIES -> "series"
        Category.CARTOONS -> "cartoons"
        Category.DOCUMENTARIES -> "documentaries"
        Category.SUGGESTIONS -> "suggestions"
    }
}

/** Runs [block] and translates every failure into a [CatalogException]. */
internal suspend fun <T> guarded(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw e.toCatalogException()
}

/** Non-suspending twin of [guarded] for pure parsing. */
internal fun <T> guardedBlocking(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw e.toCatalogException()
}

internal fun Throwable.toCatalogException(): CatalogException = when (this) {
    is CatalogException -> this
    is HttpException -> when (code()) {
        404 -> CatalogException.NotFound()
        in 500..599 -> CatalogException.AllMirrorsDown(this)
        else -> CatalogException.BadResponse(this)
    }

    is AllMirrorsFailedException ->
        if (reachedAnyMirror || failures.any { !it.isConnectivityFailure() }) {
            CatalogException.AllMirrorsDown(this)
        } else {
            CatalogException.Offline(this)
        }

    // Anything else from the socket layer (also a body that dies mid-read).
    is IOException -> if (isConnectivityFailure()) CatalogException.Offline(this) else CatalogException.AllMirrorsDown(this)
    // SerializationException, mapper bugs, unexpected shapes: never crash the TV box over a bad payload.
    else -> CatalogException.BadResponse(this)
}

private fun Throwable.isConnectivityFailure(): Boolean =
    this is UnknownHostException || this is ConnectException || this is NoRouteToHostException || this is SocketTimeoutException
