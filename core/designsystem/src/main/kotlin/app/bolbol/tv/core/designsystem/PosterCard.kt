package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import app.bolbol.tv.core.model.TitleSummary

/** Fixed dimensions of [PosterCard] (2:3). */
object PosterCardDefaults {
    val Width = 140.dp
    val Height = 210.dp

    /** Space reserved below the poster for the focused title + year, so rows never reflow. */
    val CaptionHeight = 52.dp
}

internal val CardShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/** Surface colours shared by [PosterCard] and [WideCard]: solid placeholder, lighter when focused. */
@Composable
internal fun cardSurfaceColors() = ClickableSurfaceDefaults.colors(
    containerColor = BolbolColors.Surface,
    contentColor = BolbolColors.TextPrimary,
    focusedContainerColor = BolbolColors.SurfaceHigh,
    focusedContentColor = BolbolColors.TextPrimary,
    pressedContainerColor = BolbolColors.SurfaceHigh,
    pressedContentColor = BolbolColors.TextPrimary,
)

private val CardFocusBorder = Border(
    border = BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing),
    shape = CardShape,
)

@Composable
internal fun cardSurfaceBorder() = ClickableSurfaceDefaults.border(focusedBorder = CardFocusBorder)

@Composable
internal fun cardSurfaceScale() = ClickableSurfaceDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE)

/**
 * Portrait poster card, 2:3 ([PosterCardDefaults]: 140 x 210 dp).
 *
 * - Poster: Coil request sized to the card in pixels, no crossfade, Crop; the solid surface colour
 *   is the placeholder (no shimmer). A null/blank [imageUrl] draws the title inside the card.
 * - Top-start badges (right in RTL): [dubbed]/[subtitled] pills, then the [imdb] rating.
 * - Bottom: [ProgressLine] when [progress] is in (0, 1] (a started title).
 * - Focus: white 2 dp border + 1.08x scale (graphicsLayer). The title and [year] appear
 *   **below** the card only while focused (or always with [alwaysShowCaption]); that space is
 *   reserved ([PosterCardDefaults.CaptionHeight]) so focus never reflows the row.
 * - [onFocused] fires when the card gains focus (use it to drive a debounced hero backdrop).
 * - [modifier] applies to the whole card (poster + caption); `focusRequester(...)` on it works.
 *
 * [year] is shown as given (years stay Latin digits, see Formatters.kt).
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    year: String? = null,
    imdb: Float? = null,
    dubbed: Boolean = false,
    subtitled: Boolean = false,
    progress: Float? = null,
    alwaysShowCaption: Boolean = false,
    onFocused: (() -> Unit)? = null,
) {
    val focused = remember { mutableStateOf(false) }
    val currentOnFocused = rememberUpdatedState(onFocused)
    Column(modifier = modifier.width(PosterCardDefaults.Width)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(PosterCardDefaults.Width, PosterCardDefaults.Height)
                .onFocusChanged {
                    focused.value = it.isFocused
                    if (it.isFocused) currentOnFocused.value?.invoke()
                }
                .semantics { contentDescription = title },
            shape = ClickableSurfaceDefaults.shape(CardShape),
            colors = cardSurfaceColors(),
            scale = cardSurfaceScale(),
            border = cardSurfaceBorder(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!imageUrl.isNullOrBlank()) {
                    CoverImage(
                        url = imageUrl,
                        width = PosterCardDefaults.Width,
                        height = PosterCardDefaults.Height,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    BolbolText(
                        text = title,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                    )
                }
                if (dubbed || subtitled || imdb != null) {
                    Column(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AudioBadge(dubbed = dubbed, subtitled = subtitled)
                        if (imdb != null && imdb > 0f) RatingBadge(imdb)
                    }
                }
                if (progress != null && progress > 0f) {
                    ProgressLine(progress, Modifier.align(Alignment.BottomStart))
                }
            }
        }
        PosterCaption(title = title, year = year, focused = focused, always = alwaysShowCaption)
    }
}

/** Reads [focused] here (not in [PosterCard]) so a focus change recomposes only the caption. */
@Composable
private fun PosterCaption(title: String, year: String?, focused: State<Boolean>, always: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(PosterCardDefaults.CaptionHeight)
            .padding(top = 10.dp, start = 2.dp, end = 2.dp),
    ) {
        if (always || focused.value) {
            Column {
                BolbolText(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.TextPrimary,
                    maxLines = 1,
                )
                if (!year.isNullOrBlank()) {
                    BolbolText(
                        text = year,
                        style = MaterialTheme.typography.labelMedium,
                        color = BolbolColors.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * [PosterCard] for a catalogue item. Shows the Persian title (falls back to the display title),
 * the year as Latin digits, the IMDb rating and the dub/subtitle flags.
 */
@Composable
fun PosterCard(
    item: TitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    alwaysShowCaption: Boolean = false,
    onFocused: (() -> Unit)? = null,
) {
    PosterCard(
        title = item.faTitle.ifBlank { item.title },
        imageUrl = item.posterUrl,
        onClick = onClick,
        modifier = modifier,
        year = item.year?.toString(),
        imdb = item.imdbRating,
        dubbed = item.hasDub,
        subtitled = item.hasSubtitle,
        progress = progress,
        alwaysShowCaption = alwaysShowCaption,
        onFocused = onFocused,
    )
}
