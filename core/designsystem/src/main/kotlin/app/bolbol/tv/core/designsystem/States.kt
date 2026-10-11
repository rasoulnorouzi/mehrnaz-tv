package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * Placeholder while a screen or row loads: a static row of solid poster-shaped blocks and a
 * caption. Deliberately **not animated** (no shimmer, no spinner): nothing redraws while waiting.
 * Fills the available space and centres its content.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = "در حال بارگذاری…",
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 96.dp)
                        .background(BolbolColors.SurfaceHigh, RoundedCornerShape(BolbolSpacing.CardRadius)),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        BolbolText(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * Full-area error with a **focused** retry button ("تلاش دوباره"): focus is requested when the
 * state appears, so the user can press OK right away. Never traps focus (D-pad still leaves).
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "تلاش دوباره",
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(48.dp).background(BolbolColors.Error, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BolbolText(
                text = "!",
                style = MaterialTheme.typography.headlineSmall,
                color = BolbolColors.Background,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        BolbolText(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = BolbolColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 4,
        )
        Spacer(Modifier.height(20.dp))
        TvButton(
            text = retryLabel,
            onClick = onRetry,
            modifier = Modifier.focusRequester(retryFocus),
            icon = BolbolIcons.Refresh,
        )
    }
}

/** Full-area "nothing here" message (empty list, no search results). */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        BolbolText(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = BolbolColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 4,
        )
    }
}
