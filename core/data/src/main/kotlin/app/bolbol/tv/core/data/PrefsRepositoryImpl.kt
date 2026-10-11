package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.PrefsDao
import app.bolbol.tv.core.database.PrefsEntity
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PrefsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Playback preferences on Room: row postId = 0 is the global default, other rows override it per title. */
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
}
