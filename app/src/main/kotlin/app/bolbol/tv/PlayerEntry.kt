package app.bolbol.tv

import kotlinx.coroutines.flow.first
import app.bolbol.tv.core.model.DownloadStatus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bolbol.tv.core.designsystem.ErrorState
import app.bolbol.tv.core.designsystem.LoadingState
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.playbackRequest
import app.bolbol.tv.feature.playback.PlayerScreen
import app.bolbol.tv.feature.playback.PlayerViewModel

private sealed interface Resolve {
    data object Loading : Resolve
    data class Ready(val request: PlaybackRequest) : Resolve
    data object Failed : Resolve
}

/**
 * Resolves [route] to a [PlaybackRequest] (details come from the repository's in-memory LRU,
 * so this is instant when arriving from the details screen), then shows the player.
 * Resolving by id instead of passing stream URLs keeps the back stack serializable and
 * picks up fresh links after process death.
 */
@Composable
fun PlayerEntry(
    app: BolbolApp,
    route: PlayerRoute,
    onExit: () -> Unit,
    onPlayNext: (WatchKey) -> Unit,
    onSwitchEpisode: (WatchKey) -> Unit = {},
) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<Resolve>(Resolve.Loading, route, attempt) {
        val key = WatchKey(route.postId, route.season, route.episode)
        val start = route.startPositionMs.takeIf { it >= 0 }
        value = try {
            val details = app.data.catalog.details(route.postId)
            details.playbackRequest(key, start)?.let { Resolve.Ready(it) } ?: Resolve.Failed
        } catch (e: Exception) {
            // No internet (or the title vanished): a saved copy still plays (v1.4-1). No next/previous offline.
            savedRequest(app, key, start)?.let { Resolve.Ready(it) } ?: Resolve.Failed
        }
    }
    when (val s = state) {
        Resolve.Loading -> LoadingState()
        Resolve.Failed -> ErrorState(message = "پخش این مورد ممکن نیست.", onRetry = { attempt++ })
        is Resolve.Ready -> {
            val vm: PlayerViewModel = viewModel(
                key = "player-${route.postId}-${route.season}-${route.episode}",
                factory = PlayerViewModel.factory(
                    request = s.request,
                    playerFactory = app.playerFactory,
                    watch = app.data.watch,
                    prefs = app.data.prefs,
                    downloads = app.downloads,
                ),
            )
            val showPerf by app.appSettings.showPerformance.collectAsState()
            PlayerScreen(
                viewModel = vm,
                onExit = onExit,
                onPlayNext = onPlayNext,
                onSwitchEpisode = onSwitchEpisode,
                showDiagnostics = showPerf,
            )
        }
    }
}

/** A [PlaybackRequest] built from a completed download alone (offline start from «دانلودها»). */
private suspend fun savedRequest(app: BolbolApp, key: WatchKey, start: Long?): PlaybackRequest? =
    runCatching {
        app.downloads.downloadsForTitle(key.postId).first()
            .firstOrNull { it.key == key && it.status == DownloadStatus.COMPLETED }
            ?.let { PlaybackRequest(it.key, it.title, it.subtitleLine, listOf(it.stream), start, next = null) }
    }.getOrNull()
