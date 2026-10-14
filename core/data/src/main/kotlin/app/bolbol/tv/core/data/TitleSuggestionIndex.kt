package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Local prefix index of title names, for the search keyboard's prediction row (v1.4-5, docs/api.md section
 * "Search predictions"). The site's own `search-data.json` is ~28 MB, far too big for a 2 GB TV, so this index
 * is fed only with titles the app has already received (home rows, search and list pages): no extra requests.
 *
 * Matching is by word: a title is suggested when the (normalised) query is a prefix of the title or of any
 * suffix of it that starts at a word boundary, so "ناک" finds "جنگ ناکجا" and "dark" finds "The Dark Knight".
 * Normalisation folds Arabic yeh/kaf to Persian, drops diacritics and tatweel, maps ZWNJ and punctuation to a
 * space, Persian and Arabic-Indic digits to ASCII and Latin to lower case. Both the Latin and the Persian title
 * of an item are indexed. Ranking: whole-title prefix first, then shorter titles, then alphabetical. The sorted
 * array is rebuilt lazily, off the main thread, after new titles arrived.
 */
internal class TitleSuggestionIndex(
    private val dispatcher: CoroutineDispatcher,
    private val maxTitles: Int = MAX_TITLES,
) {
    private class Entry(val key: String, val wordStart: Int, val title: String)

    private val lock = Any()
    private val titles = LinkedHashSet<String>()
    private var entries: Array<Entry> = emptyArray()
    private var dirty = false

    fun add(items: List<TitleSummary>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            for (item in items) {
                addTitle(item.title)
                addTitle(item.faTitle)
            }
        }
    }

    fun addSections(sections: List<HomeSection>) {
        sections.forEach { add(it.items) }
    }

    fun size(): Int = synchronized(lock) { titles.size }

    /** Display titles that complete [prefix], best first, at most [limit]. Runs on the injected dispatcher. */
    suspend fun suggest(prefix: String, limit: Int): List<String> = withContext(dispatcher) {
        var query = normalizeForIndex(prefix)
        if (query.isEmpty() || limit <= 0) return@withContext emptyList()
        // "dark " (trailing space) means: the next word is starting.
        if (prefix.last().isWhitespace() || prefix.last().code == ZWNJ) query += ' '
        val snapshot = snapshot()
        var i = lowerBound(snapshot, query)
        val matches = ArrayList<Entry>()
        while (i < snapshot.size && snapshot[i].key.startsWith(query)) {
            matches += snapshot[i]
            i++
        }
        val exact = query.trim()
        matches
            .sortedWith(compareBy<Entry> { it.wordStart != 0 }.thenBy { it.title.length }.thenBy { it.title })
            .map { it.title }
            .distinct()
            .filterNot { normalizeForIndex(it) == exact } // a completion must add something
            .take(limit)
    }

    private fun addTitle(raw: String) {
        val title = raw.trim()
        if (title.isEmpty() || title in titles) return
        if (titles.size >= maxTitles) titles.remove(titles.first())
        titles += title
        dirty = true
    }

    private fun snapshot(): Array<Entry> = synchronized(lock) {
        if (dirty) {
            val list = ArrayList<Entry>(titles.size * 3)
            for (title in titles) {
                val norm = normalizeForIndex(title)
                var start = 0
                while (start < norm.length) {
                    list += Entry(norm.substring(start), start, title)
                    val next = norm.indexOf(' ', start)
                    if (next < 0) break
                    start = next + 1
                }
            }
            list.sortBy { it.key }
            entries = list.toTypedArray()
            dirty = false
        }
        entries
    }

    private fun lowerBound(sorted: Array<Entry>, query: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].key < query) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val MAX_TITLES = 4000
        private const val ZWNJ = 0x200C
    }
}

/** See [TitleSuggestionIndex]: the key titles and queries are compared by. */
internal fun normalizeForIndex(raw: String): String {
    val sb = StringBuilder(raw.length)
    var pendingSpace = false
    for (c in raw) {
        val code = c.code
        val mapped: Char? = when {
            c.isWhitespace() || code == 0x200C || c == '-' || c == ':' || c == '.' || c == '\'' || c == ',' ||
                code == 0x060C || code == 0x061F || code == 0x061B -> ' '
            code == 0x0640 || code in 0x064B..0x065F || code == 0x0670 -> null
            code == 0x200B || code == 0x200D || code == 0x200E || code == 0x200F || code == 0xFEFF -> null
            code == 0x064A || code == 0x0649 -> 0x06CC.toChar()
            code == 0x0643 -> 0x06A9.toChar()
            code in 0x06F0..0x06F9 -> '0' + (code - 0x06F0)
            code in 0x0660..0x0669 -> '0' + (code - 0x0660)
            else -> c.lowercaseChar()
        }
        if (mapped == null) continue
        if (mapped == ' ') {
            pendingSpace = sb.isNotEmpty()
            continue
        }
        if (pendingSpace) {
            sb.append(' ')
            pendingSpace = false
        }
        sb.append(mapped)
    }
    return sb.toString()
}
