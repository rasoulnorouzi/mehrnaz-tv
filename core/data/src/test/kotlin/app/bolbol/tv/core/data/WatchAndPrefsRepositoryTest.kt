package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.ContinueWatchingRow
import app.bolbol.tv.core.database.PrefsEntity
import app.bolbol.tv.core.database.ProgressEntity
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

    // --- watch-list imports (QA-03) -------------------------------------------------------------

    @Test
    fun reachedCompletionIsNeverTrueWithoutADuration() {
        assertFalse(WatchRepositoryImpl.reachedCompletion(5_000, 0))
        assertFalse(WatchRepositoryImpl.reachedCompletion(5_000, -1))
        assertFalse(WatchRepositoryImpl.reachedCompletion(0, 0))
        assertTrue(WatchRepositoryImpl.reachedCompletion(95, 100))
    }

    @Test
    fun importedProgressIsARemoteRowAndNeverCompleted() = runTest {
        val repo = watch()
        // the caller even says completed, and the position is past 95 % of a (believed) duration
        repo.importRemote(summary(100), progress(movie, 98_000, 100_000, completed = true))
        val row = dao.progressRows.value.getValue("100:0:0")
        assertFalse(row.completed)
        assertEquals("remote", row.source)
        assertEquals(98_000L, row.positionMs)
        assertEquals(0L, row.durationMs) // unknown rather than finished
        assertEquals(100L, dao.snapshots.value.getValue(100).postId)
    }

    @Test
    fun importedProgressKeepsASaneDurationAndAnUnknownOne() = runTest {
        val repo = watch()
        repo.importRemote(summary(100), progress(movie, 1_200_000, 5_100_000))
        repo.importRemote(summary(200), progress(episode, 300_000, 0))
        repo.importRemote(summary(300), progress(WatchKey(300), 300_000, -5))
        assertEquals(5_100_000L, dao.progressRows.value.getValue("100:0:0").durationMs)
        assertEquals(0L, dao.progressRows.value.getValue("200:1:2").durationMs)
        assertEquals(0L, dao.progressRows.value.getValue("300:0:0").durationMs)
        assertTrue(dao.progressRows.value.values.none { it.completed })
    }

    @Test
    fun anImportedUnknownDurationIsOverwrittenByTheLocalPlayer() = runTest {
        val repo = watch()
        repo.importRemote(summary(100), progress(movie, 3_000_000, 0))

        // the player reports the real duration (and a later position)
        repo.saveProgress(summary(100), progress(movie, 3_100_000, 5_400_000, updatedAt = 99))
        val played = dao.progressRows.value.getValue("100:0:0")
        assertEquals(5_400_000L, played.durationMs)
        assertEquals(3_100_000L, played.positionMs)
        assertEquals("local", played.source)
        assertFalse(played.completed)

        // and the usual completion rule works again against the real duration
        repo.saveProgress(summary(100), progress(movie, 5_200_000, 5_400_000, updatedAt = 100))
        assertTrue(dao.progressRows.value.getValue("100:0:0").completed)
    }

    @Test
    fun anImportedRowWithoutDurationStillShowsInContinueWatching() = runTest {
        val repo = watch()
        repo.importRemote(summary(100), progress(movie, 3_000_000, 0, updatedAt = 5))
        val row = dao.progressRows.value.getValue("100:0:0")
        assertEquals(0L, row.durationMs)
        assertFalse(row.completed)
        // the DAO query only filters completed = 0 and hidden titles, so this row is a Continue Watching candidate
        assertFalse(dao.snapshots.value.getValue(100).hiddenFromContinue)
    }

    @Test
    fun markWatchedOnAnImportedRowWithUnknownDurationKeepsThePositionAndBecomesLocal() = runTest {
        val repo = watch()
        repo.importRemote(summary(100), progress(movie, 3_000_000, 0))
        repo.markWatched(movie, true)
        val row = dao.progressRows.value.getValue("100:0:0")
        assertTrue(row.completed)
        assertEquals(3_000_000L, row.positionMs)
        assertEquals("local", row.source)
        // so the one-time repair of remote imports leaves it alone
        assertEquals(0, repo.repairRemoteImports())
        assertTrue(dao.progressRows.value.getValue("100:0:0").completed)
    }

    @Test
    fun repairResetsOnlyRemoteRowsWithAnUntrustedDuration() = runTest {
        val repo = watch()
        fun plant(key: String, position: Long, duration: Long, completed: Boolean, source: String) {
            val k = WatchKey.parse(key)!!
            dao.progressRows.value = dao.progressRows.value + (key to ProgressEntity(key, k.postId, k.season, k.episode, position, duration, 5, completed, source))
        }
        plant("1:0:0", 29_000, 30_000, completed = true, source = "remote") // 30 s "duration" (old bug): reset
        plant("2:0:0", 1_000_000, 5_000_000, completed = true, source = "remote") // completed at 20 %: reset
        plant("3:0:0", 60_000, 20_000, completed = false, source = "remote") // shorter than the position: reset
        plant("4:0:0", 600_000, 30_000_000, completed = false, source = "remote") // 8 h: reset
        plant("5:0:0", 29_000, 30_000, completed = true, source = "local") // the player said so: kept
        plant("6:0:0", 5_000_000, 5_100_000, completed = true, source = "remote") // 98 % of a sane duration: kept
        plant("7:0:0", 600_000, 5_100_000, completed = false, source = "remote") // fine: kept
        plant("8:0:0", 600_000, 0, completed = false, source = "remote") // already unknown: kept

        assertEquals(4, repo.repairRemoteImports())

        val rows = dao.progressRows.value
        for (key in listOf("1:0:0", "2:0:0", "3:0:0", "4:0:0")) {
            assertFalse(key, rows.getValue(key).completed)
            assertEquals(key, 0L, rows.getValue(key).durationMs)
            assertEquals(key, "remote", rows.getValue(key).source)
        }
        assertTrue(rows.getValue("5:0:0").completed)
        assertEquals(30_000L, rows.getValue("5:0:0").durationMs)
        assertTrue(rows.getValue("6:0:0").completed)
        assertEquals(5_100_000L, rows.getValue("6:0:0").durationMs)
        assertEquals(5_100_000L, rows.getValue("7:0:0").durationMs)
        assertEquals(600_000L, rows.getValue("8:0:0").positionMs)
        // idempotent
        assertEquals(0, repo.repairRemoteImports())
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
