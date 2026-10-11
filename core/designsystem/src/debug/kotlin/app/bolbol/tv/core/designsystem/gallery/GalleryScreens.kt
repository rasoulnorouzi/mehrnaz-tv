package app.bolbol.tv.core.designsystem.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.AudioBadge
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.ChipGroup
import app.bolbol.tv.core.designsystem.ContentRow
import app.bolbol.tv.core.designsystem.EmptyState
import app.bolbol.tv.core.designsystem.ErrorState
import app.bolbol.tv.core.designsystem.HeroBackdrop
import app.bolbol.tv.core.designsystem.LoadingState
import app.bolbol.tv.core.designsystem.MetaLine
import app.bolbol.tv.core.designsystem.PosterCard
import app.bolbol.tv.core.designsystem.ProgressLine
import app.bolbol.tv.core.designsystem.RatingBadge
import app.bolbol.tv.core.designsystem.TopBar
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.designsystem.WideCard
import app.bolbol.tv.core.designsystem.formatDurationFa
import app.bolbol.tv.core.designsystem.formatEpisodeFa
import app.bolbol.tv.core.designsystem.formatRemainingFa
import app.bolbol.tv.core.designsystem.formatRuntimeFa
import app.bolbol.tv.core.designsystem.rememberDebouncedValue

private const val POSTER_1 = "https://seo2024.ir/wp-content/uploads/2026/07/0-39075900-1783156910.webp"
private const val POSTER_2 = "https://euploadi.com/wp-content/uploads/2026/02/0-76008300-1772116872.webp"
private const val POSTER_3 = "https://euploadi.com/wp-content/uploads/2026/10/0-74308300-1791649121.webp"
private const val BACKDROP = "https://euploadi.com/wp-content/uploads/2026/02/0-84730100-1772116872.webp"

private data class GalleryItem(
    val id: Long,
    val title: String,
    val year: String,
    val poster: String,
    val backdrop: String,
    val imdb: Float? = null,
    val dubbed: Boolean = false,
    val subtitled: Boolean = false,
    val progress: Float? = null,
)

// Mixed Persian / Latin titles on purpose: the Latin ones end in ")" and "!" to exercise bidi.
private val GalleryItems = listOf(
    GalleryItem(1, "اسلحه برهنه", "2025", POSTER_1, BACKDROP, imdb = 6.4f, dubbed = true, subtitled = true, progress = 0.35f),
    GalleryItem(2, "The Naked Gun 2025", "2025", POSTER_2, POSTER_2, imdb = 7.1f, subtitled = true),
    GalleryItem(3, "وان پیس لگویی", "2025", POSTER_3, POSTER_3, imdb = 8.3f, dubbed = true),
    GalleryItem(4, "سریال LEGO ONE PIECE", "2025", POSTER_1, BACKDROP, subtitled = true, progress = 0.8f),
    GalleryItem(5, "Dune: Part Two (2024)", "2024", POSTER_2, POSTER_2, imdb = 8.5f, dubbed = true, subtitled = true),
    GalleryItem(6, "Mission: Impossible!", "2023", POSTER_3, POSTER_3, imdb = 7.7f, subtitled = true, progress = 0.12f),
    GalleryItem(7, "فیلم ۷", "2022", POSTER_1, BACKDROP, dubbed = true),
    GalleryItem(8, "Masters of the Universe", "2026", POSTER_2, POSTER_2, imdb = 6.9f),
    GalleryItem(9, "کارتون آزمایشی", "2021", POSTER_3, POSTER_3, imdb = 7.2f, dubbed = true, progress = 0.55f),
)

private val Tabs = listOf("جستجو", "خانه", "فیلم", "سریال", "کارتون", "لیست من", "تنظیمات")

@Composable
fun GalleryRoot(page: Int) {
    Box(Modifier.fillMaxSize().background(BolbolColors.Background)) {
        when (page) {
            1 -> CardsAndControlsPage()
            2 -> StatesPage()
            else -> HomePage()
        }
    }
}

/** Page 0: what the home screen is made of. */
@Composable
private fun HomePage() {
    var focusedIndex by remember { mutableIntStateOf(0) }
    val focused = GalleryItems[focusedIndex]
    // Focus-driven hero: debounced 300 ms, then a single 250 ms crossfade.
    val backdrop = rememberDebouncedValue(focused.backdrop)
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstCard.requestFocus() }

    Column(Modifier.fillMaxSize().padding(top = BolbolSpacing.SafeVertical)) {
        TopBar(items = Tabs, selectedIndex = 1, onSelect = {})
        HeroBackdrop(backdrop, Modifier.fillMaxWidth().height(150.dp)) {
            Column(
                Modifier.align(Alignment.CenterStart).padding(horizontal = BolbolSpacing.SafeHorizontal),
            ) {
                BolbolText(
                    text = focused.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = BolbolColors.TextPrimary,
                    maxLines = 1,
                )
                MetaLine(
                    parts = listOf(
                        focused.year,
                        formatRuntimeFa(102),
                        focused.imdb?.let { "IMDb $it" } ?: "",
                        "اکشن",
                    ),
                )
            }
        }
        ContentRow(
            title = "پیشنهاد برای شما",
            items = GalleryItems,
            key = { it.id },
            contentType = { "poster" },
        ) { item ->
            PosterCard(
                title = item.title,
                imageUrl = item.poster,
                onClick = {},
                modifier = if (item.id == 1L) Modifier.focusRequester(firstCard) else Modifier,
                year = item.year,
                imdb = item.imdb,
                dubbed = item.dubbed,
                subtitled = item.subtitled,
                progress = item.progress,
                onFocused = { focusedIndex = GalleryItems.indexOf(item) },
            )
        }
    }
}

/** Page 1: continue-watching row, buttons, chips, badges, progress, formatters. */
@Composable
private fun CardsAndControlsPage() {
    var audio by remember { mutableIntStateOf(0) }
    var quality by remember { mutableIntStateOf(1) }
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstCard.requestFocus() }
    val wide = remember { GalleryItems.take(6) }

    Column(
        Modifier.fillMaxSize().padding(top = BolbolSpacing.SafeVertical),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContentRow(
            title = "ادامه تماشا",
            items = wide,
            key = { it.id },
            contentType = { "wide" },
        ) { item ->
            val remainingMs = ((1f - (item.progress ?: 0.3f)) * 100 * 60_000L).toLong()
            WideCard(
                title = item.title,
                imageUrl = if (item.id % 2L == 0L) BACKDROP else item.backdrop,
                onClick = {},
                modifier = if (item.id == 1L) Modifier.focusRequester(firstCard) else Modifier,
                subtitle = formatEpisodeFa(1, item.id.toInt(), includeSeason = false) + " · " + formatRemainingFa(remainingMs),
                progress = item.progress ?: 0.3f,
            )
        }

        Row(
            Modifier.padding(horizontal = BolbolSpacing.SafeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton("ادامه از ${formatDurationFa(47 * 60_000L + 12_000L)}", onClick = {}, icon = BolbolIcons.Play)
            TvButton("از ابتدا", onClick = {}, icon = BolbolIcons.Replay, style = TvButtonStyle.Secondary)
            TvButton("لیست من", onClick = {}, icon = BolbolIcons.Add, style = TvButtonStyle.Secondary)
            TvButton("جستجو", onClick = {}, icon = BolbolIcons.Search, style = TvButtonStyle.Secondary)
            TvButton("غیرفعال", onClick = {}, style = TvButtonStyle.Secondary, enabled = false)
        }

        Row(
            Modifier.padding(horizontal = BolbolSpacing.SafeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipGroup(listOf("دوبله", "زیرنویس"), selectedIndex = audio, onSelect = { audio = it })
            ChipGroup(listOf("1080p", "720p", "480p"), selectedIndex = quality, onSelect = { quality = it })
        }

        Row(
            Modifier.padding(horizontal = BolbolSpacing.SafeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AudioBadge(dubbed = true, subtitled = true)
            AudioBadge(dubbed = true, subtitled = false)
            AudioBadge(dubbed = false, subtitled = true)
            RatingBadge(8.1f)
            ProgressLine(0.4f, Modifier.width(120.dp))
            MetaLine(listOf("2025", formatRuntimeFa(102), "PG-13", "اکشن · ماجراجویی"))
        }

        Column(Modifier.padding(horizontal = BolbolSpacing.SafeHorizontal)) {
            MetaLine(
                parts = listOf(
                    "formatDurationFa(1:23:45) = " + formatDurationFa(5025_000L),
                    "formatRemainingFa = " + formatRemainingFa(24 * 60_000L),
                    "formatEpisodeFa = " + formatEpisodeFa(2, 3),
                ),
            )
        }
    }
}

/** Page 2: the three non-content states, side by side. */
@Composable
private fun StatesPage() {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = BolbolSpacing.SafeHorizontal, vertical = BolbolSpacing.SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(BolbolSpacing.ItemGap),
    ) {
        Panel("LoadingState") { LoadingState() }
        Panel("ErrorState") { ErrorState(message = "اتصال به سرور برقرار نشد.\nاینترنت را بررسی کنید.", onRetry = {}) }
        Panel("EmptyState") { EmptyState(message = "موردی یافت نشد") }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Panel(label: String, content: @Composable () -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight()) {
        BolbolText(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = BolbolColors.TextSecondary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(BolbolColors.Surface, RoundedCornerShape(BolbolSpacing.CardRadius)),
        ) { content() }
    }
}
