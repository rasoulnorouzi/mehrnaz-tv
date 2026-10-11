package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.ContinueWatchingRow
import app.bolbol.tv.core.database.PrefsDao
import app.bolbol.tv.core.database.PrefsEntity
import app.bolbol.tv.core.database.ProgressEntity
import app.bolbol.tv.core.database.SubtitleOffsetEntity
import app.bolbol.tv.core.database.SubtitleStyleEntity
import app.bolbol.tv.core.database.TitleSnapshotEntity
import app.bolbol.tv.core.database.WatchDao
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.CatalogRemoteDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal fun summary(id: Long, title: String = "Title $id", type: TitleType? = TitleType.MOVIE) = TitleSummary(
    id = id, type = type, title = title, faTitle = "fa $id", posterUrl = "https://h/p$id.webp", backdropUrl = null,
    year = 2020, imdbRating = 7.5f, hasDub = true, hasSubtitle = false,
)

internal fun section(id: String, vararg titles: Long) =
    HomeSection(id = id, title = "Row $id", items = titles.map { summary(it) }, viewAll = ListQuery(Category.MOVIES))

/**
 * Fake network. [parseHome] understands tiny pseudo-payloads: "rows:A,B" gives sections A and B,
 * "empty" gives no sections and anything else throws BadResponse (like an unparsable body).
 */
internal class FakeRemote : CatalogRemoteDataSource {
    var homeJson: String = "rows:fresh"
    var homeError: CatalogException? = null
    var detailsCalls = 0
    var homeCalls = 0
    var searchCalls = 0
    var listCalls = 0
    val failingDetails = mutableSetOf<Long>()

    override suspend fun fetchHomeJson(): String {
        homeCalls++
        homeError?.let { throw it }
        return homeJson
    }

    override fun parseHome(json: String): List<HomeSection> = when {
        json == "empty" -> emptyList()
        json.startsWith("rows:") -> json.removePrefix("rows:").split(',').map { section(it, 1) }
        else -> throw CatalogException.BadResponse()
    }

    override suspend fun details(id: Long): TitleDetails {
        detailsCalls++
        if (id in failingDetails) throw CatalogException.Offline()
        return details(summary(id))
    }

    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> {
        listCalls++
        return Page(listOf(summary(100L + page)), page, 3)
    }

    override suspend fun search(text: String, page: Int): Page<TitleSummary> {
        searchCalls++
        return Page(listOf(summary(500)), page, 1)
    }

    private fun details(summary: TitleSummary) = TitleDetails(
        summary = summary, type = TitleType.MOVIE, plot = "", runtimeMinutes = null, ageRating = null,
        metacritic = null, genres = emptyList(), countries = emptyList(), actors = emptyList(),
        directors = emptyList(), trailerUrl = null, updateText = null, streams = emptyList(),
        seasons = emptyList(), related = emptyList(),
    )
}

internal class FakeHomeCache(var stored: CachedHome? = null) : HomeCacheStore {
    var saves = 0
    var failLoad = false
    var failSave = false

    override suspend fun load(): CachedHome? {
        if (failLoad) error("disk unreadable")
        return stored
    }

    override suspend fun save(json: String, fetchedAtMs: Long) {
        if (failSave) error("disk full")
        saves++
        stored = CachedHome(json, fetchedAtMs)
    }
}

/** In-memory WatchDao; the transactional template methods of the base class run unchanged. */
internal class FakeWatchDao : WatchDao() {
    val progressRows = MutableStateFlow<Map<String, ProgressEntity>>(emptyMap())
    val snapshots = MutableStateFlow<Map<Long, TitleSnapshotEntity>>(emptyMap())
    var continueRows: List<ContinueWatchingRow> = emptyList()

    override suspend fun upsertSnapshot(snapshot: TitleSnapshotEntity) {
        snapshots.value = snapshots.value + (snapshot.postId to snapshot)
    }

    override suspend fun upsertProgress(progress: ProgressEntity) {
        progressRows.value = progressRows.value + (progress.key to progress)
    }

    override suspend fun markCompleted(key: String): Int {
        val row = progressRows.value[key] ?: return 0
        val position = if (row.durationMs > 0) row.durationMs else row.positionMs
        progressRows.value = progressRows.value + (key to row.copy(completed = true, positionMs = position, source = ProgressEntity.SOURCE_LOCAL))
        return 1
    }

    /** Same predicate as the SQL of [WatchDao.repairRemoteImports]. */
    override suspend fun repairRemoteImports(): Int {
        var repaired = 0
        progressRows.value = progressRows.value.mapValues { (_, row) ->
            val bogusDuration = row.durationMs > 0 &&
                (row.durationMs < 60_000 || row.durationMs > 21_600_000 || row.durationMs < row.positionMs)
            val bogusCompleted = row.completed && (row.durationMs < 60_000 || row.positionMs * 100 < row.durationMs * 95)
            if (row.source == ProgressEntity.SOURCE_REMOTE && (bogusDuration || bogusCompleted)) {
                repaired++
                row.copy(completed = false, durationMs = 0)
            } else {
                row
            }
        }
        return repaired
    }

    override suspend fun insertIfAbsent(progress: ProgressEntity) {
        if (progress.key !in progressRows.value) upsertProgress(progress)
    }

    override suspend fun deleteProgress(key: String) {
        progressRows.value = progressRows.value - key
    }

    override suspend fun hideFromContinue(postId: Long) {
        snapshots.value[postId]?.let { upsertSnapshot(it.copy(hiddenFromContinue = true)) }
    }

    override suspend fun progress(key: String): ProgressEntity? = progressRows.value[key]

    override fun observeProgressForTitle(postId: Long): Flow<List<ProgressEntity>> =
        progressRows.map { rows -> rows.values.filter { it.postId == postId }.sortedWith(compareBy({ it.season }, { it.episode })) }

    override fun observeContinueWatching(limit: Int): Flow<List<ContinueWatchingRow>> =
        MutableStateFlow(continueRows.take(limit))
}

internal class FakePrefsDao : PrefsDao {
    private val rows = MutableStateFlow<Map<Long, PrefsEntity>>(emptyMap())

    override fun observe(postId: Long): Flow<PrefsEntity?> = rows.map { it[postId] }
    override suspend fun get(postId: Long): PrefsEntity? = rows.value[postId]
    override suspend fun upsert(prefs: PrefsEntity) {
        rows.value = rows.value + (prefs.postId to prefs)
    }

    private val style = MutableStateFlow<SubtitleStyleEntity?>(null)
    val offsets = MutableStateFlow<Map<Long, Int>>(emptyMap())

    override fun observeSubtitleStyle(): Flow<SubtitleStyleEntity?> = style
    override suspend fun upsertSubtitleStyle(style: SubtitleStyleEntity) {
        this.style.value = style
    }

    override suspend fun subtitleOffsetMs(postId: Long): Int? = offsets.value[postId]
    override suspend fun upsertSubtitleOffset(offset: SubtitleOffsetEntity) {
        offsets.value = offsets.value + (offset.postId to offset.offsetMs)
    }

    override suspend fun deleteSubtitleOffset(postId: Long) {
        offsets.value = offsets.value - postId
    }

    /** For tests that plant raw rows (e.g. unknown enum names). */
    fun plantStyle(entity: SubtitleStyleEntity) {
        style.value = entity
    }
}
