package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.CatalogFacets
import app.bolbol.tv.core.model.CatalogFilter
import app.bolbol.tv.core.model.CatalogSort
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.CatalogRemoteDataSource
import app.bolbol.tv.core.network.IndexedTitle
import app.bolbol.tv.core.network.SearchIndexFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device copy of the website's own daily index of every title (v1.5 search, v1.6 categories; docs/api.md
 * "Search index"). The app refreshes it by itself, so new titles, filters and recommendations need no app update.
 *
 * - [refreshIfStale] downloads at most every [maxAgeMs], conditionally (304 when unchanged, ~4 MB gzipped otherwise),
 *   streams it into a compact tab-separated file (one title per line, normalised search key first) and swaps it in
 *   atomically. Nothing big is held in memory (2 GB TV).
 * - [search] matches every query word against title + Persian title + year.
 * - [browse] / [facets] / [entries] scan the same file for the «فیلم» / «سریال» filters and the Home rows.
 */
internal class LocalSearchIndex(
    private val dir: File,
    private val remote: CatalogRemoteDataSource,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val file get() = File(dir, FILE_NAME)
    private val metaFile get() = File(dir, META_NAME)
    private val refreshLock = Mutex()

    /** Changes whenever a new copy is swapped in (lets callers drop their caches). */
    @Volatile var version: Long = 0L
        private set

    /** True once a downloaded index exists on disk. */
    fun isReady(): Boolean = file.isFile && file.length() > 0

    /** Downloads a new copy when ours is older than [maxAgeMs] (or [force]). Never throws; false on failure. */
    suspend fun refreshIfStale(force: Boolean = false): Boolean = withContext(io) {
        refreshLock.withLock {
            val meta = readMeta()
            val fresh = meta != null && isReady() && clock() - meta.fetchedAt in 0 until maxAgeMs
            if (fresh && !force) return@withLock true
            try {
                val tmp = File(dir, "$FILE_NAME.tmp")
                dir.mkdirs()
                File(dir, LEGACY_FILE_NAME).delete()
                File(dir, LEGACY_META_NAME).delete()
                val since = meta?.lastModified?.takeIf { isReady() }
                val result = tmp.bufferedWriter(Charsets.UTF_8).use { out ->
                    remote.fetchSearchIndex(since) { title -> out.write(encode(title)); out.write('\n'.code) }
                }
                when (result) {
                    SearchIndexFetch.NotModified -> {
                        tmp.delete()
                        writeMeta(Meta(meta?.lastModified, clock()))
                    }
                    is SearchIndexFetch.Updated -> {
                        if (result.count == 0) {
                            tmp.delete() // an empty answer must not wipe a good index
                            return@withLock false
                        }
                        if (!tmp.renameTo(file)) {
                            file.delete()
                            if (!tmp.renameTo(file)) return@withLock false
                        }
                        version = clock()
                        writeMeta(Meta(result.lastModified, clock()))
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                File(dir, "$FILE_NAME.tmp").delete()
                false
            }
        }
    }

    /** All titles matching [query], best first, at most [limit]; null when there is no index yet. */
    suspend fun search(query: String, limit: Int = MAX_RESULTS): List<TitleSummary>? = withContext(io) {
        if (!isReady()) return@withContext null
        val tokens = normalizeForIndex(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return@withContext emptyList()
        val whole = tokens.joinToString(" ")
        val hits = ArrayList<Pair<Int, String>>()
        val ok = scanLines { line ->
            val keyEnd = line.indexOf('\t')
            if (keyEnd <= 0) return@scanLines
            val key = line.substring(0, keyEnd)
            if (tokens.any { !key.contains(it) }) return@scanLines
            val rank = when {
                key.startsWith(whole) -> 0
                key.startsWith(tokens[0]) || key.contains(" " + tokens[0]) -> 1
                else -> 2
            }
            hits += rank to line
        }
        if (!ok) return@withContext null
        hits.asSequence()
            .mapNotNull { (rank, line) -> decode(line)?.let { rank to it.summary } }
            .sortedWith(compareBy<Pair<Int, TitleSummary>> { it.first }.thenByDescending { it.second.id })
            .map { it.second }
            .take(limit)
            .toList()
    }

    /** Display titles completing [prefix] (Persian titles for a Persian query, else Latin), best first. */
    suspend fun suggest(prefix: String, limit: Int): List<String>? {
        val results = search(prefix, limit * 3) ?: return null
        val persian = prefix.any { it in '؀'..'ۿ' }
        return results.asSequence()
            .map { if (persian && it.faTitle.isNotBlank()) it.faTitle else it.title }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
            .toList()
    }

    /** Result of [browse]: the first [items] (at most the limit) and how many titles match in all. */
    data class BrowseResult(val items: List<TitleSummary>, val total: Int)

    /** Titles matching [filter] in its sort order (at most [limit] kept) + the full count; null without an index. */
    suspend fun browse(filter: CatalogFilter, limit: Int = MAX_BROWSE): BrowseResult? = withContext(io) {
        val now = clock() / 1000
        val matches = ArrayList<IndexedTitle>()
        val ok = scanEntries { if (filter.matches(it, now)) matches += it }
        if (!ok) return@withContext null
        BrowseResult(sortEntries(matches, filter.sort).take(limit).map { it.summary }, matches.size)
    }

    /** Genre / country / decade choices for [type], most common first; null when there is no index yet. */
    suspend fun facets(type: TitleType): CatalogFacets? = withContext(io) {
        val genres = HashMap<String, Int>()
        val countries = HashMap<String, Int>()
        val decades = HashSet<Int>()
        var dubbed = 0
        var total = 0
        val ok = scanEntries { e ->
            if (e.summary.type != type) return@scanEntries
            total++
            if (e.summary.hasDub) dubbed++
            e.genres.forEach { genres[it] = (genres[it] ?: 0) + 1 }
            e.countries.forEach { countries[it] = (countries[it] ?: 0) + 1 }
            decadeOf(e.summary.year)?.let(decades::add)
        }
        if (!ok) return@withContext null
        CatalogFacets(
            genres = genres.entries.filter { it.value >= MIN_FACET_COUNT }.sortedByDescending { it.value }.map { it.key },
            countries = countries.entries.filter { it.value >= MIN_FACET_COUNT }.sortedByDescending { it.value }
                .map { it.key }.take(MAX_COUNTRIES),
            decades = decades.sortedDescending(),
            dubbedCount = dubbed,
            total = total,
        )
    }

    /** Every entry, for callers that compute several things in one pass (Home rows). Null without an index. */
    suspend fun entries(visit: (IndexedTitle) -> Unit): Boolean = withContext(io) { scanEntries(visit) }

    private suspend fun scanEntries(visit: (IndexedTitle) -> Unit): Boolean = scanLines { line -> decode(line)?.let(visit) }

    private suspend fun scanLines(visit: (String) -> Unit): Boolean {
        if (!isReady()) return false
        val context = currentCoroutineContext()
        return try {
            var n = 0
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (++n % 2000 == 0) context.ensureActive()
                    visit(line)
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private data class Meta(val lastModified: String?, val fetchedAt: Long)

    private fun readMeta(): Meta? = try {
        val lines = metaFile.takeIf { it.isFile }?.readLines() ?: return null
        Meta(lines.getOrNull(0)?.takeIf { it.isNotBlank() }, lines.getOrNull(1)?.toLongOrNull() ?: 0L)
    } catch (e: Exception) {
        null
    }

    private fun writeMeta(meta: Meta) {
        try {
            metaFile.writeText("${meta.lastModified.orEmpty()}\n${meta.fetchedAt}\n")
        } catch (_: Exception) {
            // best effort: the next start simply checks again
        }
    }

    internal companion object {
        /** v2 (v1.6) adds genres, countries and the date added; the v1 file is deleted and downloaded again. */
        const val FILE_NAME = "search-index-v2.tsv"
        const val META_NAME = "search-index-v2.meta"
        private const val LEGACY_FILE_NAME = "search-index-v1.tsv"
        private const val LEGACY_META_NAME = "search-index-v1.meta"
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val MAX_RESULTS = 240
        /** Browse keeps at most this many results per filter (100 pages of 24). */
        const val MAX_BROWSE = 2400
        private const val MIN_FACET_COUNT = 5
        private const val MAX_COUNTRIES = 40

        fun decadeOf(year: Int?): Int? = when {
            year == null || year < 1870 -> null
            year < 1980 -> CatalogFilter.OLDER
            else -> year / 10 * 10
        }

        fun CatalogFilter.matches(e: IndexedTitle, nowEpochSec: Long): Boolean {
            val s = e.summary
            if (type != null && s.type != type) return false
            if (dubbedOnly && !s.hasDub) return false
            if (genre != null && genre !in e.genres) return false
            if (country != null && country !in e.countries) return false
            if (decade != null && decadeOf(s.year) != decade) return false
            val min = minImdb
            if (min != null && (s.imdbRating ?: 0f) < min) return false
            val days = addedWithinDays
            if (days != null) {
                if (e.addedAtEpochSec <= 0 || nowEpochSec - e.addedAtEpochSec > days * 86_400L) return false
            }
            return true
        }

        fun sortEntries(list: List<IndexedTitle>, sort: CatalogSort): List<IndexedTitle> = when (sort) {
            // Date added, then post id (both grow with time on the site).
            CatalogSort.NEWEST_ADDED -> list.sortedWith(
                compareByDescending<IndexedTitle> { it.addedAtEpochSec }.thenByDescending { it.summary.id },
            )
            CatalogSort.TOP_IMDB -> list.sortedWith(
                compareByDescending<IndexedTitle> { it.summary.imdbRating ?: -1f }.thenByDescending { it.summary.year ?: 0 },
            )
            CatalogSort.NEWEST_YEAR -> list.sortedWith(
                compareByDescending<IndexedTitle> { it.summary.year ?: 0 }.thenByDescending { it.addedAtEpochSec },
            )
        }

        /** key, id, type, title, faTitle, year, imdb, poster, dub, sub, genres (|), countries (|), added (epoch s). */
        fun encode(t: IndexedTitle): String {
            val s = t.summary
            val key = normalizeForIndex(listOf(s.title, s.faTitle, s.year?.toString().orEmpty()).joinToString(" "))
            val type = when (s.type) { TitleType.MOVIE -> "m"; TitleType.SERIES -> "s"; null -> "-" }
            return listOf(
                key, s.id.toString(), type, s.title, s.faTitle, s.year?.toString().orEmpty(),
                s.imdbRating?.toString().orEmpty(), s.posterUrl.orEmpty(), if (s.hasDub) "1" else "0",
                if (s.hasSubtitle) "1" else "0", t.genres.joinToString("|") { it.replace("|", " ") },
                t.countries.joinToString("|") { it.replace("|", " ") }, t.addedAtEpochSec.toString(),
            ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
        }

        fun decode(line: String): IndexedTitle? {
            val f = line.split('\t')
            if (f.size < 13) return null
            val id = f[1].toLongOrNull() ?: return null
            val summary = TitleSummary(
                id = id,
                type = when (f[2]) { "m" -> TitleType.MOVIE; "s" -> TitleType.SERIES; else -> null },
                title = f[3],
                faTitle = f[4],
                posterUrl = f[7].ifEmpty { null },
                backdropUrl = null,
                year = f[5].toIntOrNull(),
                imdbRating = f[6].toFloatOrNull(),
                hasDub = f[8] == "1",
                hasSubtitle = f[9] == "1",
            )
            return IndexedTitle(
                summary = summary,
                genres = f[10].split('|').filter { it.isNotEmpty() },
                countries = f[11].split('|').filter { it.isNotEmpty() },
                addedAtEpochSec = f[12].toLongOrNull() ?: 0L,
            )
        }
    }
}
