package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.MyListDao
import app.bolbol.tv.core.database.MyListEntity
import app.bolbol.tv.core.model.AccountRepository
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.model.LoginResult
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchRepository
import app.bolbol.tv.core.network.AccountRemoteDataSource
import app.bolbol.tv.core.network.LoginOutcome
import app.bolbol.tv.core.network.RemoteFavorite
import app.bolbol.tv.core.network.RemoteProgressItem
import app.bolbol.tv.core.network.RemoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Optional Bolboljan account (docs/auth.md section 0) on top of Room, the shared OkHttp client and the session store.
 *
 * **My List** is local-first: every change is written to Room at once and the UI never waits for the network. When
 * signed in, the list is mirrored to the site's favourites by [syncMyList]: it reads the server list, applies the
 * pure [planMerge] rules, toggles what is pending and reads the list again to confirm (the server route toggles).
 * Everything is best effort: no method throws, any failure leaves the local state and the pending flags untouched, and
 * a 401/403 signs the user out (the local list stays).
 *
 * **Progress** ([pushProgress] / [pullProgress]) mirrors the server's continue-watching list into local watch progress.
 * The manager wires the calls (player exit, app start); they are no-ops when signed out.
 */
internal class AccountRepositoryImpl(
    private val remote: AccountRemoteDataSource,
    private val dao: MyListDao,
    private val session: SessionStore,
    private val watch: WatchRepository,
    /** Fetches a title's summary when the server sent only an id (details call). Null when unavailable. */
    private val resolveSummary: suspend (Long) -> TitleSummary?,
    /** Runs the debounced push after local changes. */
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pushDebounceMs: Long = PUSH_DEBOUNCE_MS,
) : AccountRepository {

    private val syncMutex = Mutex()
    @Volatile private var lastFullSyncAtMs = 0L
    @Volatile private var lastProfileAtMs = 0L

    /** Conflated: a burst of local changes becomes one (or two) syncs. */
    private val pushTrigger = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (trigger in pushTrigger) {
                delay(pushDebounceMs)
                syncMyList()
            }
        }
    }

    // --- account ----------------------------------------------------------------------------------------

    override val state: Flow<AccountState> = flow {
        withContext(io) { session.ensureLoaded() }
        emitAll(session.state)
    }

    /** VIP days left of the signed-in user (null: none), for the account screen. */
    val vipDaysLeft: Flow<Int?> = flow {
        withContext(io) { session.ensureLoaded() }
        emitAll(session.vipDaysLeft)
    }

    /** The non-secret profile of the signed-in user (null when signed out), for the Profile screen. */
    val profile: Flow<AccountProfileDetails?> = flow {
        withContext(io) { session.ensureLoaded() }
        emitAll(session.profile)
    }

    override suspend fun login(username: String, password: String): LoginResult {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) return LoginResult.Failed(MSG_FIELDS_REQUIRED)
        return when (val outcome = remote.login(user, password)) {
            is LoginOutcome.Success -> try {
                withContext(io) {
                    session.signIn(
                        outcome.token,
                        outcome.profile.displayName ?: user,
                        outcome.profile.vipDaysLeft,
                        loginName = user,
                    )
                    // Every synced row becomes "to add": the first sync after a login is a union and deletes nothing.
                    dao.markSyncedAsPendingAdd()
                }
                lastFullSyncAtMs = 0
                lastProfileAtMs = clock()
                LoginResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LoginResult.Failed(MSG_STORAGE)
            }

            LoginOutcome.WrongCredentials -> LoginResult.WrongCredentials
            is LoginOutcome.Failed -> LoginResult.Failed(outcome.message)
        }
    }

    /** Forgets the token. The local My List stays (docs/auth.md section 8.1). */
    override suspend fun logout() {
        withContext(io) { session.signOut() }
        lastFullSyncAtMs = 0
    }

    // --- My List -------------------------------------------------------------------------------------------

    override fun myList(): Flow<List<TitleSummary>> = dao.observeVisible().map { rows -> rows.map { it.toSummary() } }

    override fun isInMyList(postId: Long): Flow<Boolean> = dao.observeContains(postId)

    override suspend fun setInMyList(title: TitleSummary, inList: Boolean) {
        withContext(io) {
            val existing = dao.get(title.id)
            if (inList) {
                if (existing != null && existing.pendingSync != MyListEntity.PENDING_REMOVE) return@withContext
                dao.upsert(title.toMyList(addedAtMs = clock(), pendingSync = MyListEntity.PENDING_ADD))
            } else {
                if (existing == null || existing.pendingSync == MyListEntity.PENDING_REMOVE) return@withContext
                // An add that never reached the server (or any row while signed out and never synced) just vanishes;
                // a synced row keeps a hidden tombstone so the removal reaches the server at the next login/sync.
                if (existing.pendingSync == MyListEntity.PENDING_ADD && !session.isLoggedIn) {
                    dao.delete(title.id)
                } else {
                    dao.markRemoved(title.id)
                }
            }
        }
        if (session.isLoggedIn) pushTrigger.trySend(Unit)
    }

    /**
     * Two-way merge with the site's favourites; a no-op when signed out. Runs at most once per
     * [MIN_SYNC_INTERVAL_MS] unless local changes are waiting, so calling it on every app start or screen open is cheap.
     * Never throws.
     */
    override suspend fun syncMyList() {
        try {
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort by contract.
        }
    }

    private suspend fun sync() {
        if (!session.isLoggedIn) return
        syncMutex.withLock {
            if (!session.isLoggedIn) return
            val now = clock()
            val hasPending = withContext(io) { dao.pendingCount() } > 0
            if (!hasPending && lastFullSyncAtMs != 0L && now - lastFullSyncAtMs < MIN_SYNC_INTERVAL_MS) return

            if (!refreshProfileIfStale(now)) return

            val server = when (val result = remote.favorites()) {
                is RemoteResult.Ok -> result.value
                RemoteResult.Unauthorized -> return expire()
                RemoteResult.Failed -> return
            }
            val plan = withContext(io) {
                val plan = planMerge(dao.all(), server.items.map { it.id }, server.complete)
                applyLocalChanges(plan, server.items.associateBy { it.id })
                plan
            }

            // Push what is pending. The server toggles, so each id is toggled once, only after we saw its state above.
            var pushed = false
            pushes@ for ((id, expected) in plan.pushAdd.map { it to MyListEntity.PENDING_ADD } +
                plan.pushRemove.map { it to MyListEntity.PENDING_REMOVE }) {
                // The user may have changed this item since the plan was made.
                if (withContext(io) { dao.get(id)?.pendingSync } != expected) continue
                when (remote.toggleFavorite(id)) {
                    is RemoteResult.Ok -> pushed = true
                    RemoteResult.Unauthorized -> return expire()
                    RemoteResult.Failed -> break@pushes
                }
            }
            if (pushed) confirm(plan)

            if (withContext(io) { dao.pendingCount() } == 0) lastFullSyncAtMs = clock()
        }
    }

    /** Deletes, clears and inserts that need no network. Runs on [io]. */
    private suspend fun applyLocalChanges(plan: MergePlan, serverItems: Map<Long, RemoteFavorite>) {
        plan.deleteSynced.forEach { dao.deleteIfPending(it, MyListEntity.PENDING_NONE) }
        plan.clearAdd.forEach { dao.clearPending(it, MyListEntity.PENDING_ADD) }
        plan.dropRemoved.forEach { dao.deleteIfPending(it, MyListEntity.PENDING_REMOVE) }
        val now = clock()
        val budget = ResolveBudget()
        plan.insertFromServer.forEachIndexed { index, id ->
            val summary = serverItems[id]?.summary ?: budget.resolve(id)
            // Server order is kept (assumed newest first); an id we cannot paint yet is retried at the next sync.
            if (summary != null) dao.insertIfAbsent(summary.toMyList(addedAtMs = now - index, pendingSync = MyListEntity.PENDING_NONE))
        }
    }

    /** Reads the server list again and settles the pushed items. Unconfirmed items stay pending for the next sync. */
    private suspend fun confirm(plan: MergePlan) {
        val after = (remote.favorites() as? RemoteResult.Ok)?.value ?: return
        val ids = after.items.mapTo(HashSet()) { it.id }
        withContext(io) {
            plan.pushAdd.filter { it in ids }.forEach { dao.clearPending(it, MyListEntity.PENDING_ADD) }
            if (after.complete) plan.pushRemove.filter { it !in ids }.forEach { dao.deleteIfPending(it, MyListEntity.PENDING_REMOVE) }
        }
    }

    /** Refreshes name and VIP days at most once an hour. Returns false when the session turned out to be expired. */
    private suspend fun refreshProfileIfStale(now: Long): Boolean {
        if (now - lastProfileAtMs < PROFILE_REFRESH_MS) return true
        when (val result = remote.profile()) {
            is RemoteResult.Ok -> {
                lastProfileAtMs = now
                withContext(io) { session.updateProfile(result.value.displayName, result.value.vipDaysLeft) }
            }
            RemoteResult.Unauthorized -> {
                expire()
                return false
            }
            RemoteResult.Failed -> Unit
        }
        return true
    }

    /** A 401/403 with a token: the token is no longer valid. The user signs in again; the local list stays. */
    private suspend fun expire() {
        withContext(io) { session.signOut() }
        lastFullSyncAtMs = 0
    }

    // --- watch progress ("continue watching" on the server) ----------------------------------------------------------

    /**
     * Sends the position of [progress] to the server (what the official app does when the player closes). A no-op when
     * signed out or when less than a second was watched. Never throws.
     */
    suspend fun pushProgress(progress: PlaybackProgress) {
        try {
            if (!session.isLoggedIn || progress.positionMs < MIN_PUSH_POSITION_MS) return
            val seconds = (progress.positionMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val key = progress.key
            if (remote.saveProgress(key.postId, seconds, key.season, key.episode) == RemoteResult.Unauthorized) expire()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /**
     * Fills the local watch progress from the server's list: an item is taken when it is **missing locally**, or when the
     * local one is not completed and the server's position is further than the local one by more than
     * [PULL_MARGIN_MS] (the server sends no timestamp, so "further" is the only signal). A server item never lowers or
     * completes local progress. Taken items are saved through [WatchRepository.saveProgress]; as for any watching,
     * that puts the title back into Continue Watching. A no-op when signed out. Never throws.
     */
    suspend fun pullProgress() {
        try {
            if (!session.isLoggedIn) return
            val items = when (val result = remote.progressList()) {
                is RemoteResult.Ok -> result.value
                RemoteResult.Unauthorized -> return expire()
                RemoteResult.Failed -> return
            }
            val now = clock()
            val budget = ResolveBudget()
            items.forEachIndexed { index, item ->
                val local = watch.progress(item.key)
                if (!shouldTakeServerProgress(local, item)) return@forEachIndexed
                val title = item.summary ?: budget.resolve(item.key.postId) ?: return@forEachIndexed
                watch.saveProgress(
                    title,
                    PlaybackProgress(
                        key = item.key,
                        positionMs = item.positionMs,
                        durationMs = if (item.durationMs > 0) item.durationMs else local?.durationMs ?: 0,
                        // Server order (assumed newest first) decides the order of what we add.
                        updatedAtMs = now - index,
                        completed = false,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /** Limits the details calls made to paint server items that came without a title. */
    private inner class ResolveBudget {
        private var used = 0

        suspend fun resolve(id: Long): TitleSummary? {
            if (used >= MAX_RESOLVE_PER_SYNC) return null
            used++
            return resolveSummary(id)
        }
    }

    companion object {
        /** Wait this long after a local change so a burst of toggles becomes one sync. */
        const val PUSH_DEBOUNCE_MS = 800L

        /** An automatic sync is skipped when the last complete one was more recent and nothing is pending. */
        const val MIN_SYNC_INTERVAL_MS = 10 * 60 * 1000L

        const val PROFILE_REFRESH_MS = 60 * 60 * 1000L

        /** Details calls per sync to paint server items that came without a title (sequential, never a crawl). */
        const val MAX_RESOLVE_PER_SYNC = 12

        const val MIN_PUSH_POSITION_MS = 1_000L

        /** A server position must beat the local one by more than this to replace it. */
        const val PULL_MARGIN_MS = 5_000L

        const val MSG_FIELDS_REQUIRED = "نام کاربری و رمز عبور را وارد کنید."
        const val MSG_STORAGE = "ذخیرهٔ اطلاعات ورود ممکن نشد؛ دوباره تلاش کنید."

        /** The pull rule as a pure function (unit-tested). */
        internal fun shouldTakeServerProgress(local: PlaybackProgress?, server: RemoteProgressItem): Boolean = when {
            server.positionMs < PULL_MARGIN_MS -> false
            local == null -> true
            local.completed -> false
            else -> server.positionMs > local.positionMs + PULL_MARGIN_MS
        }
    }
}
