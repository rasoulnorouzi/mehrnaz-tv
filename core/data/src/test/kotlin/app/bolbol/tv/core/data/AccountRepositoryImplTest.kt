package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.MyListEntity.Companion.PENDING_ADD
import app.bolbol.tv.core.database.MyListEntity.Companion.PENDING_NONE
import app.bolbol.tv.core.database.MyListEntity.Companion.PENDING_REMOVE
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.model.LoginResult
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.network.LoginOutcome
import app.bolbol.tv.core.network.RemoteFavorite
import app.bolbol.tv.core.network.RemoteProfile
import app.bolbol.tv.core.network.RemoteProgressItem
import app.bolbol.tv.core.network.RemoteResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryImplTest {

    private class Fixture(testScope: TestScope, signedIn: Boolean = false) {
        val remote = FakeAccountRemote()
        val dao = FakeMyListDao()
        val storage = InMemorySessionStorage(if (signedIn) StoredSession("tok", "Ali", null) else null)
        val session = SessionStore(storage)
        val watchDao = FakeWatchDao()
        val watch = WatchRepositoryImpl(watchDao, UnconfinedTestDispatcher(testScope.testScheduler))
        var now = 1_000_000_000L
        val resolved = mutableListOf<Long>()
        var resolver: (Long) -> TitleSummary? = { id -> summary(id) }
        val repo = AccountRepositoryImpl(
            remote = remote,
            dao = dao,
            session = session,
            watch = watch,
            resolveSummary = { id ->
                resolved += id
                resolver(id)
            },
            scope = testScope.backgroundScope,
            clock = { now },
            io = UnconfinedTestDispatcher(testScope.testScheduler),
        )

        fun visibleIds() = dao.rows.value.values.filter { it.pendingSync != PENDING_REMOVE }.map { it.postId }.toSet()
    }

    // --- account --------------------------------------------------------------------------------------

    @Test
    fun `login success stores the session and flips the state`() = runTest {
        val f = Fixture(this)
        assertEquals(AccountState(false), f.repo.state.first())
        assertEquals(LoginResult.Success, f.repo.login("  ali  ", "secret pw"))
        assertEquals("ali" to "secret pw", f.remote.lastLogin) // trimmed username, password untouched
        assertEquals(AccountState(true, "Ali"), f.repo.state.first())
        assertEquals("tok", f.session.token())
        assertEquals("tok", f.storage.stored!!.token)
        assertEquals(12, f.repo.vipDaysLeft.first())
        // the password is stored nowhere
        assertFalse(f.storage.stored.toString().contains("secret pw"))
    }

    @Test
    fun `the profile follows the session and never carries the token`() = runTest {
        val f = Fixture(this)
        assertNull(f.repo.profile.first())
        f.repo.login("ali", "pw")
        val profile = f.repo.profile.first()!!
        assertEquals(AccountProfileDetails("ali", null, "Ali", 12, null), profile)
        assertFalse(profile.toString().contains("tok"))
        f.repo.logout()
        assertNull(f.repo.profile.first())
    }

    @Test
    fun `login falls back to the typed username when the server sends no name`() = runTest {
        val f = Fixture(this)
        f.remote.loginOutcome = LoginOutcome.Success("tok", RemoteProfile(null, null))
        f.repo.login("ali@example.com", "pw")
        assertEquals(AccountState(true, "ali@example.com"), f.repo.state.first())
        assertNull(f.repo.vipDaysLeft.first())
    }

    @Test
    fun `wrong credentials and network failures leave the user signed out`() = runTest {
        val f = Fixture(this)
        f.remote.loginOutcome = LoginOutcome.WrongCredentials
        assertEquals(LoginResult.WrongCredentials, f.repo.login("ali", "bad"))
        f.remote.loginOutcome = LoginOutcome.Failed("سرور پاسخ نمی‌دهد")
        assertEquals(LoginResult.Failed("سرور پاسخ نمی‌دهد"), f.repo.login("ali", "pw"))
        assertEquals(AccountState(false), f.repo.state.first())
        assertNull(f.storage.stored)
    }

    @Test
    fun `blank fields never reach the server`() = runTest {
        val f = Fixture(this)
        assertTrue(f.repo.login("  ", "pw") is LoginResult.Failed)
        assertTrue(f.repo.login("ali", "") is LoginResult.Failed)
        assertTrue(f.remote.calls.isEmpty())
    }

    @Test
    fun `logout forgets the token but keeps the list`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1))
        f.repo.logout()
        assertEquals(AccountState(false), f.repo.state.first())
        assertNull(f.session.token())
        assertNull(f.storage.stored)
        assertEquals(setOf(1L), f.visibleIds())
    }

    @Test
    fun `a stored session is restored on start`() = runTest {
        val f = Fixture(this, signedIn = true)
        assertEquals(AccountState(true, "Ali"), f.repo.state.first())
    }

    @Test
    fun `login turns synced rows into pending adds so the first sync is a union`() = runTest {
        val f = Fixture(this)
        f.dao.put(myListRow(1, PENDING_NONE)) // synced earlier, then logged out
        f.dao.put(myListRow(2, PENDING_REMOVE)) // removed while signed out
        f.repo.login("ali", "pw")
        assertEquals(PENDING_ADD, f.dao.pending(1))
        assertEquals(PENDING_REMOVE, f.dao.pending(2))
    }

    // --- My List, local first -----------------------------------------------------------------------------

    @Test
    fun `adding and removing works signed out and never touches the network`() = runTest {
        val f = Fixture(this)
        f.repo.setInMyList(summary(1), true)
        f.now += 10
        f.repo.setInMyList(summary(2), true)
        assertEquals(listOf(2L, 1L), f.repo.myList().first().map { it.id }) // newest first
        assertTrue(f.repo.isInMyList(1).first())
        assertEquals(PENDING_ADD, f.dao.pending(1))

        f.repo.setInMyList(summary(1), false) // an add that never synced simply vanishes
        assertNull(f.dao.pending(1))
        assertFalse(f.repo.isInMyList(1).first())
        advanceTimeBy(10_000)
        assertTrue(f.remote.calls.isEmpty())
    }

    @Test
    fun `adding twice keeps the original position`() = runTest {
        val f = Fixture(this)
        f.repo.setInMyList(summary(1), true)
        f.now += 100
        f.repo.setInMyList(summary(1), true)
        assertEquals(1_000_000_000L, f.dao.rows.value.getValue(1).addedAtMs)
    }

    @Test
    fun `removing a synced row leaves a hidden tombstone and re-adding restores it`() = runTest {
        val f = Fixture(this)
        f.dao.put(myListRow(1, PENDING_NONE))
        f.repo.setInMyList(summary(1), false)
        assertEquals(PENDING_REMOVE, f.dao.pending(1))
        assertFalse(f.repo.isInMyList(1).first())
        assertTrue(f.repo.myList().first().isEmpty())

        f.repo.setInMyList(summary(1), true)
        assertEquals(PENDING_ADD, f.dao.pending(1))
        assertTrue(f.repo.isInMyList(1).first())
    }

    @Test
    fun `removing something that is not in the list does nothing`() = runTest {
        val f = Fixture(this)
        f.repo.setInMyList(summary(9), false)
        assertTrue(f.dao.rows.value.isEmpty())
    }

    // --- sync: guards -----------------------------------------------------------------------------------

    @Test
    fun `sync is a no-op signed out`() = runTest {
        val f = Fixture(this)
        f.dao.put(myListRow(1, PENDING_ADD))
        f.repo.syncMyList()
        assertTrue(f.remote.calls.isEmpty())
        assertEquals(PENDING_ADD, f.dao.pending(1))
    }

    @Test
    fun `sync never throws`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.throwOnFavorites = IllegalStateException("boom")
        f.repo.syncMyList()
        assertTrue(f.repo.state.first().loggedIn) // still signed in
    }

    @Test
    fun `a failed read changes nothing`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_NONE))
        f.dao.put(myListRow(2, PENDING_ADD))
        f.remote.favoritesFailure = RemoteResult.Failed
        f.repo.syncMyList()
        assertEquals(PENDING_NONE, f.dao.pending(1)) // not deleted on a failed read
        assertEquals(PENDING_ADD, f.dao.pending(2))
        assertTrue(f.remote.toggled.isEmpty())
    }

    @Test
    fun `an expired session signs out and keeps the list`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_NONE))
        f.remote.favoritesFailure = RemoteResult.Unauthorized
        f.repo.syncMyList()
        assertEquals(AccountState(false), f.repo.state.first())
        assertNull(f.storage.stored)
        assertEquals(setOf(1L), f.visibleIds())
    }

    @Test
    fun `an expired profile call also signs out`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.profile = RemoteResult.Unauthorized
        f.repo.syncMyList()
        assertEquals(AccountState(false), f.repo.state.first())
        assertFalse("no list calls after the session expired", "favorites" in f.remote.calls)
    }

    // --- sync: merge rules ------------------------------------------------------------------------------

    @Test
    fun `first sync after login is a union and deletes nothing`() = runTest {
        val f = Fixture(this)
        f.dao.put(myListRow(1, PENDING_NONE)) // local only, was synced to another account long ago
        f.remote.serverHas(2, 3)
        f.repo.login("ali", "pw")
        f.repo.syncMyList()

        assertEquals(setOf(1L, 2L, 3L), f.visibleIds())
        assertEquals(setOf(1L, 2L, 3L), f.remote.serverIds.toSet()) // 1 was pushed
        assertEquals(listOf(1L), f.remote.toggled)
        assertTrue(f.dao.rows.value.values.all { it.pendingSync == PENDING_NONE })
    }

    @Test
    fun `server removals are respected only for rows without pending changes`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_NONE)) // removed on the site: goes
        f.dao.put(myListRow(2, PENDING_NONE)) // still on the site: stays
        f.dao.put(myListRow(3, PENDING_ADD)) // added locally, not on the site yet: stays and is pushed
        f.dao.put(myListRow(4, PENDING_REMOVE)) // removed locally, already gone on the site: tombstone dropped
        f.remote.serverHas(2)
        f.repo.syncMyList()

        assertEquals(setOf(2L, 3L), f.visibleIds())
        assertNull(f.dao.pending(1))
        assertNull(f.dao.pending(4))
        assertEquals(listOf(3L), f.remote.toggled)
        assertEquals(PENDING_NONE, f.dao.pending(3))
    }

    @Test
    fun `a local add that is already on the server is not toggled`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_ADD))
        f.remote.serverHas(1)
        f.repo.syncMyList()
        assertTrue("toggling would remove it", f.remote.toggled.isEmpty())
        assertEquals(PENDING_NONE, f.dao.pending(1))
        assertEquals(listOf(1L), f.remote.serverIds)
    }

    @Test
    fun `a local removal is pushed once and confirmed`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_REMOVE))
        f.dao.put(myListRow(2, PENDING_NONE))
        f.remote.serverHas(1, 2)
        f.repo.syncMyList()
        assertEquals(listOf(1L), f.remote.toggled)
        assertEquals(listOf(2L), f.remote.serverIds)
        assertNull(f.dao.pending(1))
        assertEquals(setOf(2L), f.visibleIds())
    }

    @Test
    fun `server only items are inserted in server order with their summaries`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.serverHas(30, 20, 10)
        f.repo.syncMyList()
        assertEquals(listOf(30L, 20L, 10L), f.repo.myList().first().map { it.id })
        assertEquals("Title 30", f.repo.myList().first().first().title)
        assertTrue(f.resolved.isEmpty())
    }

    @Test
    fun `server items without a summary are resolved through details, a few at a time`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.favorites += (1L..20L).map { RemoteFavorite(it, null) }
        f.repo.syncMyList()
        assertEquals(AccountRepositoryImpl.MAX_RESOLVE_PER_SYNC, f.resolved.size)
        assertEquals(AccountRepositoryImpl.MAX_RESOLVE_PER_SYNC, f.visibleIds().size)

        // The rest follows at the next sync (pending nothing, so move past the throttle).
        f.now += AccountRepositoryImpl.MIN_SYNC_INTERVAL_MS + 1
        f.repo.syncMyList()
        assertEquals(20, f.visibleIds().size)
    }

    @Test
    fun `an unresolvable server item is skipped and retried later`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.favorites += RemoteFavorite(7, null)
        f.resolver = { null }
        f.repo.syncMyList()
        assertTrue(f.visibleIds().isEmpty())
        f.resolver = { id -> summary(id) }
        f.now += AccountRepositoryImpl.MIN_SYNC_INTERVAL_MS + 1
        f.repo.syncMyList()
        assertEquals(setOf(7L), f.visibleIds())
    }

    @Test
    fun `an incomplete server list deletes nothing and pushes no adds`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_NONE)) // might sit on an unread page
        f.dao.put(myListRow(2, PENDING_ADD)) // toggling could remove it from the server
        f.dao.put(myListRow(3, PENDING_REMOVE)) // gone from the read pages, but unproven
        f.dao.put(myListRow(4, PENDING_REMOVE)) // seen on the server: safe to push
        f.remote.serverHas(4, 5)
        f.remote.favoritesComplete = false
        f.repo.syncMyList()
        assertEquals(PENDING_NONE, f.dao.pending(1))
        assertEquals(PENDING_ADD, f.dao.pending(2))
        assertEquals(PENDING_REMOVE, f.dao.pending(3))
        assertEquals(listOf(4L), f.remote.toggled)
        assertEquals(PENDING_REMOVE, f.dao.pending(4)) // confirmation is also incomplete: stays until proven
        assertEquals(setOf(1L, 2L, 5L), f.visibleIds())
    }

    @Test
    fun `a failed push keeps the item pending and stops pushing`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_ADD))
        f.dao.put(myListRow(2, PENDING_ADD))
        f.remote.toggleFailure = RemoteResult.Failed
        f.repo.syncMyList()
        assertEquals(PENDING_ADD, f.dao.pending(1))
        assertEquals(PENDING_ADD, f.dao.pending(2))
        assertEquals(1, f.remote.calls.count { it.startsWith("toggle") }) // no hammering after the first failure
    }

    @Test
    fun `a push the server ignored stays pending and is never toggled twice in one sync`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_ADD))
        f.remote.ignoreToggles = true
        f.repo.syncMyList()
        assertEquals(PENDING_ADD, f.dao.pending(1))
        assertEquals(listOf(1L), f.remote.toggled)
    }

    @Test
    fun `an item changed during the sync is not overwritten`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.dao.put(myListRow(1, PENDING_ADD))
        f.remote.serverHas()
        // The user removes the item while the push request is in flight.
        val original = f.remote
        f.remote.toggleFailure = null
        val raced = object : app.bolbol.tv.core.network.AccountRemoteDataSource by original {
            override suspend fun toggleFavorite(postId: Long): RemoteResult<Unit> {
                f.repo.setInMyList(summary(postId), false)
                return original.toggleFavorite(postId)
            }
        }
        val repo = AccountRepositoryImpl(raced, f.dao, f.session, f.watch, { null }, backgroundScope, { f.now }, UnconfinedTestDispatcher(testScheduler))
        repo.syncMyList()
        // The add reached the server, but the user's removal is what the list shows; it is now a pending removal.
        assertEquals(PENDING_REMOVE, f.dao.pending(1))
    }

    // --- sync: throttle and triggers ---------------------------------------------------------------------------

    @Test
    fun `automatic syncs are throttled unless something is pending`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.serverHas(1)
        f.repo.syncMyList()
        assertEquals(1, f.remote.favoritesReads)

        f.now += 60_000
        f.repo.syncMyList() // one minute later, nothing pending
        assertEquals(1, f.remote.favoritesReads)

        f.dao.put(myListRow(2, PENDING_ADD))
        f.repo.syncMyList() // pending: runs at once
        assertTrue(f.remote.favoritesReads > 1)

        val before = f.remote.favoritesReads
        f.now += AccountRepositoryImpl.MIN_SYNC_INTERVAL_MS + 1
        f.repo.syncMyList()
        assertEquals(before + 1, f.remote.favoritesReads)
    }

    @Test
    fun `login resets the throttle`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.repo.syncMyList()
        val reads = f.remote.favoritesReads
        f.repo.logout()
        f.repo.login("ali", "pw")
        f.repo.syncMyList()
        assertEquals(reads + 1, f.remote.favoritesReads)
    }

    @Test
    fun `a burst of local changes becomes one debounced push`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.serverHas()
        f.repo.setInMyList(summary(1), true)
        f.repo.setInMyList(summary(2), true)
        f.repo.setInMyList(summary(3), true)
        runCurrent()
        assertTrue("nothing is sent before the debounce", f.remote.calls.isEmpty())

        advanceTimeBy(AccountRepositoryImpl.PUSH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(setOf(1L, 2L, 3L), f.remote.toggled.toSet())
        assertEquals(3, f.remote.toggled.size)
        assertTrue(f.dao.rows.value.values.all { it.pendingSync == PENDING_NONE })
    }

    @Test
    fun `signed out changes schedule nothing`() = runTest {
        val f = Fixture(this)
        f.repo.setInMyList(summary(1), true)
        advanceTimeBy(AccountRepositoryImpl.PUSH_DEBOUNCE_MS * 5)
        runCurrent()
        assertTrue(f.remote.calls.isEmpty())
    }

    // --- profile refresh ----------------------------------------------------------------------------------------

    @Test
    fun `sync refreshes name and vip at most once an hour`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.repo.syncMyList()
        assertEquals(AccountState(true, "Ali Server"), f.repo.state.first())
        assertEquals(11, f.repo.vipDaysLeft.first())
        assertEquals(1, f.remote.calls.count { it == "profile" })

        f.now += AccountRepositoryImpl.MIN_SYNC_INTERVAL_MS + 1
        f.repo.syncMyList()
        assertEquals(1, f.remote.calls.count { it == "profile" })

        f.now += AccountRepositoryImpl.PROFILE_REFRESH_MS
        f.repo.syncMyList()
        assertEquals(2, f.remote.calls.count { it == "profile" })
    }

    // --- progress push ----------------------------------------------------------------------------------------------

    private fun progress(key: WatchKey, positionMs: Long, durationMs: Long = 100_000_000, completed: Boolean = false, updatedAtMs: Long = 5) =
        PlaybackProgress(key, positionMs, durationMs, updatedAtMs, completed)

    @Test
    fun `push sends whole seconds and the episode only for episodes`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.repo.pushProgress(progress(WatchKey(353371), 1_234_999))
        f.repo.pushProgress(progress(WatchKey(624236, 2, 3), 600_000))
        assertEquals(listOf(353371L to 1234, 624236L to 600), f.remote.saved.map { it.postId to it.seconds })
        assertEquals(listOf(0 to 0, 2 to 3), f.remote.saved.map { it.season to it.episode })
    }

    @Test
    fun `push is a no-op signed out or for trivial positions and never throws`() = runTest {
        val signedOut = Fixture(this)
        signedOut.repo.pushProgress(progress(WatchKey(1), 600_000))
        assertTrue(signedOut.remote.calls.isEmpty())

        val f = Fixture(this, signedIn = true)
        f.repo.pushProgress(progress(WatchKey(1), 500))
        assertTrue(f.remote.saved.isEmpty())
    }

    @Test
    fun `push with an expired token signs out`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.saveFailure = RemoteResult.Unauthorized
        f.repo.pushProgress(progress(WatchKey(1), 60_000))
        assertEquals(AccountState(false), f.repo.state.first())
    }

    @Test
    fun `push failure is swallowed`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.saveFailure = RemoteResult.Failed
        f.repo.pushProgress(progress(WatchKey(1), 60_000))
        assertEquals(AccountState(true, "Ali"), f.repo.state.first())
    }

    // --- progress pull ----------------------------------------------------------------------------------------------

    private fun server(key: WatchKey, positionMs: Long, durationMs: Long = 7_200_000, withTitle: Boolean = true) =
        RemoteProgressItem(key, positionMs, durationMs, if (withTitle) summary(key.postId) else null)

    @Test
    fun `pull fills in titles that are missing locally`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.progress = RemoteResult.Ok(
            listOf(server(WatchKey(1), 600_000), server(WatchKey(2, 1, 4), 120_000, durationMs = 0)),
        )
        f.repo.pullProgress()

        val movie = f.watch.progress(WatchKey(1))!!
        assertEquals(600_000L, movie.positionMs)
        assertEquals(7_200_000L, movie.durationMs)
        assertFalse(movie.completed)
        val episode = f.watch.progress(WatchKey(2, 1, 4))!!
        assertEquals(120_000L, episode.positionMs)
        assertEquals(0L, episode.durationMs)
        // server order is kept: the first item is the newest
        assertTrue(movie.updatedAtMs > episode.updatedAtMs)
        assertEquals(listOf(1L, 2L), f.watchDao.snapshots.value.keys.sorted())
    }

    @Test
    fun `pull takes a further server position and ignores everything else`() = runTest {
        val f = Fixture(this, signedIn = true)
        suspend fun local(id: Long, positionMs: Long, completed: Boolean = false) =
            f.watch.saveProgress(summary(id), progress(WatchKey(id), positionMs, completed = completed))
        local(1, 100_000) // server further: taken
        local(2, 600_000) // server behind: kept
        local(3, 600_000) // server within the margin: kept
        local(4, 7_100_000, completed = true) // finished locally: kept
        f.remote.progress = RemoteResult.Ok(
            listOf(
                server(WatchKey(1), 900_000),
                server(WatchKey(2), 300_000),
                server(WatchKey(3), 604_000),
                server(WatchKey(4), 7_150_000),
            ),
        )
        f.repo.pullProgress()

        assertEquals(900_000L, f.watch.progress(WatchKey(1))!!.positionMs)
        assertEquals(600_000L, f.watch.progress(WatchKey(2))!!.positionMs)
        assertEquals(600_000L, f.watch.progress(WatchKey(3))!!.positionMs)
        assertEquals(7_100_000L, f.watch.progress(WatchKey(4))!!.positionMs)
        assertTrue(f.watch.progress(WatchKey(4))!!.completed)
    }

    @Test
    fun `pull keeps the local duration when the server has none and completes at 95 percent`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.watch.saveProgress(summary(1), progress(WatchKey(1), 100_000, durationMs = 1_000_000))
        f.remote.progress = RemoteResult.Ok(listOf(server(WatchKey(1), 960_000, durationMs = 0)))
        f.repo.pullProgress()
        val p = f.watch.progress(WatchKey(1))!!
        assertEquals(1_000_000L, p.durationMs)
        assertTrue("watched to the end elsewhere", p.completed)
    }

    @Test
    fun `pull skips trivial server positions and resolves missing titles`() = runTest {
        val f = Fixture(this, signedIn = true)
        f.remote.progress = RemoteResult.Ok(
            listOf(server(WatchKey(1), 2_000), server(WatchKey(2), 300_000, withTitle = false), server(WatchKey(3), 300_000, withTitle = false)),
        )
        f.resolver = { id -> if (id == 2L) summary(2) else null }
        f.repo.pullProgress()
        assertNull(f.watch.progress(WatchKey(1)))
        assertEquals(300_000L, f.watch.progress(WatchKey(2))!!.positionMs)
        assertNull("unresolvable title cannot be painted", f.watch.progress(WatchKey(3)))
        assertEquals(listOf(2L, 3L), f.resolved)
    }

    @Test
    fun `pull is a no-op signed out and swallows failures`() = runTest {
        val signedOut = Fixture(this)
        signedOut.remote.progress = RemoteResult.Ok(listOf(server(WatchKey(1), 600_000)))
        signedOut.repo.pullProgress()
        assertTrue(signedOut.remote.calls.isEmpty())
        assertNull(signedOut.watch.progress(WatchKey(1)))

        val f = Fixture(this, signedIn = true)
        f.remote.progress = RemoteResult.Failed
        f.repo.pullProgress()
        assertEquals(AccountState(true, "Ali"), f.repo.state.first())

        f.remote.progress = RemoteResult.Unauthorized
        f.repo.pullProgress()
        assertEquals(AccountState(false), f.repo.state.first())
    }

    @Test
    fun `the pull rule as a pure function`() {
        fun take(local: PlaybackProgress?, serverMs: Long) =
            AccountRepositoryImpl.shouldTakeServerProgress(local, server(WatchKey(1), serverMs))
        assertTrue(take(null, 10_000))
        assertFalse(take(null, 4_999))
        assertFalse(take(progress(WatchKey(1), 50_000, completed = true), 5_000_000))
        assertTrue(take(progress(WatchKey(1), 50_000), 55_001))
        assertFalse(take(progress(WatchKey(1), 50_000), 55_000))
        assertFalse(take(progress(WatchKey(1), 50_000), 10_000))
    }
}
