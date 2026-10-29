package app.bolbol.tv.core.data

import app.bolbol.tv.core.data.LocalSearchIndex.Companion.matches
import app.bolbol.tv.core.model.CatalogFilter
import app.bolbol.tv.core.model.CatalogSort
import app.bolbol.tv.core.model.LocalHomeRow
import app.bolbol.tv.core.network.IndexedTitle
import java.util.PriorityQueue

/**
 * v1.6 Home rows computed on the TV from the site's daily index (no extra requests, always current):
 * «پیشنهاد برای شما» (like what was watched), «تازه‌های این هفته», «تازه‌های دوبله فارسی» and a top-rated genre that
 * changes every day. Two passes over the index file at most, with bounded top-N lists (2 GB TV).
 */
internal object LocalHomeRows {

    const val ROW_SIZE = 20

    /** Genres the daily «بهترین‌های …» row rotates through (site names after `normalizeGenre`). */
    val ROTATING_GENRES = listOf(
        "درام", "کمدی", "اکشن", "هیجان‌انگیز", "عاشقانه", "جنایی", "ماجراجویی", "فانتزی",
        "رازآلود", "انیمیشن", "ترسناک", "علمی‌تخیلی", "خانوادگی", "تاریخی",
    )

    private const val MIN_RECOMMENDED_IMDB = 6.5f
    private const val MIN_TOP_IMDB = 7.5f
    private const val TOP_MIN_YEAR = 2000

    suspend fun build(index: LocalSearchIndex, watchedIds: List<Long>, nowMs: Long): List<LocalHomeRow> {
        val nowSec = nowMs / 1000
        val day = (nowMs / 86_400_000L).toInt()
        val topGenre = ROTATING_GENRES[Math.floorMod(day, ROTATING_GENRES.size)]

        val weekFilter = CatalogFilter(addedWithinDays = 7, sort = CatalogSort.NEWEST_ADDED)
        val dubFilter = CatalogFilter(dubbedOnly = true, sort = CatalogSort.NEWEST_ADDED)
        val topFilter = CatalogFilter(genre = topGenre, minImdb = MIN_TOP_IMDB, sort = CatalogSort.TOP_IMDB)

        val week = TopN<IndexedTitle>(ROW_SIZE, newest)
        val dubbed = TopN<IndexedTitle>(ROW_SIZE, newest)
        val top = TopN<IndexedTitle>(ROW_SIZE, bestImdb)
        val wanted = watchedIds.take(MAX_SEEDS).toSet()
        val seeds = HashMap<Long, IndexedTitle>()

        val ok = index.entries { e ->
            if (weekFilter.matches(e, nowSec)) week.offer(e)
            if (dubFilter.matches(e, nowSec)) dubbed.offer(e)
            if (topFilter.matches(e, nowSec) && (e.summary.year ?: 0) >= TOP_MIN_YEAR) top.offer(e)
            if (e.summary.id in wanted) seeds[e.summary.id] = e
        }
        if (!ok) return emptyList()

        val rows = ArrayList<LocalHomeRow>(4)
        recommendations(index, watchedIds.mapNotNull { seeds[it] }, watchedIds.toSet())?.let(rows::add)
        week.sorted().takeIf { it.isNotEmpty() }?.let {
            rows += LocalHomeRow("local-week", "تازه‌های این هفته", it.map(IndexedTitle::summary), weekFilter)
        }
        dubbed.sorted().takeIf { it.isNotEmpty() }?.let {
            rows += LocalHomeRow("local-dubbed", "تازه‌های دوبله فارسی", it.map(IndexedTitle::summary), dubFilter)
        }
        top.sorted().takeIf { it.isNotEmpty() }?.let {
            rows += LocalHomeRow("local-top", "بهترین‌های $topGenre", it.map(IndexedTitle::summary), topFilter.copy(minImdb = 7f))
        }
        return rows
    }

    /** «پیشنهاد برای شما»: shared genres (2 each), same country (1), same kind (1); IMDb ≥ 6.5; nothing already watched. */
    private suspend fun recommendations(index: LocalSearchIndex, seeds: List<IndexedTitle>, exclude: Set<Long>): LocalHomeRow? {
        if (seeds.isEmpty()) return null
        val scored = TopN<Pair<Int, IndexedTitle>>(ROW_SIZE, compareBy<Pair<Int, IndexedTitle>> { it.first }
            .thenBy { it.second.summary.imdbRating ?: 0f }.thenBy { it.second.addedAtEpochSec })
        val seedGenres = seeds.map { it.genres.toSet() }
        val ok = index.entries { e ->
            val s = e.summary
            if (s.id in exclude || (s.imdbRating ?: 0f) < MIN_RECOMMENDED_IMDB || e.genres.isEmpty()) return@entries
            var score = 0
            seeds.forEachIndexed { i, seed ->
                val shared = e.genres.count { it in seedGenres[i] }
                if (shared == 0) return@forEachIndexed
                score += shared * 2
                if (e.countries.any { it in seed.countries }) score += 1
                if (s.type == seed.summary.type) score += 1
            }
            if (score >= MIN_SCORE) scored.offer(score to e)
        }
        if (!ok) return null
        val items = scored.sorted().map { it.second.summary }
        if (items.isEmpty()) return null
        val first = seeds.first()
        val name = first.summary.faTitle.ifBlank { first.summary.title }
        val title = if (seeds.size == 1) "چون «$name» را دیدید" else "پیشنهاد برای شما"
        val viewAll = first.genres.firstOrNull()?.let {
            CatalogFilter(type = first.summary.type, genre = it, minImdb = MIN_RECOMMENDED_IMDB, sort = CatalogSort.TOP_IMDB)
        }
        return LocalHomeRow("local-for-you", title, items, viewAll)
    }

    private const val MAX_SEEDS = 5
    private const val MIN_SCORE = 4

    private val newest = compareBy<IndexedTitle> { it.addedAtEpochSec }.thenBy { it.summary.id }
    private val bestImdb = compareBy<IndexedTitle> { it.summary.imdbRating ?: 0f }.thenBy { it.summary.year ?: 0 }

    /** Keeps the [size] greatest items by [order]; [sorted] returns them greatest first. */
    private class TopN<T>(private val size: Int, private val order: Comparator<in T>) {
        private val heap = PriorityQueue<T>(size + 1, order)
        fun offer(item: T) {
            heap.add(item)
            if (heap.size > size) heap.poll()
        }
        fun sorted(): List<T> = heap.sortedWith(order.reversed())
    }

}
