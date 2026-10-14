package app.bolbol.tv.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
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
import app.bolbol.tv.core.model.UpdateState

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

/** Settings → به‌روزرسانی: version, check, download and install, with the one-time permission explanation. */
@Composable
internal fun UpdatePage(
    versionName: String,
    state: UpdateState,
    onAction: (UpdateAction) -> Unit,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val ui = updateUi(state)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BolbolText(
                text = "نسخهٔ فعلی",
                modifier = Modifier.width(LabelWidth),
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                maxLines = 1,
            )
            // Version names are data (Latin digits and letters), like release years.
            BolbolText(text = versionName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        }
        BolbolText(
            text = ui.status,
            modifier = Modifier.widthIn(max = 640.dp),
            style = MaterialTheme.typography.titleSmall,
            color = if (ui.highlight) BolbolColors.Accent else BolbolColors.TextPrimary,
        )
        if (ui.size.isNotEmpty()) {
            BolbolText(
                text = ui.size,
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
            )
        }
        if (ui.notes.isNotBlank()) {
            BolbolText(
                text = ui.notes,
                modifier = Modifier.widthIn(max = 640.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.buttons.forEachIndexed { index, button ->
                key(button.action) {
                    TvButton(
                        text = button.label,
                        // A no-op (not disabled) button while busy: it keeps the D-pad focus.
                        onClick = { onAction(button.action) },
                        modifier = if (index == 0) Modifier.focusRequester(entry) else Modifier,
                        icon = if (button.action == UpdateAction.CHECK) BolbolIcons.Refresh else null,
                        style = if (index == 0) TvButtonStyle.Primary else TvButtonStyle.Secondary,
                    )
                }
            }
        }
    }
}

/** Settings → توسعه‌دهنده. */
@Composable
internal fun DeveloperPage(
    showPerformance: Boolean,
    onShowPerformance: (Boolean) -> Unit,
    manifestUrl: String,
    onManifestUrl: (String) -> Unit,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ToggleRow(
            title = "نمایش اطلاعات عملکرد",
            description = PERFORMANCE_DESCRIPTION,
            checked = showPerformance,
            onCheckedChange = onShowPerformance,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .focusRequester(entry),
        )
        BolbolText(
            text = "نشانی فایل به‌روزرسانی (برای آزمایش)",
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
        )
        UrlField(value = manifestUrl, onValueChange = onManifestUrl, modifier = Modifier.widthIn(max = 520.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "نشانی اصلی",
                onClick = { onManifestUrl("") },
                style = TvButtonStyle.Secondary,
            )
            TvButton(
                text = "رایانهٔ من (شبیه‌ساز)",
                onClick = { onManifestUrl(EMULATOR_MANIFEST_URL) },
                style = TvButtonStyle.Secondary,
            )
        }
        BolbolText(
            text = if (manifestUrl.isBlank()) "اکنون: نشانی اصلی در گیت‌هاب." else "اکنون نشانی آزمایشی استفاده می‌شود.",
            style = MaterialTheme.typography.bodySmall,
            color = BolbolColors.TextSecondary,
        )
    }
}

private val FieldShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/** One-line URL field: always left to right; D-pad focus draws the ring, OK opens the keyboard. */
@Composable
private fun UrlField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    // Edited locally and saved on every keystroke; the saved value is only the starting point.
    var text by remember { mutableStateOf(value) }
    BasicTextField(
        value = text,
        onValueChange = { text = it; onValueChange(it) },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(BolbolColors.Surface, FieldShape)
            .border(
                BolbolSpacing.FocusBorder,
                if (focused) BolbolColors.FocusRing else BolbolColors.Outline,
                FieldShape,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = BolbolColors.TextPrimary,
            textDirection = TextDirection.Ltr,
        ),
        cursorBrush = SolidColor(BolbolColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) {
                    BolbolText(
                        text = "https://…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BolbolColors.TextSecondary,
                    )
                }
                inner()
            }
        },
    )
}

/** Settings → درباره. Nothing here takes focus. */
@Composable
internal fun AboutPage(versionName: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BolbolText(
            text = "مهرناز",
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
