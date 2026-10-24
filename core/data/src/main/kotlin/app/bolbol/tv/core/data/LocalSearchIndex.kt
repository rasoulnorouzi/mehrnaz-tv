package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.CatalogRemoteDataSource
import app.bolbol.tv.core.network.SearchIndexFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device copy of the website's own search index (v1.5, docs/api.md "Search index").
 *
 * Why: the API's `post/search` misses new titles by their Latin name (their `normalized_title` is empty), so a
 * series added after a release (e.g. "Carrie 2026") could not be found. The site rebuilds `search-data.json` every
 * day with all ~38k titles, so the app refreshes its copy by itself: no code change or app update for new titles.
 *
 * - [refreshIfStale] downloads at most every [maxAgeMs], conditionally (`If-Modified-Since` → 304 costs nothing,
 *   a change costs ~4 MB gzipped), streams it into a compact tab-separated file (one title per line, a normalised
 *   search key first) and swaps it in atomically. Nothing big is ever held in memory (2 GB TV).
 * - [search] scans that file line by line: every query word must appear in the key (title + Persian title + year);
 *   whole-key prefix first, then word-start matches, then the rest; newest post first inside each group.
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
        val hits = ArrayList<Hit>()
        var lineNo = 0
        try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (++lineNo % 2000 == 0) ensureActive()
                    val keyEnd = line.indexOf('\t')
                    if (keyEnd <= 0) continue
                    val key = line.substring(0, keyEnd)
                    if (tokens.any { !key.contains(it) }) continue
                    val rank = when {
                        key.startsWith(whole) -> 0
                        key.startsWith(tokens[0]) || key.contains(" " + tokens[0]) -> 1
                        else -> 2
                    }
                    hits += Hit(rank, line)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        hits.asSequence()
            .mapNotNull { hit -> decode(hit.line)?.let { hit.rank to it } }
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

    private data class Hit(val rank: Int, val line: String)

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
        const val FILE_NAME = "search-index-v1.tsv"
        const val META_NAME = "search-index-v1.meta"
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val MAX_RESULTS = 240

        /** key, id, type (m/s/-), title, faTitle, year, imdb, poster, dub (0/1), sub (0/1). */
        fun encode(t: TitleSummary): String {
            val key = normalizeForIndex(listOf(t.title, t.faTitle, t.year?.toString().orEmpty()).joinToString(" "))
            val type = when (t.type) { TitleType.MOVIE -> "m"; TitleType.SERIES -> "s"; null -> "-" }
            return listOf(
                key, t.id.toString(), type, t.title, t.faTitle, t.year?.toString().orEmpty(),
                t.imdbRating?.toString().orEmpty(), t.posterUrl.orEmpty(), if (t.hasDub) "1" else "0",
                if (t.hasSubtitle) "1" else "0",
            ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
        }

        fun decode(line: String): TitleSummary? {
            val f = line.split('\t')
            if (f.size < 10) return null
            val id = f[1].toLongOrNull() ?: return null
            return TitleSummary(
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
        }
    }
}
