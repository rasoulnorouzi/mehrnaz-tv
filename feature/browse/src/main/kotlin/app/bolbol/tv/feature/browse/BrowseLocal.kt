package app.bolbol.tv.feature.browse

import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.CatalogFacets
import app.bolbol.tv.core.model.CatalogFilter
import app.bolbol.tv.core.model.CatalogSort
import app.bolbol.tv.core.model.TitleType
import java.net.URLDecoder
import java.net.URLEncoder

// v1.6 «فیلم‌ها» / «سریال‌ها»: genre / country / decade / dubbed / sort filters answered from the on-device index.
// Pure helpers only (labels, option lists, filter edits) so they are unit-testable without Compose.

/** The site's page size, for the "حدود N عنوان" approximation (Page has no total count). */
internal const val LOCAL_PAGE_SIZE = 24

internal const val LABEL_ALL = "همه"
internal const val LABEL_OLDER = "قدیمی‌تر"
internal const val LABEL_DUBBED = "دوبله فارسی"
internal const val LABEL_CLEAR = "پاک کردن"
internal const val LABEL_CLEAR_FILTERS = "پاک کردن فیلترها"
internal const val MESSAGE_NO_MATCH = "چیزی با این فیلترها پیدا نشد"

/** The four pickers of the filter bar (the dubbed toggle needs none). */
internal enum class FilterKind(val title: String) {
    Genre("ژانر"),
    Country("کشور"),
    Decade("سال"),
    Sort("مرتب‌سازی"),
}

internal fun decadeLabel(decade: Int): String =
    if (decade == CatalogFilter.OLDER) LABEL_OLDER else "دههٔ ${decade.toPersianDigits()}"

internal fun sortLabel(sort: CatalogSort): String = when (sort) {
    CatalogSort.NEWEST_ADDED -> "تازه‌ترین‌ها"
    CatalogSort.TOP_IMDB -> "بهترین IMDb"
    CatalogSort.NEWEST_YEAR -> "جدیدترین سال"
}

/** One row of a picker. Exactly one of [text] / [number] / [sort] is set, or none for «همه». */
internal data class PickerOption(
    val label: String,
    val text: String? = null,
    val number: Int? = null,
    val sort: CatalogSort? = null,
)

/** Options of [kind]: «همه» first (not for sort), then the facets in their order. */
internal fun pickerOptions(kind: FilterKind, facets: CatalogFacets?): List<PickerOption> = when (kind) {
    FilterKind.Genre -> listOf(PickerOption(LABEL_ALL)) + facets?.genres.orEmpty().map { PickerOption(it, text = it) }
    FilterKind.Country -> listOf(PickerOption(LABEL_ALL)) + facets?.countries.orEmpty().map { PickerOption(it, text = it) }
    FilterKind.Decade -> listOf(PickerOption(LABEL_ALL)) + facets?.decades.orEmpty().map { PickerOption(decadeLabel(it), number = it) }
    FilterKind.Sort -> CatalogSort.entries.map { PickerOption(sortLabel(it), sort = it) }
}

/** Whether [option] is the current value of [kind] in this filter. */
internal fun CatalogFilter.isSelected(kind: FilterKind, option: PickerOption): Boolean = when (kind) {
    FilterKind.Genre -> option.text == genre
    FilterKind.Country -> option.text == country
    FilterKind.Decade -> option.number == decade
    FilterKind.Sort -> option.sort == sort
}

/** Index of the current value in [options] (the picker's initial focus); 0 when not found. */
internal fun CatalogFilter.selectedIndex(kind: FilterKind, options: List<PickerOption>): Int =
    options.indexOfFirst { isSelected(kind, it) }.coerceAtLeast(0)

/** This filter with [option] applied for [kind]. */
internal fun CatalogFilter.withOption(kind: FilterKind, option: PickerOption): CatalogFilter = when (kind) {
    FilterKind.Genre -> copy(genre = option.text)
    FilterKind.Country -> copy(country = option.text)
    FilterKind.Decade -> copy(decade = option.number)
    FilterKind.Sort -> copy(sort = option.sort ?: CatalogSort.NEWEST_ADDED)
}

/** Text of the bar button for [kind]: «ژانر: همه» / «کشور: کره جنوبی» / «سال: دههٔ ۲۰۲۰» / «مرتب‌سازی: تازه‌ترین‌ها». */
internal fun CatalogFilter.buttonLabel(kind: FilterKind): String {
    val value = when (kind) {
        FilterKind.Genre -> genre ?: LABEL_ALL
        FilterKind.Country -> country ?: LABEL_ALL
        FilterKind.Decade -> decade?.let(::decadeLabel) ?: LABEL_ALL
        FilterKind.Sort -> sortLabel(sort)
    }
    return "${kind.title}: $value"
}

/** True when the picker of [kind] holds a non-default value (the button shows as selected). */
internal fun CatalogFilter.isSet(kind: FilterKind): Boolean = when (kind) {
    FilterKind.Genre -> genre != null
    FilterKind.Country -> country != null
    FilterKind.Decade -> decade != null
    FilterKind.Sort -> sort != CatalogSort.NEWEST_ADDED
}

/** Everything reset, only the page's type kept. */
internal fun CatalogFilter.cleared(): CatalogFilter = CatalogFilter(type = type)

/** True when anything (including the sort) differs from the plain page: shows «پاک کردن». */
internal fun CatalogFilter.isActive(): Boolean = this != cleared()

/** Short summary of the active filters for the header, e.g. «کره جنوبی · دوبله فارسی»; empty when none. */
internal fun CatalogFilter.summary(): String = buildList {
    genre?.let(::add)
    country?.let(::add)
    decade?.let { add(decadeLabel(it)) }
    if (dubbedOnly) add(LABEL_DUBBED)
    minImdb?.let { add("IMDb ${it.toString().toPersianDigits()}+") }
    addedWithinDays?.let { add("${it.toPersianDigits()} روز اخیر") }
    if (sort != CatalogSort.NEWEST_ADDED) add(sortLabel(sort))
}.joinToString(" · ")

/** Result count line: exact when everything fits one page, else the page-count approximation. */
internal fun localCountLabel(phase: BrowsePhase, totalPages: Int, itemCount: Int): String = when {
    phase != BrowsePhase.Content -> ""
    totalPages > 1 -> "حدود ${(totalPages * LOCAL_PAGE_SIZE).toPersianDigits()} عنوان"
    else -> "${itemCount.toPersianDigits()} عنوان"
}

/** Header sub-line of the local mode: filter summary and count. */
internal fun localSubtitle(state: BrowseUiState): String {
    val f = state.filter ?: return ""
    return listOf(f.summary(), localCountLabel(state.phase, state.totalPages, state.items.size))
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}

// --- Route (de)serialisation of a CatalogFilter, so :app can carry Home's "view all" in one string field. ---

/** Compact string form for a nav route field; null when everything is default. Inverse of [catalogFilterFromRoute]. */
fun CatalogFilter.toRouteString(): String? {
    val parts = buildList {
        type?.let { add("t=${it.name}") }
        genre?.let { add("g=${enc(it)}") }
        country?.let { add("c=${enc(it)}") }
        if (dubbedOnly) add("d=1")
        decade?.let { add("y=$it") }
        minImdb?.let { add("i=$it") }
        addedWithinDays?.let { add("a=$it") }
        if (sort != CatalogSort.NEWEST_ADDED) add("s=${sort.name}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("&")
}

/** Parses [toRouteString]'s output; null for null / blank. Unknown keys are ignored. */
fun catalogFilterFromRoute(value: String?): CatalogFilter? {
    if (value.isNullOrBlank()) return null
    var f = CatalogFilter()
    for (part in value.split('&')) {
        val k = part.substringBefore('=')
        val v = part.substringAfter('=', "")
        f = when (k) {
            "t" -> f.copy(type = TitleType.entries.firstOrNull { it.name == v })
            "g" -> f.copy(genre = dec(v))
            "c" -> f.copy(country = dec(v))
            "d" -> f.copy(dubbedOnly = v == "1")
            "y" -> f.copy(decade = v.toIntOrNull())
            "i" -> f.copy(minImdb = v.toFloatOrNull())
            "a" -> f.copy(addedWithinDays = v.toIntOrNull())
            "s" -> f.copy(sort = CatalogSort.entries.firstOrNull { it.name == v } ?: CatalogSort.NEWEST_ADDED)
            else -> f
        }
    }
    return f
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")
