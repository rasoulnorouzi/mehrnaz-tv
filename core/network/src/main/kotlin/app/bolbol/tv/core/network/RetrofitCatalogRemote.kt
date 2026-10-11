package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.mapper.emptyPage
import app.bolbol.tv.core.network.mapper.toDetails
import app.bolbol.tv.core.network.mapper.toHomeSections
import app.bolbol.tv.core.network.mapper.toPage
import kotlinx.coroutines.CancellationException
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
) : CatalogRemoteDataSource {

    override suspend fun fetchHomeJson(): String = guarded {
        service.home().use { it.string() }
    }

    override fun parseHome(json: String): List<HomeSection> = guardedBlocking {
        val envelope = this.json.decodeFromString(HomeEnvelopeDto.serializer(), json)
        if (!envelope.success && envelope.data == null) throw CatalogException.BadResponse()
        envelope.toHomeSections()
    }

    override suspend fun details(id: Long): TitleDetails = guarded {
        val envelope = service.detail(id)
        val data = envelope.data
        if (!envelope.success || data == null) throw CatalogException.NotFound()
        data.toDetails()
    }

    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> = try {
        guarded {
            service.list(
                category = query.category.path(),
                page = page,
                country = query.country,
                genres = query.genre,
                imdbTop250 = if (query.imdbTop250) 1 else null,
            ).toPage(page, query.category)
        }
    } catch (e: CatalogException.NotFound) {
        emptyPage(page)
    }

    override suspend fun search(text: String, page: Int): Page<TitleSummary> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyPage(page)
        return try {
            guarded { service.search(trimmed, page).toPage(page, category = null) }
        } catch (e: CatalogException.NotFound) {
            emptyPage(page)
        }
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
