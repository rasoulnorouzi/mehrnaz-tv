package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.ContinueWatchingRow
import app.bolbol.tv.core.database.PrefsEntity
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchAndPrefsRepositoryTest {

    private val dao = FakeWatchDao()
    private val prefsDao = FakePrefsDao()

    private fun TestScope.watch() = WatchRepositoryImpl(dao, UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.prefs() = PrefsRepositoryImpl(prefsDao, UnconfinedTestDispatcher(testScheduler))

    private val movie = WatchKey(100)
    private val episode = WatchKey(200, season = 1, episode = 2)

    private fun progress(key: WatchKey, position: Long, duration: Long, updatedAt: Long = 10, completed: Boolean = false) =
        PlaybackProgress(key, position, duration, updatedAt, completed)

    // --- saveProgress -------------------------------------------------------------------------

    @Test
    fun saveProgressWritesSnapshotAndProgress() = runTest {
        watch().saveProgress(summary(100, "Heat", TitleType.MOVIE), progress(movie, 60_000, 6_000_000, updatedAt = 777))

        val row = dao.progressRows.value.getValue("100:0:0")
        assertEquals(60_000L, row.positionMs)
        assertEquals(6_000_000L, row.durationMs)
        assertEquals(100L, row.postId)
        assertFalse(row.completed)

        val snapshot = dao.snapshots.value.getValue(100)
        assertEquals("Heat", snapshot.title)
        assertEquals("MOVIE", snapshot.type)
        assertEquals(777L, snapshot.lastWatchedAtMs)
        assertFalse(snapshot.hiddenFromContinue)
    }

    @Test
    fun passing95PercentForcesCompleted() = runTest {
        val repo = watch()
        repo.saveProgress(summary(100), progress(movie, 94_999, 100_000))
        assertFalse(dao.progressRows.value.getValue("100:0:0").completed)

        repo.saveProgress(summary(100), progress(movie, 95_000, 100_000))
        assertTrue(dao.progressRows.value.getValue("100:0:0").completed)
    }

    @Test
    fun callerCompletedIsKeptEvenFarFromTheEnd() = runTest {
        watch().saveProgress(summary(100), progress(movie, 1_000, 100_000, completed = true))
        assertTrue(dao.progressRows.value.getValue("100:0:0").completed)
    }

    @Test
    fun unknownDurationNeverForcesCompleted() = runTest {
        watch().saveProgress(summary(100), progress(movie, 5_000, duration = 0))
        assertFalse(dao.progressRows.value.getValue("100:0:0").completed)
    }

    @Test
    fun savingProgressUnhidesTheTitle() = runTest {
        val repo = watch()
        repo.saveProgress(summary(100), progress(movie, 1_000, 100_000))
        repo.hideFromContinueWatching(100)
        assertTrue(dao.snapshots.value.getValue(100).hiddenFromContinue)

        repo.saveProgress(summary(100), progress(movie, 2_000, 100_000, updatedAt = 20))
        assertFalse(dao.snapshots.value.getValue(100).hiddenFromContinue)
        assertEquals(20L, dao.snapshots.value.getValue(100).lastWatchedAtMs)
    }

    @Test
    fun hidingKeepsTheProgressRows() = runTest {
        val repo = watch()
        repo.saveProgress(summary(200), progress(episode, 1_000, 100_000))
        repo.hideFromContinueWatching(200)
        assertEquals(1_000L, repo.progress(episode)!!.positionMs)
    }

    @Test
    fun snapshotMustMatchTheKey() = runTest {
        try {
            watch().saveProgress(summary(999), progress(movie, 1, 2))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- markWatched --------------------------------------------------------------------------

    @Test
    fun markWatchedCompletesAnExistingRowAtItsDuration() = runTest {
        val repo = watch()
        repo.saveProgress(summary(200), progress(episode, 1_000, 100_000, updatedAt = 42))
        repo.markWatched(episode, true)

        val row = dao.progressRows.value.getValue("200:1:2")
        assertTrue(row.completed)
        assertEquals(100_000L, row.positionMs)
        assertEquals(42L, row.updatedAtMs) // untouched: does not become "the latest episode"
    }

    @Test
    fun markWatchedOnANeverPlayedItemCreatesACompletedRow() = runTest {
        val repo = watch()
        repo.markWatched(episode, true)

        val saved = repo.progress(episode)!!
        assertTrue(saved.completed)
        assertEquals(episode, saved.key)
        assertEquals(0L, saved.updatedAtMs)
    }

    @Test
    fun markUnwatchedDeletesTheProgressRow() = runTest {
        val repo = watch()
        repo.saveProgress(summary(200), progress(episode, 100_000, 100_000, completed = true))
        repo.markWatched(episode, false)
        assertNull(repo.progress(episode))
        // idempotent
        repo.markWatched(episode, false)
        assertNull(repo.progress(episode))
    }

    // --- reads --------------------------------------------------------------------------------

    @Test
    fun progressForTitleReturnsEpisodesInOrder() = runTest {
        val repo = watch()
        repo.saveProgress(summary(200), progress(WatchKey(200, 1, 3), 1, 10))
        repo.saveProgress(summary(200), progress(WatchKey(200, 1, 1), 1, 10))
        repo.saveProgress(summary(200), progress(WatchKey(200, 2, 1), 1, 10))
        repo.saveProgress(summary(300), progress(WatchKey(300), 1, 10))

        val keys = repo.progressForTitle(200).first().map { it.key.asString() }
        assertEquals(listOf("200:1:1", "200:1:3", "200:2:1"), keys)
    }

    @Test
    fun continueWatchingRowsAreMappedToDomainItems() = runTest {
        val snapshot = summary(100, "Heat", TitleType.MOVIE).toSnapshot(hiddenFromContinue = false, lastWatchedAtMs = 9)
        val entity = progress(movie, 1_000, 10_000, updatedAt = 9).toEntity()
        dao.continueRows = listOf(ContinueWatchingRow(entity, snapshot))

        val items = watch().continueWatching(20).first()

        assertEquals(1, items.size)
        assertEquals(summary(100, "Heat", TitleType.MOVIE), items.single().title)
        assertEquals(progress(movie, 1_000, 10_000, updatedAt = 9), items.single().progress)
    }

    @Test
    fun entitiesRoundTripThroughTheMappers() {
        val title = summary(5, "T", null)
        assertEquals(title, title.toSnapshot(false, 0).toSummary())
        val p = progress(episode, 3, 4, 5, completed = true)
        assertEquals(p, p.toEntity().toModel())
        assertNull(summary(5, "T", null).toSnapshot(false, 0).type)
        // An unknown stored enum name reads back as null instead of crashing.
        assertNull(title.toSnapshot(false, 0).copy(type = "SOMETHING_NEW").toSummary().type)
    }

    // --- prefs --------------------------------------------------------------------------------

    @Test
    fun globalPrefsDefaultWhenNothingIsSaved() = runTest {
        assertEquals(PlaybackPrefs(), prefs().globalPrefs().first())
    }

    @Test
    fun globalPrefsRoundTrip() = runTest {
        val repo = prefs()
        val saved = PlaybackPrefs(audio = AudioType.DUBBED, quality = 720, subtitleScale = 1.25f)
        repo.saveGlobalPrefs(saved)
        assertEquals(saved, repo.globalPrefs().first())
        assertTrue(prefsDao.get(PrefsEntity.GLOBAL_POST_ID) != null)
    }

    @Test
    fun titlePrefsAreSeparateFromGlobalAndNullWhenAbsent() = runTest {
        val repo = prefs()
        assertNull(repo.titlePrefs(55))
        repo.saveGlobalPrefs(PlaybackPrefs(quality = 480))
        assertNull(repo.titlePrefs(55))

        val perTitle = PlaybackPrefs(audio = AudioType.SUBTITLED, quality = 1080)
        repo.saveTitlePrefs(55, perTitle)
        assertEquals(perTitle, repo.titlePrefs(55))
        assertEquals(PlaybackPrefs(quality = 480), repo.globalPrefs().first())
    }

    @Test
    fun savingTitlePrefsForPostZeroIsRejected() = runTest {
        try {
            prefs().saveTitlePrefs(0, PlaybackPrefs())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
