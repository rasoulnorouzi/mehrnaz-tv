package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.MyListDao
import app.bolbol.tv.core.database.MyListEntity
import app.bolbol.tv.core.network.AccountRemoteDataSource
import app.bolbol.tv.core.network.LoginOutcome
import app.bolbol.tv.core.network.RemoteFavorite
import app.bolbol.tv.core.network.RemoteFavorites
import app.bolbol.tv.core.network.RemoteProfile
import app.bolbol.tv.core.network.RemoteProgressItem
import app.bolbol.tv.core.network.RemoteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [SessionStorage]; [saves] counts writes so tests can see what was persisted. */
internal class InMemorySessionStorage(var stored: StoredSession? = null) : SessionStorage {
    var saves = 0
    var loads = 0
    override fun load(): StoredSession? {
        loads++
        return stored
    }

    override fun save(session: StoredSession) {
        saves++
        stored = session
    }

    override fun clear() {
        stored = null
    }
}

/** In-memory [MyListDao] with the same ordering and guards as the SQL. */
internal class FakeMyListDao : MyListDao() {
    val rows = MutableStateFlow<Map<Long, MyListEntity>>(emptyMap())

    fun put(entity: MyListEntity) {
        rows.value = rows.value + (entity.postId to entity)
    }

    fun pending(postId: Long): Int? = rows.value[postId]?.pendingSync

    override fun observeVisible(): Flow<List<MyListEntity>> = rows.map { map ->
        map.values.filter { it.pendingSync != MyListEntity.PENDING_REMOVE }
            .sortedWith(compareByDescending<MyListEntity> { it.addedAtMs }.thenByDescending { it.postId })
    }

    override fun observeContains(postId: Long): Flow<Boolean> =
        rows.map { map -> map[postId]?.let { it.pendingSync != MyListEntity.PENDING_REMOVE } ?: false }

    override suspend fun all(): List<MyListEntity> = rows.value.values.toList()
    override suspend fun get(postId: Long): MyListEntity? = rows.value[postId]
    override suspend fun pendingCount(): Int = rows.value.values.count { it.pendingSync != MyListEntity.PENDING_NONE }
    override suspend fun upsert(entity: MyListEntity) = put(entity)

    override suspend fun insertIfAbsent(entity: MyListEntity): Long {
        if (entity.postId in rows.value) return -1
        put(entity)
        return entity.postId
    }

    override suspend fun markRemoved(postId: Long) {
        rows.value[postId]?.let { put(it.copy(pendingSync = MyListEntity.PENDING_REMOVE)) }
    }

    override suspend fun delete(postId: Long) {
        rows.value = rows.value - postId
    }

    override suspend fun clearPending(postId: Long, expected: Int) {
        rows.value[postId]?.takeIf { it.pendingSync == expected }?.let { put(it.copy(pendingSync = MyListEntity.PENDING_NONE)) }
    }

    override suspend fun deleteIfPending(postId: Long, expected: Int) {
        if (rows.value[postId]?.pendingSync == expected) delete(postId)
    }

    override suspend fun markSyncedAsPendingAdd() {
        rows.value = rows.value.mapValues { (_, row) ->
            if (row.pendingSync == MyListEntity.PENDING_NONE) row.copy(pendingSync = MyListEntity.PENDING_ADD) else row
        }
    }
}

/**
 * A tiny fake of the site. [favorites] is the server-side favourites list in server order; [toggleFavorite] flips
 * membership like the real route (unless [ignoreToggles]). Every call is recorded in [calls].
 */
internal class FakeAccountRemote : AccountRemoteDataSource {
    val calls = mutableListOf<String>()

    var loginOutcome: LoginOutcome = LoginOutcome.Success("tok", RemoteProfile("Ali", 12))
    var lastLogin: Pair<String, String>? = null

    var profile: RemoteResult<RemoteProfile> = RemoteResult.Ok(RemoteProfile("Ali Server", 11))

    /** Server favourites with optional summaries. */
    val favorites = mutableListOf<RemoteFavorite>()
    var favoritesComplete = true
    var favoritesFailure: RemoteResult<Nothing>? = null
    var toggleFailure: RemoteResult<Nothing>? = null
    var ignoreToggles = false
    val toggled = mutableListOf<Long>()

    var progress: RemoteResult<List<RemoteProgressItem>> = RemoteResult.Ok(emptyList())
    var saveFailure: RemoteResult<Nothing>? = null
    class SavedProgress(val postId: Long, val seconds: Int, val season: Int, val episode: Int)
    val saved = mutableListOf<SavedProgress>()

    var throwOnFavorites: Throwable? = null

    fun serverHas(vararg ids: Long) {
        favorites.clear()
        ids.forEach { favorites += RemoteFavorite(it, summary(it)) }
    }

    val serverIds: List<Long> get() = favorites.map { it.id }
    val favoritesReads: Int get() = calls.count { it == "favorites" }

    override suspend fun login(username: String, password: String): LoginOutcome {
        calls += "login"
        lastLogin = username to password
        return loginOutcome
    }

    override suspend fun profile(): RemoteResult<RemoteProfile> {
        calls += "profile"
        return profile
    }

    override suspend fun favorites(): RemoteResult<RemoteFavorites> {
        calls += "favorites"
        throwOnFavorites?.let { throw it }
        favoritesFailure?.let { return it }
        return RemoteResult.Ok(RemoteFavorites(favorites.toList(), favoritesComplete))
    }

    override suspend fun toggleFavorite(postId: Long): RemoteResult<Unit> {
        calls += "toggle:$postId"
        toggleFailure?.let { return it }
        toggled += postId
        if (!ignoreToggles) {
            if (favorites.any { it.id == postId }) favorites.removeAll { it.id == postId } else favorites.add(0, RemoteFavorite(postId, summary(postId)))
        }
        return RemoteResult.Ok(Unit)
    }

    override suspend fun progressList(): RemoteResult<List<RemoteProgressItem>> {
        calls += "progressList"
        return progress
    }

    override suspend fun saveProgress(postId: Long, positionSeconds: Int, season: Int, episode: Int): RemoteResult<Unit> {
        calls += "saveProgress"
        saveFailure?.let { return it }
        saved += SavedProgress(postId, positionSeconds, season, episode)
        return RemoteResult.Ok(Unit)
    }
}

internal fun myListRow(id: Long, pending: Int = MyListEntity.PENDING_NONE, addedAtMs: Long = id) =
    summary(id).toMyList(addedAtMs, pending)
