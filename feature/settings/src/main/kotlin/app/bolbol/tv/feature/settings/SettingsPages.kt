package app.bolbol.tv.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.ChipGroup
import app.bolbol.tv.core.designsystem.DedicationLine
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.model.PlaybackPrefs

private val LabelWidth = 120.dp

/** Label at the start, chips after it; one such row per [OptionSpec]. [firstRowModifier] goes on row 1 only. */
@Composable
internal fun <S, C> OptionRows(
    specs: List<OptionSpec<S, C>>,
    value: S,
    onChange: (C) -> Unit,
    modifier: Modifier = Modifier,
    firstRowModifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        specs.forEachIndexed { index, spec ->
            key(index) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BolbolText(
                        text = spec.label,
                        modifier = Modifier.width(LabelWidth),
                        style = MaterialTheme.typography.bodyMedium,
                        color = BolbolColors.TextSecondary,
                        maxLines = 1,
                    )
                    ChipGroup(
                        options = spec.options,
                        selectedIndex = spec.selectedIndex(value),
                        onSelect = { onChange(spec.change(it)) },
                        modifier = if (index == 0) firstRowModifier else Modifier,
                    )
                }
            }
        }
    }
}


/** Settings → پخش: default audio and quality (global). */
@Composable
internal fun PlaybackPage(
    prefs: PlaybackPrefs,
    onChange: (PlaybackChange) -> Unit,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OptionRows(
            specs = PlaybackOptionSpecs,
            value = prefs,
            onChange = onChange,
            firstRowModifier = Modifier.focusRequester(entry),
        )
        BolbolText(
            text = "اگر چیزی انتخاب نشود، پخش‌کننده بهترین نسخهٔ موجود را خودش برمی‌گزیند.",
            style = MaterialTheme.typography.bodySmall,
            color = BolbolColors.TextSecondary,
        )
    }
}

/** Settings → اتصال: the server in use and a reset of the failover state. */
@Composable
internal fun ConnectionPage(
    mirror: String,
    resetDone: Boolean,
    onReset: () -> Unit,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BolbolText(
                text = "سرور فعلی",
                modifier = Modifier.width(LabelWidth),
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                maxLines = 1,
            )
            BolbolText(
                text = mirror.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
        }
        TvButton(
            text = "بازنشانی سرورها",
            onClick = onReset,
            modifier = Modifier.focusRequester(entry),
            icon = BolbolIcons.Refresh,
            style = TvButtonStyle.Secondary,
        )
        BolbolText(
            text = "اگر سرور فعلی کند یا ناپایدار شد، این گزینه برنامه را دوباره از اولین سرور شروع می‌کند.",
            style = MaterialTheme.typography.bodySmall,
            color = BolbolColors.TextSecondary,
        )
        if (resetDone) {
            BolbolText(
                text = "سرورها بازنشانی شد.",
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.Accent,
            )
        }
    }
}

/** Settings → توسعه‌دهنده. */
@Composable
internal fun DeveloperPage(
    showPerformance: Boolean,
    onShowPerformance: (Boolean) -> Unit,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ToggleRow(
            title = "نمایش اطلاعات عملکرد",
            description = "FPS، مصرف حافظه و آمار پخش‌کننده را روی صفحه نشان می‌دهد.",
            checked = showPerformance,
            onCheckedChange = onShowPerformance,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .focusRequester(entry),
        )
    }
}

/** Settings → درباره. Nothing here takes focus. */
@Composable
internal fun AboutPage(versionName: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BolbolText(
            text = "بلبل تی‌وی",
            style = MaterialTheme.typography.headlineSmall,
            color = BolbolColors.Accent,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BolbolText(
                text = "نسخه",
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
            )
            // Version names are data (Latin digits and letters), like release years.
            BolbolText(text = versionName, style = MaterialTheme.typography.bodyMedium)
        }
        DedicationLine(style = MaterialTheme.typography.bodyMedium)
        BolbolText(
            text = "نسخهٔ شخصی، برای استفادهٔ خصوصی",
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
        )
    }
}

private val ToggleShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/** A whole-row switch: title + one-line explanation at the start, the switch and its state at the end. */
@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(ToggleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BolbolColors.Surface,
            contentColor = BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.SurfaceHigh,
            focusedContentColor = BolbolColors.TextPrimary,
            pressedContainerColor = BolbolColors.SurfaceHigh,
            pressedContentColor = BolbolColors.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE_SMALL),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = ToggleShape),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BolbolText(text = title, style = MaterialTheme.typography.titleSmall)
                BolbolText(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = BolbolColors.TextSecondary,
                )
            }
            SwitchGlyph(checked)
            BolbolText(
                text = if (checked) "روشن" else "خاموش",
                modifier = Modifier.widthIn(min = 36.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (checked) BolbolColors.Accent else BolbolColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/** Static switch drawing: pill track, round thumb at the end (left in RTL) when on. No animation. */
@Composable
private fun SwitchGlyph(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .background(if (checked) BolbolColors.Accent else BolbolColors.Outline, RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(if (checked) BolbolColors.OnAccent else BolbolColors.TextSecondary, CircleShape),
        )
    }
}
