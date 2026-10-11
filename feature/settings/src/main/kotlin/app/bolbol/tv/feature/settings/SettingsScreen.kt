package app.bolbol.tv.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText

private val ListWidth = 200.dp
private val PaneGap = 32.dp

/**
 * Settings: a section list on the start (right) side, the selected section's page on the end side.
 * D-pad: up/down in the list switches the page at once; the end direction (left) or OK enters the
 * page; Back from the page returns to the list (Back from the list is the host's: leave the screen).
 * Every change is saved as it is made.
 *
 * @param initialSection which page to show first (e.g. SUBTITLES when opened from the player).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    initialSection: SettingsSection = SettingsSection.SUBTITLES,
) {
    val subtitleStyle by viewModel.subtitleStyle.collectAsStateWithLifecycle()
    val playbackPrefs by viewModel.playbackPrefs.collectAsStateWithLifecycle()
    val showPerformance by viewModel.showPerformance.collectAsStateWithLifecycle()
    val mirror by viewModel.mirror.collectAsStateWithLifecycle()
    val mirrorResetDone by viewModel.mirrorResetDone.collectAsStateWithLifecycle()

    var nav by remember { mutableStateOf(SettingsNavState(initialSection)) }
    // Attached to the selected list item / to the first control of the shown page.
    val listEntry = remember { FocusRequester() }
    val contentEntry = remember { FocusRequester() }

    // Initial focus: the section list (on the initial section).
    LaunchedEffect(Unit) { runCatching { listEntry.requestFocus() } }
    LaunchedEffect(nav.section) {
        if (nav.section == SettingsSection.CONNECTION) viewModel.refreshMirror()
    }

    BackHandler(enabled = nav.zone == SettingsZone.CONTENT) {
        nav.back()?.let { nav = it }
        runCatching { listEntry.requestFocus() }
    }

    fun enterContent() {
        nav = nav.enterContent()
        if (nav.zone == SettingsZone.CONTENT) runCatching { contentEntry.requestFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(BolbolColors.Background)
            .padding(horizontal = BolbolSpacing.SafeHorizontal, vertical = BolbolSpacing.SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(PaneGap),
    ) {
        SectionList(
            selected = nav.section,
            selectedRequester = listEntry,
            onSectionFocused = { nav = nav.listFocused(it) },
            onEnterContent = ::enterContent,
            modifier = Modifier
                .width(ListWidth)
                .fillMaxHeight(),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { nav = if (it.hasFocus) nav.contentFocused() else nav.contentBlurred() },
        ) {
            // key: a new page starts with its own scroll position.
            key(nav.section) {
                when (nav.section) {
                    SettingsSection.SUBTITLES -> SubtitlePage(
                        style = subtitleStyle,
                        onChange = viewModel::onSubtitleChange,
                        entry = contentEntry,
                    )
                    SettingsSection.PLAYBACK -> PlaybackPage(
                        prefs = playbackPrefs,
                        onChange = viewModel::onPlaybackChange,
                        entry = contentEntry,
                    )
                    SettingsSection.CONNECTION -> ConnectionPage(
                        mirror = mirror,
                        resetDone = mirrorResetDone,
                        onReset = viewModel::onResetMirrors,
                        entry = contentEntry,
                    )
                    SettingsSection.DEVELOPER -> DeveloperPage(
                        showPerformance = showPerformance,
                        onShowPerformance = viewModel::onShowPerformance,
                        entry = contentEntry,
                    )
                    SettingsSection.ABOUT -> AboutPage(versionName = viewModel.versionName)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SectionList(
    selected: SettingsSection,
    selectedRequester: FocusRequester,
    onSectionFocused: (SettingsSection) -> Unit,
    onEnterContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // Coming back from the page lands on the selected section, not on whatever is level with
        // the last focused control.
        modifier = modifier.focusRestorer(selectedRequester),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsSection.entries.forEach { section ->
            key(section) {
                SectionItem(
                    label = section.label,
                    selected = section == selected,
                    onClick = onEnterContent,
                    modifier = Modifier
                        .then(if (section == selected) Modifier.focusRequester(selectedRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) onSectionFocused(section) }
                        // End direction in RTL = D-pad left: into the page.
                        .onPreviewKeyEvent {
                            val enter = it.key == Key.DirectionLeft
                            if (enter && it.type == KeyEventType.KeyDown) onEnterContent()
                            enter
                        },
                )
            }
        }
    }
}

private val ItemShape = RoundedCornerShape(BolbolSpacing.CardRadius)

@Composable
private fun SectionItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(ItemShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BolbolColors.SurfaceHigh else Color.Transparent,
            contentColor = if (selected) BolbolColors.Accent else BolbolColors.TextSecondary,
            focusedContainerColor = BolbolColors.TextPrimary,
            focusedContentColor = BolbolColors.Background,
            pressedContainerColor = BolbolColors.TextPrimary,
            pressedContentColor = BolbolColors.Background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE_SMALL),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = ItemShape),
        ),
    ) {
        BolbolText(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
    }
}
