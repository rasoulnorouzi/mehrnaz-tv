package app.bolbol.tv.feature.playback.debug

import android.util.Log
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.SubtitleStyle
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.WatchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private const val TAG = "BolbolTest"

/** Process-lifetime in-memory progress store; logs every write (tag BolbolTest). */
internal class InMemoryWatchRepository : WatchRepository {
    private val entries = MutableStateFlow<Map<WatchKey, ContinueWatchingItem>>(emptyMap())

    override fun continueWatching(limit: Int): Flow<List<ContinueWatchingItem>> = entries.map { all ->
        all.values.filter { !it.progress.completed }.sortedByDescending { it.progress.updatedAtMs }.take(limit)
    }

    override suspend fun progress(key: WatchKey): PlaybackProgress? = entries.value[key]?.progress

    override fun progressForTitle(postId: Long): Flow<List<PlaybackProgress>> =
        entries.map { all -> all.values.map { it.progress }.filter { it.key.postId == postId } }

    override suspend fun saveProgress(title: TitleSummary, progress: PlaybackProgress) {
        entries.update { it + (progress.key to ContinueWatchingItem(title, progress)) }
        Log.i(TAG, "saveProgress ${progress.key.asString()} pos=${progress.positionMs} dur=${progress.durationMs} completed=${progress.completed}")
    }

    override suspend fun markWatched(key: WatchKey, watched: Boolean) {
        entries.update { all ->
            val item = all[key] ?: return@update all
            all + (key to item.copy(progress = item.progress.copy(completed = watched)))
        }
    }

    override suspend fun hideFromContinueWatching(postId: Long) {
        entries.update { all -> all.filterKeys { it.postId != postId } }
    }

    fun clear() = entries.update { emptyMap() }
}

/** In-memory prefs; global prefs start at [initial]. Logs every write (tag BolbolTest). */
internal class InMemoryPrefsRepository(initial: PlaybackPrefs) : PrefsRepository {
    private val global = MutableStateFlow(initial)
    private val perTitle = MutableStateFlow<Map<Long, PlaybackPrefs>>(emptyMap())

    override fun globalPrefs(): Flow<PlaybackPrefs> = global

    override suspend fun titlePrefs(postId: Long): PlaybackPrefs? = perTitle.value[postId]

    override suspend fun saveGlobalPrefs(prefs: PlaybackPrefs) {
        global.value = prefs
        Log.i(TAG, "saveGlobalPrefs $prefs")
    }

    override suspend fun saveTitlePrefs(postId: Long, prefs: PlaybackPrefs) {
        perTitle.update { it + (postId to prefs) }
        Log.i(TAG, "saveTitlePrefs $postId $prefs")
    }

    private val style = MutableStateFlow(SubtitleStyle())
    private val offsets = MutableStateFlow<Map<Long, Int>>(emptyMap())

    override fun subtitleStyle(): Flow<SubtitleStyle> = style

    override suspend fun saveSubtitleStyle(style: SubtitleStyle) {
        this.style.value = style
        Log.i(TAG, "saveSubtitleStyle $style")
    }

    override suspend fun subtitleOffsetMs(postId: Long): Int = offsets.value[postId] ?: 0

    override suspend fun saveSubtitleOffsetMs(postId: Long, offsetMs: Int) {
        offsets.update { it + (postId to offsetMs) }
        Log.i(TAG, "saveSubtitleOffsetMs $postId $offsetMs")
    }

    fun reset(prefs: PlaybackPrefs) {
        global.value = prefs
        perTitle.value = emptyMap()
        style.value = SubtitleStyle()
        offsets.value = emptyMap()
    }
}
