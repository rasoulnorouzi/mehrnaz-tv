package app.bolbol.tv.feature.details

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.designsystem.TvDialog
import app.bolbol.tv.core.designsystem.formatBytesFa
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.WatchKey

// ---------------------------------------------------------------------------------------------
// Movie buttons
// ---------------------------------------------------------------------------------------------

/** Callbacks of the download UI; the details screen forwards them to the ViewModel. */
internal class DownloadActions(
    val onDownload: (WatchKey) -> Unit,
    val onRemove: (WatchKey) -> Unit,
    val onRetry: (WatchKey) -> Unit,
)

@Composable
internal fun downloadButtonText(state: DownloadButtonState): String = when (state) {
    DownloadButtonState.Idle -> stringResource(R.string.details_download)
    DownloadButtonState.Queued -> stringResource(R.string.details_download_queued)
    is DownloadButtonState.Downloading ->
        if (state.percent != null) {
            stringResource(R.string.details_download_progress, state.percent.toPersianDigits())
        } else {
            stringResource(R.string.details_download_progress_unknown)
        }
    is DownloadButtonState.Paused -> stringResource(R.string.details_download_paused)
    DownloadButtonState.Completed -> stringResource(R.string.details_download_done)
    is DownloadButtonState.Failed -> stringResource(R.string.details_download_failed)
}

/**
 * The movie's «دانلود» button and, when something is saved or saving, a red «حذف دانلود» button right next to it.
 * The status button is inert while downloading / saved (it only shows the state); a failed one retries.
 */
@Composable
internal fun MovieDownloadButtons(
    key: WatchKey,
    state: DownloadButtonState,
    actions: DownloadActions,
) {
    TvButton(
        text = downloadButtonText(state),
        onClick = {
            when (state) {
                DownloadButtonState.Idle -> actions.onDownload(key)
                is DownloadButtonState.Failed -> actions.onRetry(key)
                else -> Unit
            }
        },
        icon = when (state) {
            DownloadButtonState.Completed -> BolbolIcons.Check
            is DownloadButtonState.Failed -> BolbolIcons.Refresh
            else -> BolbolIcons.Download
        },
        style = TvButtonStyle.Secondary,
    )
    if (state.removable) {
        TvButton(
            text = stringResource(R.string.details_download_remove),
            onClick = { actions.onRemove(key) },
            icon = BolbolIcons.Delete,
            style = TvButtonStyle.Danger,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Episode icon
// ---------------------------------------------------------------------------------------------

private val EpisodeDownloadShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/**
 * The small download control at the end of an episode row (D-pad towards the screen's end edge from the row).
 * Idle: a download arrow. Waiting: «صف». Downloading: «۴۵٪». Saved: a yellow check. Failed: a red retry arrow.
 * OK on an idle one opens the quality dialog, on a failed one retries, on any other opens the «حذف دانلود؟» confirm.
 */
@Composable
internal fun EpisodeDownloadButton(
    key: WatchKey,
    state: DownloadButtonState,
    actions: DownloadActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = {
            when (state) {
                DownloadButtonState.Idle -> actions.onDownload(key)
                is DownloadButtonState.Failed -> actions.onRetry(key)
                else -> actions.onRemove(key)
            }
        },
        modifier = modifier.size(width = 72.dp, height = 60.dp),
        shape = ClickableSurfaceDefaults.shape(EpisodeDownloadShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BolbolColors.Surface,
            contentColor = BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.SurfaceHigh,
            focusedContentColor = BolbolColors.TextPrimary,
            pressedContainerColor = BolbolColors.SurfaceHigh,
            pressedContentColor = BolbolColors.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = EpisodeDownloadShape),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state) {
                DownloadButtonState.Idle -> StateIcon(BolbolIcons.Download, BolbolColors.TextSecondary, R.string.details_download)
                DownloadButtonState.Completed -> StateIcon(BolbolIcons.Check, BolbolColors.Accent, R.string.details_download_done)
                is DownloadButtonState.Failed -> StateIcon(BolbolIcons.Refresh, BolbolColors.Error, R.string.details_download_failed)
                DownloadButtonState.Queued -> StateText(stringResource(R.string.details_download_queued_short), BolbolColors.TextSecondary)
                is DownloadButtonState.Paused -> StateText(stringResource(R.string.details_download_paused_short), BolbolColors.TextSecondary)
                is DownloadButtonState.Downloading -> StateText(
                    text = state.percent?.let { stringResource(R.string.details_percent, it.toPersianDigits()) } ?: "…",
                    color = BolbolColors.Accent,
                )
            }
        }
    }
}

@Composable
private fun StateIcon(icon: ImageVector, tint: Color, @StringRes description: Int) {
    Icon(imageVector = icon, contentDescription = stringResource(description), modifier = Modifier.size(24.dp), tint = tint)
}

@Composable
private fun StateText(text: String, color: Color) {
    BolbolText(text = text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

/** Draws whichever modal is open. [titleName] is the Persian (or original) title shown in the confirm text. */
@Composable
internal fun DetailsOverlayHost(
    overlay: DetailsOverlay?,
    titleName: String,
    onChoose: (StreamOption) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (overlay) {
        null -> Unit
        is DetailsOverlay.Pick -> DownloadPickDialog(overlay.state, titleName, onChoose, onDismiss)
        is DetailsOverlay.ConfirmRemove -> ConfirmRemoveDialog(overlay, titleName, onConfirmRemove, onDismiss)
    }
}

private val OptionShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/**
 * «دانلود»: every quality × دوبله/زیرنویس with its size and the TV's free space on top. Rows that do not fit are
 * dimmed and marked «جای کافی نیست» (they stay focusable, OK explains with the needed and the free amount). Focus starts
 * on the first row; Back closes.
 */
@Composable
private fun DownloadPickDialog(
    state: DownloadPickState,
    titleName: String,
    onChoose: (StreamOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    TvDialog(onDismiss = onDismiss, width = 620.dp) {
        BolbolText(
            text = stringResource(R.string.details_download_dialog_title),
            style = MaterialTheme.typography.titleLarge,
            color = BolbolColors.TextPrimary,
            maxLines = 1,
        )
        BolbolText(
            text = listOfNotNull(titleName, state.subtitleLine).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = BolbolColors.TextSecondary,
            maxLines = 2,
        )
        BolbolText(
            text = state.freeBytes?.let { stringResource(R.string.details_download_free_space, formatBytesFa(it)) }
                ?: stringResource(R.string.details_download_free_loading),
            style = MaterialTheme.typography.titleSmall,
            color = BolbolColors.Accent,
            maxLines = 1,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.options.forEachIndexed { index, option ->
                OptionRow(
                    option = option,
                    onClick = { onChoose(option.stream) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        state.message?.let { message ->
            BolbolText(
                text = downloadMessageText(message),
                style = MaterialTheme.typography.titleSmall,
                color = BolbolColors.Error,
                maxLines = 3,
            )
        }
        TvButton(
            text = stringResource(R.string.details_download_cancel),
            onClick = onDismiss,
            style = TvButtonStyle.Secondary,
        )
    }
}

@Composable
private fun downloadMessageText(message: DownloadMessage): String = when (message) {
    is DownloadMessage.NotEnoughSpace -> stringResource(
        R.string.details_download_no_space,
        formatBytesFa(message.neededBytes),
        formatBytesFa(message.freeBytes),
    )
    is DownloadMessage.Failed -> stringResource(
        when (message.failure) {
            DownloadFailure.NO_SPACE -> R.string.details_download_fail_no_space
            DownloadFailure.NETWORK -> R.string.details_download_fail_network
            DownloadFailure.SOURCE_GONE -> R.string.details_download_fail_gone
            DownloadFailure.UNKNOWN -> R.string.details_download_fail_generic
        },
    )
}

@Composable
private fun OptionRow(option: DownloadOptionUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val stream = option.stream
    val audio = stringResource(
        if (stream.audio == AudioType.DUBBED) R.string.details_audio_dubbed else R.string.details_audio_subtitled,
    )
    val quality = if (stream.quality > 0) stream.quality.toPersianDigits() else stream.label
    val dim = !option.fits
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = ClickableSurfaceDefaults.shape(OptionShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BolbolColors.SurfaceHigh,
            contentColor = if (dim) BolbolColors.TextSecondary else BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.TextPrimary,
            focusedContentColor = BolbolColors.Background,
            pressedContainerColor = BolbolColors.TextPrimary,
            pressedContentColor = BolbolColors.Background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = OptionShape),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BolbolText(
                text = "$quality · $audio",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            if (dim) {
                BolbolText(
                    text = stringResource(R.string.details_download_no_fit),
                    style = MaterialTheme.typography.labelMedium,
                    color = BolbolColors.Error,
                    maxLines = 1,
                )
            }
            BolbolText(
                text = optionSizeText(option),
                modifier = Modifier.width(110.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** «…» while `sizeOf` is running, then the measured size, else the site's own text ("2.42 GB"), else «نامشخص». */
@Composable
private fun optionSizeText(option: DownloadOptionUi): String = when {
    option.sizeBytes != null -> formatBytesFa(option.sizeBytes)
    option.sizeLoading -> "…"
    !option.stream.sizeText.isNullOrBlank() -> option.stream.sizeText!!.toPersianDigits()
    else -> stringResource(R.string.details_download_size_unknown)
}

/** «حذف دانلود؟»: focus starts on «انصراف» so an accidental double OK never deletes gigabytes. */
@Composable
private fun ConfirmRemoveDialog(
    overlay: DetailsOverlay.ConfirmRemove,
    titleName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    TvDialog(onDismiss = onDismiss, width = 560.dp) {
        BolbolText(
            text = stringResource(R.string.details_download_confirm_title),
            style = MaterialTheme.typography.titleLarge,
            color = BolbolColors.TextPrimary,
            maxLines = 1,
        )
        BolbolText(
            text = stringResource(
                R.string.details_download_confirm_body,
                listOfNotNull(titleName, overlay.subtitleLine).joinToString(" · "),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = BolbolColors.TextSecondary,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = stringResource(R.string.details_download_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancel),
                style = TvButtonStyle.Secondary,
            )
            TvButton(
                text = stringResource(R.string.details_download_remove),
                onClick = onConfirm,
                icon = BolbolIcons.Delete,
                style = TvButtonStyle.Danger,
            )
        }
    }
}
