package app.bolbol.tv.feature.search

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.TitleSummary

/** Pure search logic (unit-tested): recent searches, pagination merge, error mapping. */

/** Most recent searches kept and shown as chips. */
internal const val MAX_RECENTS = 8

/**
 * New recent-searches list after searching [query]: the query goes first, an existing entry with
 * the same normalised text is removed (case-insensitive for Latin), at most [MAX_RECENTS] remain.
 * Queries that are too short are ignored. Input entries are assumed already normalised.
 */
internal fun List<String>.withRecent(query: String): List<String> {
    val normalized = normalizeQuery(query)
    if (!isSearchable(normalized)) return this
    val rest = filterNot { it.equals(normalized, ignoreCase = true) }
    return (listOf(normalized) + rest).take(MAX_RECENTS)
}

/** Storage format of the recents list (one query per line; queries never contain line breaks). */
internal fun encodeRecents(recents: List<String>): String = recents.joinToString("\n")

internal fun decodeRecents(stored: String?): List<String> =
    stored.orEmpty()
        .split('\n')
        .map(::normalizeQuery)
        .filter(::isSearchable)
        .distinctBy { it.lowercase() }
        .take(MAX_RECENTS)

/**
 * Appends [incoming] to [existing], dropping titles whose id is already present (the server may
 * repeat an item when the list shifts between pages). Order is kept; returns [existing] itself
 * when nothing new arrived.
 */
internal fun mergePages(existing: List<TitleSummary>, incoming: List<TitleSummary>): List<TitleSummary> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<Long>(existing.size + incoming.size)
    existing.forEach { seen.add(it.id) }
    val fresh = incoming.filter { seen.add(it.id) }
    return if (fresh.isEmpty()) existing else existing + fresh
}

/** True when the item at [focusedIndex] is in the last row of a [columns]-wide grid of [total] items. */
internal fun isInLastRow(focusedIndex: Int, total: Int, columns: Int): Boolean {
    if (total <= 0 || columns <= 0 || focusedIndex < 0) return false
    val lastRowStart = ((total + columns - 1) / columns - 1) * columns
    return focusedIndex >= lastRowStart
}

internal object SearchStrings {
    const val Title = "جستجو"
    const val QueryPlaceholder = "نام فیلم یا سریال"
    const val Hint = "حداقل دو حرف از نام فیلم یا سریال را وارد کنید"
    const val HintVoice = "یا با میکروفون و گوشی جستجو کنید"
    const val Recents = "جستجوهای اخیر"
    const val NoResults = "نتیجه‌ای پیدا نشد"
    const val Searching = "در حال جستجو…"
    const val LoadingMore = "در حال بارگذاری…"
    const val Voice = "جستجوی صوتی"
    const val VoicePrompt = "نام فیلم یا سریال را بگویید"
    const val Phone = "تایپ با گوشی"
    const val PhoneHint = "کد را با دوربین گوشی اسکن کنید و جستجو را از روی گوشی تایپ کنید"
    const val Close = "بستن"
}

/** What went wrong, in terms the UI can word in Persian. */
internal enum class SearchError(val message: String) {
    Offline("اتصال به اینترنت برقرار نیست. اتصال تلویزیون را بررسی کنید و دوباره تلاش کنید."),
    AllMirrorsDown("سرور در دسترس نیست. کمی بعد دوباره تلاش کنید."),
    BadResponse("پاسخ سرور نامعتبر بود. دوباره تلاش کنید."),
    Unknown("جستجو انجام نشد. دوباره تلاش کنید."),
}

internal fun Throwable.toSearchError(): SearchError = when (this) {
    is CatalogException.Offline -> SearchError.Offline
    is CatalogException.AllMirrorsDown -> SearchError.AllMirrorsDown
    is CatalogException.BadResponse -> SearchError.BadResponse
    else -> SearchError.Unknown
}
