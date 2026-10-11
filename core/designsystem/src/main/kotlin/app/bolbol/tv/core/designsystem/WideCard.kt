package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import app.bolbol.tv.core.model.ContinueWatchingItem

/** Fixed dimensions of [WideCard] (16:9). */
object WideCardDefaults {
    val Width = 260.dp
    val Height = 146.dp

    /** Reserved text area under the image (10 dp gap + body line + label line). */
    val TextHeight = 52.dp
}

/**
 * Landscape card, 16:9 ([WideCardDefaults]: 260 x 146 dp), for Continue Watching and episode
 * lists. [imageUrl] is a backdrop / episode still; [progress] (0..1, shown when > 0) draws a
 * [ProgressLine] over the bottom edge. Below the image there are always two reserved lines:
 * [title] (body) and [subtitle] (secondary, e.g. `"قسمت ۳ · ۲۴ دقیقه مانده"` built with
 * [formatEpisodeFa] and [formatRemainingFa]). Focus: white border + 1.08x scale.
 * [onFocused] fires when the card gains focus. [modifier] applies to the whole card.
 */
@Composable
fun WideCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    onFocused: (() -> Unit)? = null,
) {
    val currentOnFocused = rememberUpdatedState(onFocused)
    Column(modifier = modifier.width(WideCardDefaults.Width)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(WideCardDefaults.Width, WideCardDefaults.Height)
                .onFocusChanged { if (it.isFocused) currentOnFocused.value?.invoke() }
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
                        width = WideCardDefaults.Width,
                        height = WideCardDefaults.Height,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    BolbolText(
                        text = title,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
                if (progress != null && progress > 0f) {
                    ProgressLine(progress, Modifier.align(Alignment.BottomStart))
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(WideCardDefaults.TextHeight)
                .padding(top = 10.dp, start = 2.dp, end = 2.dp),
        ) {
            BolbolText(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
            )
            if (!subtitle.isNullOrBlank()) {
                BolbolText(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = BolbolColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * [WideCard] for a Continue Watching entry: backdrop (poster as fallback), Persian title, a
 * subtitle like `"فصل ۲ · قسمت ۳ · ۲۴ دقیقه مانده"` (the season is shown from season 2 on) or
 * just the remaining time for movies ([formatContinueSubtitleFa]; «ادامه از ۱۲:۳۴» when the length is unknown), and
 * the saved progress bar (a minimal sliver when the length is unknown).
 */
@Composable
fun WideCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
) {
    val progress = item.progress
    WideCard(
        title = item.title.faTitle.ifBlank { item.title.title },
        imageUrl = item.title.backdropUrl ?: item.title.posterUrl,
        onClick = onClick,
        modifier = modifier,
        subtitle = formatContinueSubtitleFa(progress),
        progress = if (progress.durationMs <= 0L && !progress.completed) UNKNOWN_LENGTH_PROGRESS else progress.fraction,
        onFocused = onFocused,
    )
}

/** Bar fill of a started title whose length is unknown: the same 3 % sliver the other cards use as their minimum. */
private const val UNKNOWN_LENGTH_PROGRESS = 0.03f
