package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.Genre

/**
 * Genre ids of the movie and series lists, researched 2026-10-14 (docs/api.md section 5.3).
 *
 * The API has no genre endpoint (`wp/v2` exposes only categories and tags), so this is a static table:
 * the ids come from the `term-N` body class of the site's `/genre/<slug>/` (movies) and `/seriegenre/<slug>/`
 * (series) archive pages, and were spot-checked against `GET /post/{movies|series}?genres=ID`. Ids differ between
 * the two kinds (quirk 13): never share one. Ordered most useful first.
 */
object GenreTable {

    private val movies = listOf(
        Genre(29, "اکشن"),
        Genre(13, "درام"),
        Genre(379, "کمدی"),
        Genre(21, "جنایی"),
        Genre(468, "هیجان‌انگیز"),
        Genre(117202, "ترسناک"),
        Genre(520, "علمی‌تخیلی"),
        Genre(735, "انیمیشن"),
        Genre(117181, "عاشقانه"),
        Genre(56, "ماجراجویی"),
        Genre(772, "خانوادگی"),
        Genre(129, "فانتزی"),
        Genre(117203, "رازآلود"),
        Genre(117204, "مستند"),
        Genre(47, "تاریخی"),
        Genre(117205, "جنگی"),
        Genre(46, "بیوگرافی"),
        Genre(1044, "موزیکال"),
        Genre(1138, "ورزشی"),
        Genre(699, "وسترن"),
        Genre(1504, "فیلم نوآر"),
        Genre(979, "کوتاه"),
        Genre(376, "آهنگ"),
    )

    private val series = listOf(
        Genre(108, "اکشن"),
        Genre(74, "درام"),
        Genre(94526, "کمدی"),
        Genre(73, "جنایی"),
        Genre(75, "هیجان‌انگیز"),
        Genre(169, "ترسناک"),
        Genre(110, "علمی‌تخیلی"),
        Genre(94519, "انیمیشن"),
        Genre(187, "عاشقانه"),
        Genre(109, "ماجراجویی"),
        Genre(94527, "خانوادگی"),
        Genre(178, "فانتزی"),
        Genre(117, "رازآلود"),
        Genre(86, "مستند"),
        Genre(93, "تاریخی"),
        Genre(94, "جنگی"),
        Genre(289, "بیوگرافی"),
    )

    /** Genres of [category]'s own taxonomy; empty for kinds without a researched list. */
    fun forCategory(category: Category): List<Genre> = when (category) {
        Category.MOVIES -> movies
        Category.SERIES -> series
        else -> emptyList()
    }
}
