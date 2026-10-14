package app.bolbol.tv.feature.browse

import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Genre
import app.bolbol.tv.core.model.TitleSummary

internal enum class BrowsePhase { Loading, Content, Empty, Error }

/**
 * What [BrowseScreen] draws. The last focused poster is deliberately **not** in here: it changes
 * on every D-pad move and must not recompose the screen (see [BrowseViewModel.lastFocusedId]).
 */
internal data class BrowseUiState(
    val title: String,
    /** Chips; empty = no chip row. */
    val filters: List<BrowseFilter>,
    /** Selected quick chip; null while a [selectedGenre] is active (the two never stack). */
    val selected: BrowseFilter?,
    /** Genre chips (filled asynchronously, never blocks the grid); empty = none. */
    val genres: List<Genre> = emptyList(),
    val selectedGenre: Genre? = null,
    val phase: BrowsePhase,
    /** Unique by id, in server order. */
    val items: List<TitleSummary> = emptyList(),
    /** Last loaded page and the server's page count (0 until the first page arrives). */
    val page: Int = 0,
    val totalPages: Int = 0,
    /** Persian message for [BrowsePhase.Error] (first page failed). */
    val error: String? = null,
    val loadingMore: Boolean = false,
    /** Persian message when fetching a later page failed; the grid keeps its items and offers a retry. */
    val loadMoreError: String? = null,
)

/** Small line under the title: "صفحه ۲ از ۸۵۹", or the item count when everything fits one page. */
internal fun pageCountLabel(phase: BrowsePhase, page: Int, totalPages: Int, itemCount: Int): String = when {
    phase != BrowsePhase.Content -> ""
    totalPages > 1 -> "صفحه ${page.toPersianDigits()} از ${totalPages.toPersianDigits()}"
    else -> "${itemCount.toPersianDigits()} مورد"
}

internal const val MESSAGE_OFFLINE = "اتصال اینترنت برقرار نیست. شبکه را بررسی کنید و دوباره تلاش کنید."
internal const val MESSAGE_SERVER_DOWN = "ارتباط با سرور برقرار نشد. کمی بعد دوباره تلاش کنید."
internal const val MESSAGE_NOT_FOUND = "این فهرست پیدا نشد."
internal const val MESSAGE_BAD_RESPONSE = "پاسخ سرور قابل خواندن نبود. دوباره تلاش کنید."
internal const val MESSAGE_UNKNOWN = "مشکلی پیش آمد. دوباره تلاش کنید."

/** Friendly Persian text for a failed list request. */
internal fun Throwable.browseMessage(): String = when (this) {
    is CatalogException.Offline -> MESSAGE_OFFLINE
    is CatalogException.AllMirrorsDown -> MESSAGE_SERVER_DOWN
    is CatalogException.NotFound -> MESSAGE_NOT_FOUND
    is CatalogException.BadResponse -> MESSAGE_BAD_RESPONSE
    else -> MESSAGE_UNKNOWN
}
