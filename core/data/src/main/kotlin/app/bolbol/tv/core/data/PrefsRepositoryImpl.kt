package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.PrefsDao
import app.bolbol.tv.core.database.PrefsEntity
import app.bolbol.tv.core.database.SubtitleOffsetEntity
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.SubtitleStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Playback and subtitle preferences on Room. Playback prefs: row postId = 0 is the global default, other rows
 * override it per title. Subtitles: one global [SubtitleStyle] row plus a per-title timing offset (docs/subtitles.md).
 */
internal class PrefsRepositoryImpl(
    private val dao: PrefsDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PrefsRepository {

    override fun globalPrefs(): Flow<PlaybackPrefs> =
        dao.observe(PrefsEntity.GLOBAL_POST_ID).map { it?.toModel() ?: PlaybackPrefs() }

    override suspend fun titlePrefs(postId: Long): PlaybackPrefs? = withContext(io) {
        dao.get(postId)?.toModel()
    }

    override suspend fun saveGlobalPrefs(prefs: PlaybackPrefs) = withContext(io) {
        dao.upsert(prefs.toEntity(PrefsEntity.GLOBAL_POST_ID))
    }

    override suspend fun saveTitlePrefs(postId: Long, prefs: PlaybackPrefs) = withContext(io) {
        require(postId != PrefsEntity.GLOBAL_POST_ID) { "use saveGlobalPrefs for the global row" }
        dao.upsert(prefs.toEntity(postId))
    }

    /** Emits [SubtitleStyle] defaults until the user saves something. */
    override fun subtitleStyle(): Flow<SubtitleStyle> =
        dao.observeSubtitleStyle().map { it?.toModel() ?: SubtitleStyle() }.distinctUntilChanged()

    override suspend fun saveSubtitleStyle(style: SubtitleStyle) = withContext(io) {
        dao.upsertSubtitleStyle(style.toEntity())
    }

    /** 0 when never set. */
    override suspend fun subtitleOffsetMs(postId: Long): Int = withContext(io) {
        normalizeSubtitleOffsetMs(dao.subtitleOffsetMs(postId) ?: 0)
    }

    /** Rounded to 0.5 s steps and clamped to +-SubtitleStyle.OFFSET_MAX_MS (120 s); 0 removes the stored row. */
    override suspend fun saveSubtitleOffsetMs(postId: Long, offsetMs: Int) = withContext(io) {
        val normalized = normalizeSubtitleOffsetMs(offsetMs)
        if (normalized == 0) {
            dao.deleteSubtitleOffset(postId)
        } else {
            dao.upsertSubtitleOffset(SubtitleOffsetEntity(postId, normalized))
        }
    }
}
