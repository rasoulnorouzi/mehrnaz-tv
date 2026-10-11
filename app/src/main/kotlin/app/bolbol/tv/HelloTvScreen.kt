package app.bolbol.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.FocusCard
import app.bolbol.tv.core.designsystem.toPersianDigits

/**
 * M0.5 smoke-test screen: checks RTL layout, Persian shaping, mixed Persian/Latin
 * text (bidi), D-pad focus order and the focus visuals on a real TV / emulator.
 * Replaced by the home screen in M1.5.
 */
@Composable
fun HelloTvScreen() {
    val titles = listOf(
        "اسلحه برهنه",
        "The Naked Gun 2025",
        "وان پیس لگویی",
        "سریال LEGO ONE PIECE",
        "بازیگر",
        "Masters of the Universe",
        "فیلم ۷",
        "فیلم ۸",
    )
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstCard.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BolbolColors.Background)
            .padding(vertical = BolbolSpacing.SafeVertical),
        verticalArrangement = Arrangement.spacedBy(BolbolSpacing.RowGap),
    ) {
        Column(modifier = Modifier.padding(horizontal = BolbolSpacing.SafeHorizontal)) {
            Text(
                text = "بلبل تی‌وی",
                style = MaterialTheme.typography.displaySmall,
                color = BolbolColors.Accent,
            )
            Text(
                text = "نسخهٔ آزمایشی · مرحلهٔ ${0.toPersianDigits()} · راست‌به‌چپ و فوکوس ریموت",
                style = MaterialTheme.typography.titleMedium,
                color = BolbolColors.TextSecondary,
            )
        }
        Text(
            text = "ردیف آزمایشی (${titles.size.toPersianDigits()} مورد)",
            style = MaterialTheme.typography.headlineSmall,
            color = BolbolColors.TextPrimary,
            modifier = Modifier.padding(horizontal = BolbolSpacing.SafeHorizontal),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = BolbolSpacing.SafeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(BolbolSpacing.ItemGap),
        ) {
            itemsIndexed(titles, key = { index, _ -> index }) { index, title ->
                FocusCard(
                    onClick = {},
                    modifier = Modifier
                        .size(width = 140.dp, height = 210.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = BolbolColors.TextPrimary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
