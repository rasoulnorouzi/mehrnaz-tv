package app.bolbol.tv.feature.downloads

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadItem
import app.bolbol.tv.core.model.DownloadRepository
import app.bolbol.tv.core.model.DownloadRequest
import app.bolbol.tv.core.model.DownloadStatus
import app.bolbol.tv.core.model.OfflineMedia
import app.bolbol.tv.core.model.StartDownloadResult
import app.bolbol.tv.core.model.StorageInfo
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeRepo : DownloadRepository {
    val items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val removed = mutableListOf<WatchKey>()
    val retried = mutableListOf<WatchKey>()
    var removedAll = 0

    override fun downloads(): Flow<List<DownloadItem>> = items
    override fun downloadsForTitle(postId: Long): Flow<List<DownloadItem>> = items
    override suspend fun storage() = StorageInfo(freeBytes = 5_000, usedByDownloadsBytes = items.value.sumOf { it.bytesDownloaded })
    override suspend fun sizeOf(stream: StreamOption): Long? = null
    override suspend fun start(request: DownloadRequest): StartDownloadResult = StartDownloadResult.Started
    override suspend fun retry(key: WatchKey) {
        retried += key
    }

    override suspend fun remove(key: WatchKey) {
        removed += key
        items.value = items.value.filterNot { it.key == key }
    }

    override suspend fun removeAll() {
        removedAll++
        items.value = emptyList()
    }

    override suspend fun offlineMedia(key: WatchKey): OfflineMedia? = null
}

private fun item(
    key: WatchKey,
    status: DownloadStatus,
    done: Long = 0,
    total: Long? = 100,
    failure: DownloadFailure? = null,
    fa: String = "عنوان",
) = DownloadItem(
    key = key,
    title = TitleSummary(key.postId, null, "Title", fa, "file:///p.jpg", null, 2024, null, false, true),
    subtitleLine = if (key.isEpisode) "فصل ۱ · قسمت ۲" else null,
    stream = StreamOption(1080, AudioType.DUBBED, "https://x", null, "1080p", null),
    status = status, bytesDownloaded = done, totalBytes = total, failure = failure, createdAtMs = 1,
)

class DownloadRowTest {
    @Test fun completedRowIsPlayableFullAndHasNoBar() {
        val row = item(WatchKey(1), DownloadStatus.COMPLETED, done = 100).toRow()
        assertEquals(RowStatus.COMPLETED, row.status)
        assertTrue(row.canPlay)
        assertFalse(row.canRetry)
        assertFalse(row.showProgress)
        assertEquals(1f, row.progressFraction, 0f)
    }

    @Test fun downloadingRowShowsPercentAndABar() {
        val row = item(WatchKey(1), DownloadStatus.DOWNLOADING, done = 45).toRow()
        assertEquals(45, row.percent)
        assertEquals(0.45f, row.progressFraction, 0.001f)
        assertTrue(row.showProgress)
        assertFalse(row.canPlay)
    }

    @Test fun unknownSizeGivesAnEmptyBarNotACrash() {
        val row = item(WatchKey(1), DownloadStatus.DOWNLOADING, done = 45, total = null).toRow()
        assertNull(row.percent)
        assertEquals(0f, row.progressFraction, 0f)
    }

    @Test fun failedRowCanRetryButNotPlay() {
        val row = item(WatchKey(1), DownloadStatus.FAILED, failure = DownloadFailure.NETWORK).toRow()
        assertTrue(row.canRetry)
        assertFalse(row.canPlay)
        assertEquals(DownloadFailure.NETWORK, row.failure)
    }

    @Test fun everyStatusMaps() {
        assertEquals(RowStatus.QUEUED, DownloadStatus.QUEUED.toRowStatus())
        assertEquals(RowStatus.PAUSED, DownloadStatus.PAUSED.toRowStatus())
    }

    @Test fun titleFallsBackToTheOriginalWhenThePersianOneIsBlank() {
        assertEquals("Title", item(WatchKey(1), DownloadStatus.QUEUED, fa = " ").toRow().titleText)
        assertEquals("عنوان", item(WatchKey(1), DownloadStatus.QUEUED).toRow().titleText)
    }

    @Test fun episodeRowKeepsItsSeasonEpisodeLine() {
        assertEquals("فصل ۱ · قسمت ۲", item(WatchKey(1, 1, 2), DownloadStatus.QUEUED).toRow().subtitleLine)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.vm(repo: FakeRepo): DownloadsViewModel {
        val vm = DownloadsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    private fun DownloadsViewModel.content() = uiState.value as DownloadsUiState.Content

    @Test fun emptyListIsAnEmptyContentWithStorage() = runTest {
        val vm = vm(FakeRepo())
        assertTrue(vm.content().rows.isEmpty())
        assertEquals(5_000L, vm.content().storage?.freeBytes)
    }

    @Test fun listFollowsTheRepositoryLive() = runTest {
        val repo = FakeRepo()
        val vm = vm(repo)
        repo.items.value = listOf(item(WatchKey(1), DownloadStatus.DOWNLOADING, done = 10))
        assertEquals(10, vm.content().rows.single().percent)
        repo.items.value = listOf(item(WatchKey(1), DownloadStatus.DOWNLOADING, done = 60))
        assertEquals(60, vm.content().rows.single().percent)
        assertEquals(60L, vm.content().storage?.usedByDownloadsBytes)
    }

    @Test fun removeAsksFirstAndOnlyDeletesAfterConfirm() = runTest {
        val repo = FakeRepo().apply { items.value = listOf(item(WatchKey(1), DownloadStatus.COMPLETED, 100)) }
        val vm = vm(repo)
        vm.onRemoveClicked(vm.content().rows.single())
        assertEquals(DownloadsConfirm.RemoveOne(WatchKey(1), "عنوان", null), vm.confirm.value)
        assertTrue(repo.removed.isEmpty())

        vm.onConfirm()
        assertEquals(listOf(WatchKey(1)), repo.removed)
        assertNull(vm.confirm.value)
        assertTrue(vm.content().rows.isEmpty())
    }

    @Test fun cancellingTheConfirmDeletesNothing() = runTest {
        val repo = FakeRepo().apply { items.value = listOf(item(WatchKey(1), DownloadStatus.COMPLETED, 100)) }
        val vm = vm(repo)
        vm.onRemoveClicked(vm.content().rows.single())
        vm.onDismissConfirm()
        vm.onConfirm() // nothing pending
        assertTrue(repo.removed.isEmpty())
        assertEquals(1, vm.content().rows.size)
    }

    @Test fun removeAllCountsTheItemsAndAsksFirst() = runTest {
        val repo = FakeRepo().apply {
            items.value = listOf(item(WatchKey(1), DownloadStatus.COMPLETED, 100), item(WatchKey(2), DownloadStatus.QUEUED))
        }
        val vm = vm(repo)
        vm.onRemoveAllClicked()
        assertEquals(DownloadsConfirm.RemoveAll(2), vm.confirm.value)
        assertEquals(0, repo.removedAll)
        vm.onConfirm()
        assertEquals(1, repo.removedAll)
        assertTrue(vm.content().rows.isEmpty())
    }

    @Test fun removeAllOnAnEmptyListAsksNothing() = runTest {
        val vm = vm(FakeRepo())
        vm.onRemoveAllClicked()
        assertNull(vm.confirm.value)
    }

    @Test fun retryGoesToTheRepository() = runTest {
        val repo = FakeRepo()
        val vm = vm(repo)
        vm.onRetry(WatchKey(3))
        assertEquals(listOf(WatchKey(3)), repo.retried)
    }
}
