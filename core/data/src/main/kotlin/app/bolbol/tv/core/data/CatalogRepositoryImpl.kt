package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.HomeCacheDao
import app.bolbol.tv.core.database.HomeCacheEntity
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.LocalHomeRow
import app.bolbol.tv.core.model.CatalogFacets
import app.bolbol.tv.core.model.CatalogFilter
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.Genre
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.network.CatalogRemoteDataSource
import app.bolbol.tv.core.network.GenreTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Where the raw home JSON is kept between launches. Room in production, a fake in tests. */
internal interface HomeCacheStore {
    suspend fun load(): CachedHome?
    suspend fun save(json: String, fetchedAtMs: Long)
}

internal data class CachedHome(val json: String, val fetchedAtMs: Long)

internal class RoomHomeCacheStore(private val dao: HomeCacheDao) : HomeCacheStore {
    override suspend fun load(): CachedHome? = dao.get()?.let { CachedHome(it.json, it.fetchedAtMs) }

    override suspend fun save(json: String, fetchedAtMs: Long) {
        dao.upsert(HomeCacheEntity(json = json, fetchedAtMs = fetchedAtMs))
    }
}

/**
 * Catalogue repository: network + persisted home cache + in-memory details cache.
 *
 * - [homeFeed] is stale-while-revalidate: cached rows first (fromCache = true), then fresh rows. The raw
 *   server JSON is cached, so a schema fix in the mappers also applies to old cache entries.
 * - [details] keeps the last 32 titles for [DETAILS_TTL_MS], so going back to a details screen is instant
 *   while a long-running process still picks up new episodes.
 * - Everything runs on [io].
 */
/** Page size of local search results (the grid loads more when the last rows get focus). */
private const val LOCAL_PAGE_SIZE = 24

internal class CatalogRepositoryImpl(
    private val remote: CatalogRemoteDataSource,
    private val homeCache: HomeCacheStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    detailsCacheSize: Int = DETAILS_CACHE_SIZE,
    detailsTtlMs: Long = DETAILS_TTL_MS,
    /** The site's own daily search index on disk (v1.5); null in tests that don't need it. */
    private val searchIndex: LocalSearchIndex? = null,
) : CatalogRepository {

    private val suggestionIndex = TitleSuggestionIndex(io)
    private val detailsCache = LruTtlCache<Long, TitleDetails>(detailsCacheSize, detailsTtlMs, clock)

    override fun homeFeed(): Flow<HomeFeed> = flow {
        val cached = loadCachedSections()
        if (cached != null) emit(HomeFeed(cached, fromCache = true).also { suggestionIndex.addSections(it.sections) })

        // Emit outside the try block: a failure of the collector must not be mistaken for a network failure.
        val fresh: List<HomeSection>? = try {
            fetchFreshSections()
        } catch (e: CatalogException) {
            if (cached == null) throw e
            null // keep showing the cache, finish quietly
        }
        if (fresh != null) emit(HomeFeed(fresh, fromCache = false).also { suggestionIndex.addSections(it.sections) })
    }.flowOn(io)

    override suspend fun details(id: Long): TitleDetails {
        detailsCache.get(id)?.let { return it }
        val fresh = withContext(io) { remote.details(id) }
        detailsCache.put(id, fresh)
        return fresh
    }

    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> =
        withContext(io) { remote.list(query, page).also { suggestionIndex.add(it.items) } }

    /**
     * The on-device copy of the site's index first (it has every title, also ones added after this build: the API
     * search misses new titles by their Latin name); the API search when there is no index yet or it finds nothing.
     */
    override suspend fun search(text: String, page: Int): Page<TitleSummary> {
        if (text.isBlank()) return Page(emptyList(), page = 1, totalPages = 1)
        val local = searchIndex?.search(text)
        if (!local.isNullOrEmpty()) {
            val totalPages = (local.size + LOCAL_PAGE_SIZE - 1) / LOCAL_PAGE_SIZE
            val items = local.drop((page - 1).coerceAtLeast(0) * LOCAL_PAGE_SIZE).take(LOCAL_PAGE_SIZE)
            return Page(items, page = page, totalPages = totalPages)
        }
        return withContext(io) { remote.search(text.trim(), page).also { suggestionIndex.add(it.items) } }
    }

    override suspend fun suggestions(prefix: String, limit: Int): List<String> =
        searchIndex?.suggest(prefix, limit)?.takeIf { it.isNotEmpty() } ?: suggestionIndex.suggest(prefix, limit)

    // v1.6 categories and Home rows, from the same on-device index ----------------------------------------------

    private val browseLock = Any()
    private var browseCache: Triple<Long, CatalogFilter, List<TitleSummary>>? = null
    private val facetsCache = HashMap<Pair<Long, TitleType>, CatalogFacets>()

    override suspend fun browse(filter: CatalogFilter, page: Int): Page<TitleSummary>? {
        val index = searchIndex ?: return null
        val version = index.version
        val cached = synchronized(browseLock) { browseCache?.takeIf { it.first == version && it.second == filter }?.third }
        val all = cached ?: index.browse(filter)?.also { list ->
            synchronized(browseLock) { browseCache = Triple(version, filter, list) }
        } ?: return null
        val totalPages = ((all.size + LOCAL_PAGE_SIZE - 1) / LOCAL_PAGE_SIZE).coerceAtLeast(1)
        val items = all.drop((page - 1).coerceAtLeast(0) * LOCAL_PAGE_SIZE).take(LOCAL_PAGE_SIZE)
        return Page(items, page = page, totalPages = totalPages)
    }

    override suspend fun facets(type: TitleType): CatalogFacets? {
        val index = searchIndex ?: return null
        val key = index.version to type
        synchronized(browseLock) { facetsCache[key] }?.let { return it }
        return index.facets(type)?.also { synchronized(browseLock) { facetsCache[key] = it } }
    }

    override suspend fun localHomeRows(watchedIds: List<Long>): List<LocalHomeRow> {
        val index = searchIndex ?: return emptyList()
        return try {
            LocalHomeRows.build(index, watchedIds, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Refreshes the on-device search index if it is older than half a day (called once per app start). */
    suspend fun refreshSearchIndex(force: Boolean = false): Boolean = searchIndex?.refreshIfStale(force) ?: false

    override suspend fun genres(category: Category): List<Genre> = GenreTable.forCategory(category)

    /** Cached rows, or null when there is no cache, it is unreadable, or it no longer parses. */
    private suspend fun loadCachedSections(): List<HomeSection>? {
        val cached = try {
            homeCache.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a broken cache is not an error for the user
        } ?: return null
        return try {
            remote.parseHome(cached.json).takeIf { it.isNotEmpty() }
        } catch (e: CatalogException) {
            null
        }
    }

    private suspend fun fetchFreshSections(): List<HomeSection> {
        val raw = remote.fetchHomeJson()
        val sections = remote.parseHome(raw)
        if (sections.isEmpty()) throw CatalogException.BadResponse() // never overwrite a good cache with nothing
        try {
            homeCache.save(raw, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Failing to persist must not hide fresh rows from the user.
        }
        return sections
    }

    companion object {
        const val DETAILS_CACHE_SIZE = 32
        const val DETAILS_TTL_MS = 10 * 60 * 1000L
    }
}
