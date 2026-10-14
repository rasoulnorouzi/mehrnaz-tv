package app.bolbol.tv.feature.downloads

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.EmptyState
import app.bolbol.tv.core.designsystem.LoadingState
import app.bolbol.tv.core.designsystem.ProgressLine
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.designsystem.TvDialog
import app.bolbol.tv.core.designsystem.formatBytesFa
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadItem
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

private val PosterWidth = 56.dp
private val PosterHeight = 84.dp
private val RowShape = RoundedCornerShape(12.dp)

/** Width reserved for «پخش» / «تلاش دوباره» so that every row's «حذف» sits in the same column (clean up/down moves). */
private val PrimaryActionSlot = 150.dp

/**
 * «دانلودها»: used / free space and «حذف همه» on top, then one row per download (poster, title, season/episode line,
 * quality, progress bar + percent, status) with «پخش» when ready (or «تلاش دوباره» when failed) and a big red «حذف».
 * Every removal asks first. Nothing here needs the internet: posters are local files, the data is local.
 *
 * Initial focus is the first row's main action; after a removal focus returns to the first row. Back is the
 * navigation's job ([onPlay] opens the player for a completed [DownloadItem]).
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onPlay: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val confirm by viewModel.confirm.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().background(BolbolColors.Background)) {
        when (val current = state) {
            DownloadsUiState.Loading -> LoadingState()
            is DownloadsUiState.Content -> DownloadsContent(
                content = current,
                onPlay = onPlay,
                onRetry = viewModel::onRetry,
                onRemove = viewModel::onRemoveClicked,
                onRemoveAll = viewModel::onRemoveAllClicked,
            )
        }
        confirm?.let { ConfirmDialog(it, onConfirm = viewModel::onConfirm, onDismiss = viewModel::onDismissConfirm) }
    }
}

@Composable
private fun DownloadsContent(
    content: DownloadsUiState.Content,
    onPlay: (DownloadItem) -> Unit,
    onRetry: (app.bolbol.tv.core.model.WatchKey) -> Unit,
    onRemove: (DownloadRowUi) -> Unit,
    onRemoveAll: () -> Unit,
) {
    val firstRow = remember { FocusRequester() }
    // After a removal the focused row is gone; put focus back on the first remaining row.
    var refocus by remember { mutableStateOf(true) }
    LaunchedEffect(content.rows.size) {
        if (refocus && content.rows.isNotEmpty()) {
            runCatching { firstRow.requestFocus() }
            refocus = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(
            content = content,
            onRemoveAll = {
                refocus = true
                onRemoveAll()
            },
        )
        if (content.rows.isEmpty()) {
            EmptyState(stringResource(R.string.downloads_empty))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = BolbolSpacing.SafeHorizontal,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(content.rows, key = { it.key.asString() }, contentType = { "download" }) { row ->
                    DownloadRow(
                        row = row,
                        onPlay = { onPlay(row.item) },
                        onRetry = { onRetry(row.key) },
                        onRemove = {
                            refocus = true
                            onRemove(row)
                        },
                        primaryFocus = if (row.key == content.rows.first().key) firstRow else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(content: DownloadsUiState.Content, onRemoveAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BolbolSpacing.SafeHorizontal)
            .padding(top = BolbolSpacing.SafeVertical, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BolbolText(
                text = stringResource(R.string.downloads_title),
                style = MaterialTheme.typography.headlineMedium,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
            )
            content.storage?.let { storage ->
                BolbolText(
                    text = stringResource(
                        R.string.downloads_storage,
                        formatBytesFa(storage.usedByDownloadsBytes),
                        formatBytesFa(storage.freeBytes),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = BolbolColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
        if (content.rows.isNotEmpty()) {
            TvButton(
                text = stringResource(R.string.downloads_remove_all),
                onClick = onRemoveAll,
                icon = BolbolIcons.Delete,
                style = TvButtonStyle.Danger,
            )
        }
    }
}

@Composable
private fun DownloadRow(
    row: DownloadRowUi,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    primaryFocus: FocusRequester?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(BolbolColors.Surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(row.posterUrl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BolbolText(
                text = row.titleText,
                style = MaterialTheme.typography.titleMedium,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
            )
            BolbolText(
                text = infoLine(row),
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                maxLines = 1,
            )
            if (row.showProgress) {
                ProgressLine(row.progressFraction, Modifier.padding(top = 4.dp), height = 6.dp)
            }
            BolbolText(
                text = statusLine(row),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(row),
                maxLines = 1,
            )
        }
        Box(Modifier.width(PrimaryActionSlot), contentAlignment = Alignment.Center) {
            when {
                row.canPlay -> TvButton(
                    text = stringResource(R.string.downloads_play),
                    onClick = onPlay,
                    modifier = Modifier.then(if (primaryFocus != null) Modifier.focusRequester(primaryFocus) else Modifier),
                    icon = BolbolIcons.Play,
                )
                row.canRetry -> TvButton(
                    text = stringResource(R.string.downloads_retry),
                    onClick = onRetry,
                    modifier = Modifier.then(if (primaryFocus != null) Modifier.focusRequester(primaryFocus) else Modifier),
                    icon = BolbolIcons.Refresh,
                    style = TvButtonStyle.Secondary,
                )
                else -> Spacer(Modifier.size(1.dp))
            }
        }
        TvButton(
            text = stringResource(R.string.downloads_remove),
            onClick = onRemove,
            // Rows without a primary action (still saving) take the first focus on their remove button.
            modifier = Modifier.then(
                if (primaryFocus != null && !row.canPlay && !row.canRetry) Modifier.focusRequester(primaryFocus) else Modifier,
            ),
            icon = BolbolIcons.Delete,
            style = TvButtonStyle.Danger,
        )
    }
}

/** «۱۰۸۰ · دوبله» and, for an episode, «فصل ۱ · قسمت ۲» in front. */
@Composable
private fun infoLine(row: DownloadRowUi): String {
    val audio = stringResource(if (row.audio == AudioType.DUBBED) R.string.downloads_audio_dubbed else R.string.downloads_audio_subtitled)
    val quality = if (row.quality > 0) row.quality.toPersianDigits() else null
    val qualityAudio = if (quality != null) stringResource(R.string.downloads_quality_audio, quality, audio) else audio
    return listOfNotNull(row.subtitleLine, qualityAudio).joinToString(" · ")
}

@Composable
private fun statusLine(row: DownloadRowUi): String {
    val sizes = when {
        row.status == RowStatus.COMPLETED -> row.totalBytes?.let { formatBytesFa(it) }
        row.totalBytes != null -> stringResource(
            R.string.downloads_size_progress,
            formatBytesFa(row.bytesDownloaded),
            formatBytesFa(row.totalBytes),
        )
        else -> null
    }
    val status = when (row.status) {
        RowStatus.QUEUED -> stringResource(R.string.downloads_status_queued)
        RowStatus.DOWNLOADING ->
            if (row.percent != null) {
                stringResource(R.string.downloads_status_downloading, row.percent.toPersianDigits())
            } else {
                stringResource(R.string.downloads_status_downloading_unknown)
            }
        RowStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        RowStatus.COMPLETED -> stringResource(R.string.downloads_status_completed)
        RowStatus.FAILED -> stringResource(R.string.downloads_status_failed, stringResource(row.failure.messageRes()))
    }
    return listOfNotNull(status, sizes.takeIf { row.status != RowStatus.FAILED }).joinToString(" · ")
}

@StringRes
private fun DownloadFailure?.messageRes(): Int = when (this) {
    DownloadFailure.NO_SPACE -> R.string.downloads_fail_no_space
    DownloadFailure.NETWORK -> R.string.downloads_fail_network
    DownloadFailure.SOURCE_GONE -> R.string.downloads_fail_gone
    DownloadFailure.UNKNOWN, null -> R.string.downloads_fail_generic
}

private fun statusColor(row: DownloadRowUi) = when (row.status) {
    RowStatus.FAILED -> BolbolColors.Error
    RowStatus.COMPLETED -> BolbolColors.Accent
    else -> BolbolColors.TextSecondary
}

/** Poster from a local file URI (or a cached remote one), decoded at exactly its on-screen size. */
@Composable
private fun Poster(url: String?) {
    val shape = RoundedCornerShape(BolbolSpacing.CardRadius)
    Box(
        Modifier.size(PosterWidth, PosterHeight).clip(shape).background(BolbolColors.SurfaceHigh),
    ) {
        if (!url.isNullOrBlank()) {
            val context = LocalPlatformContext.current
            val density = LocalDensity.current
            val widthPx = with(density) { PosterWidth.roundToPx() }
            val heightPx = with(density) { PosterHeight.roundToPx() }
            val request = remember(url, widthPx, heightPx, context) {
                ImageRequest.Builder(context).data(url).size(widthPx, heightPx).crossfade(false).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

/** «حذف؟»: focus starts on «انصراف»; the red button deletes. Back cancels. */
@Composable
private fun ConfirmDialog(confirm: DownloadsConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    val title: String
    val body: String
    when (confirm) {
        is DownloadsConfirm.RemoveOne -> {
            title = stringResource(R.string.downloads_confirm_one_title)
            body = stringResource(
                R.string.downloads_confirm_one_body,
                listOfNotNull(confirm.titleText, confirm.subtitleLine).joinToString(" · "),
            )
        }
        is DownloadsConfirm.RemoveAll -> {
            title = stringResource(R.string.downloads_confirm_all_title)
            body = stringResource(R.string.downloads_confirm_all_body, confirm.count.toPersianDigits())
        }
    }
    TvDialog(onDismiss = onDismiss) {
        BolbolText(text = title, style = MaterialTheme.typography.titleLarge, color = BolbolColors.TextPrimary, maxLines = 1)
        BolbolText(text = body, style = MaterialTheme.typography.bodyLarge, color = BolbolColors.TextSecondary, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = stringResource(R.string.downloads_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancel),
                style = TvButtonStyle.Secondary,
            )
            TvButton(
                text = stringResource(R.string.downloads_remove),
                onClick = onConfirm,
                icon = BolbolIcons.Delete,
                style = TvButtonStyle.Danger,
            )
        }
    }
}
