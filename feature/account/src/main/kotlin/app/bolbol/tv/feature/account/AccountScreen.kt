package app.bolbol.tv.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.EmptyState
import app.bolbol.tv.core.designsystem.PosterCard
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.TitleSummary

/** Grid columns of "My List" (docs/design-system.md: 5 posters of 140 dp across the 864 dp safe width). */
internal const val GRID_COLUMNS = 5

/** Room above the first row and below the last for the 1.08x focus scale and the focus border. */
private val GridEdgePadding = 12.dp

/**
 * The «لیست من» screen (docs/auth.md): the local My List as a 5-column poster grid under a «لیست من» header with the
 * count; empty it shows «لیست شما خالی است». Signing in and out lives on the Profile screen ([ProfileScreen]); while
 * signed out a slim hint row says so and offers [onOpenProfile].
 *
 * Focus starts on the first poster, or on the hint's button when the list is empty and the user is signed out.
 * Draws its own background; apply BolbolTheme (RTL) above it.
 */
@Composable
fun AccountScreen(
    viewModel: AccountViewModel,
    onOpenTitle: (TitleSummary) -> Unit,
    modifier: Modifier = Modifier,
    onOpenProfile: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstPosterFocus = remember { FocusRequester() }
    val profileFocus = remember { FocusRequester() }

    // Once, when the list was first read: later changes (a sync adding titles) never steal focus.
    LaunchedEffect(state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        when {
            state.myList.isNotEmpty() -> firstPosterFocus.requestFocusWhenAttached()
            !state.loggedIn -> profileFocus.requestFocusWhenAttached()
        }
    }

    Box(modifier.fillMaxSize().background(BolbolColors.Background)) {
        if (!state.loaded) return@Box
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = BolbolSpacing.SafeHorizontal,
                end = BolbolSpacing.SafeHorizontal,
                top = BolbolSpacing.SafeVertical + GridEdgePadding,
                bottom = BolbolSpacing.SafeVertical + GridEdgePadding,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = KEY_TITLE, span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                ListTitle(count = state.myList.size)
            }
            if (!state.loggedIn) {
                item(key = KEY_HINT, span = { GridItemSpan(maxLineSpan) }, contentType = "hint") {
                    SignInHint(onOpenProfile = onOpenProfile, buttonFocus = profileFocus)
                }
            }
            if (state.myList.isEmpty()) {
                item(key = KEY_EMPTY, span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    EmptyState(message = "لیست شما خالی است", modifier = Modifier.height(180.dp))
                }
            } else {
                itemsIndexed(items = state.myList, key = { _, item -> item.id }, contentType = { _, _ -> "poster" }) { index, item ->
                    // 864 dp / 5 = 172.8 dp cells: centre each 140 dp card so the slack is shared evenly.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        PosterCard(
                            item = item,
                            onClick = { onOpenTitle(item) },
                            modifier = if (index == 0) Modifier.focusRequester(firstPosterFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

private const val KEY_TITLE = "title"
private const val KEY_HINT = "hint"
private const val KEY_EMPTY = "empty"

/** «برای همگام‌سازی با سایت، از بخش پروفایل وارد شوید» with a button to the Profile screen. */
@Composable
private fun SignInHint(onOpenProfile: () -> Unit, buttonFocus: FocusRequester) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(BolbolColors.Surface, CardShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BolbolText(
            text = "برای همگام‌سازی با سایت، از بخش پروفایل وارد شوید",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
            maxLines = 2,
        )
        TvButton(
            text = "پروفایل",
            onClick = onOpenProfile,
            modifier = Modifier.focusRequester(buttonFocus),
            style = TvButtonStyle.Secondary,
        )
    }
}

@Composable
private fun ListTitle(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BolbolText(
            text = "لیست من",
            style = MaterialTheme.typography.titleLarge,
            color = BolbolColors.TextPrimary,
            maxLines = 1,
        )
        if (count > 0) {
            BolbolText(
                text = "${count.toPersianDigits()} عنوان",
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}
