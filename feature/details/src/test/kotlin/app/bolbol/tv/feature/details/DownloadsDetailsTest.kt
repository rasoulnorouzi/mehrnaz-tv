package app.bolbol.tv.feature.details

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
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

private const val MB = 1024L * 1024
private const val GB = 1024L * MB

internal class FakeDownloads(var free: Long = 10 * GB) : DownloadRepository {
    val items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val sizes = mutableMapOf<StreamOption, Long?>()
    val started = mutableListOf<DownloadRequest>()
    val removed = mutableListOf<WatchKey>()
    val retried = mutableListOf<WatchKey>()
    var startResult: StartDownloadResult = StartDownloadResult.Started

    override fun downloads(): Flow<List<DownloadItem>> = items
    override fun downloadsForTitle(postId: Long): Flow<List<DownloadItem>> = items.map { l -> l.filter { it.key.postId == postId } }
    override suspend fun storage() = StorageInfo(free, 0)
    override suspend fun sizeOf(stream: StreamOption): Long? = sizes[stream]
    override suspend fun start(request: DownloadRequest): StartDownloadResult {
        started += request
        return startResult
    }

    override suspend fun retry(key: WatchKey) {
        retried += key
    }

    override suspend fun remove(key: WatchKey) {
        removed += key
        items.value = items.value.filterNot { it.key == key }
    }

    override suspend fun removeAll() = Unit
    override suspend fun offlineMedia(key: WatchKey): OfflineMedia? = null
}

internal fun dlItem(
    key: WatchKey,
    status: DownloadStatus,
    done: Long = 0,
    total: Long? = 100,
    failure: DownloadFailure? = null,
) = DownloadItem(key, summary(key.postId), null, stream(), status, done, total, failure, createdAtMs = 1)

class DownloadStateLogicTest {
    private val key = WatchKey(1)

    @Test fun noDownloadIsIdle() {
        assertEquals(DownloadButtonState.Idle, (null as DownloadItem?).toButtonState())
        assertFalse(DownloadButtonState.Idle.removable)
    }

    @Test fun everyStatusMapsToAButtonState() {
        assertEquals(DownloadButtonState.Queued, dlItem(key, DownloadStatus.QUEUED).toButtonState())
        assertEquals(DownloadButtonState.Downloading(45), dlItem(key, DownloadStatus.DOWNLOADING, done = 45).toButtonState())
        assertEquals(DownloadButtonState.Downloading(null), dlItem(key, DownloadStatus.DOWNLOADING, total = null).toButtonState())
        assertEquals(DownloadButtonState.Paused(10), dlItem(key, DownloadStatus.PAUSED, done = 10).toButtonState())
        assertEquals(DownloadButtonState.Completed, dlItem(key, DownloadStatus.COMPLETED, done = 100).toButtonState())
        assertEquals(
            DownloadButtonState.Failed(DownloadFailure.NETWORK),
            dlItem(key, DownloadStatus.FAILED, failure = DownloadFailure.NETWORK).toButtonState(),
        )
        assertTrue(DownloadButtonState.Queued.removable)
        assertTrue(DownloadButtonState.Completed.removable)
    }

    @Test fun anOptionFitsWhenFilePlusMarginIsBelowFreeSpace() {
        assertTrue(optionFits(1 * GB, 2 * GB))
        assertFalse(optionFits(2 * GB, 2 * GB)) // the 300 MB margin does not fit
        assertTrue(optionFits(2 * GB - 300 * MB, 2 * GB))
        assertFalse(optionFits(5 * GB, 1 * GB))
    }

    @Test fun unknownSizeOrUnknownFreeSpaceIsNotARefusal() {
        assertTrue(optionFits(null, 1 * GB))
        assertTrue(optionFits(5 * GB, null))
    }

    @Test fun optionsListHighestQualityFirstDubbedFirst() {
        val streams = listOf(
            stream(720, AudioType.SUBTITLED), stream(1080, AudioType.SUBTITLED), stream(720, AudioType.DUBBED),
        )
        val rows = initialOptions(streams, freeBytes = null)
        assertEquals(listOf(1080 to AudioType.SUBTITLED, 720 to AudioType.DUBBED, 720 to AudioType.SUBTITLED),
            rows.map { it.stream.quality to it.stream.audio })
        assertTrue(rows.all { it.sizeLoading && it.fits })
    }

    @Test fun freeSpaceArrivingAfterSizesRecomputesFits() {
        val s = stream(1080)
        var pick = DownloadPickState(WatchKey(1), null, initialOptions(listOf(s), null), freeBytes = null)
        pick = pick.withSize(s, 3 * GB)
        assertTrue(pick.options.single().fits) // free space still unknown
        pick = pick.withFree(2 * GB)
        assertFalse(pick.options.single().fits)
        assertFalse(pick.options.single().sizeLoading)
    }

    @Test fun contentCarriesMovieAndEpisodeDownloadStates() {
        val movie = movie(1)
        val c = buildDetailsContent(movie, emptyList(), null, null, downloads = listOf(dlItem(WatchKey(1), DownloadStatus.COMPLETED)))
        assertEquals(DownloadButtonState.Completed, c.movieDownload)

        val series = series(2, season(1, 3))
        val s = buildDetailsContent(
            series, emptyList(), null, null,
            downloads = listOf(dlItem(WatchKey(2, 1, 2), DownloadStatus.DOWNLOADING, done = 30)),
        )
        assertEquals(
            listOf(DownloadButtonState.Idle, DownloadButtonState.Downloading(30), DownloadButtonState.Idle),
            s.episodes.map { it.download },
        )
        assertNull(s.movieDownload)
    }

    @Test fun withoutADownloadRepositoryNothingIsShown() {
        val c = buildDetailsContent(movie(1), emptyList(), null, null)
        assertNull(c.movieDownload)
        assertNull(buildDetailsContent(series(2, season(1, 2)), emptyList(), null, null).episodes.first().download)
    }

    @Test fun episodeListKeepsOneRowOfContextOnBothSides() {
        // container 300, row 60, margin 68
        assertEquals(0f, episodeScrollDistance(offset = 100f, size = 60f, containerSize = 300f, marginPx = 68f), 0.01f)
        assertEquals(28f, episodeScrollDistance(offset = 200f, size = 60f, containerSize = 300f, marginPx = 68f), 0.01f)
        assertEquals(-18f, episodeScrollDistance(offset = 50f, size = 60f, containerSize = 300f, marginPx = 68f), 0.01f)
        // a row bigger than the container minus margins: margin shrinks, never a negative margin
        assertEquals(0f, episodeScrollDistance(offset = 0f, size = 300f, containerSize = 300f, marginPx = 68f), 0.01f)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val hd = stream(1080, AudioType.SUBTITLED)
    private val sd = stream(480, AudioType.DUBBED)

    private fun TestScope.vm(dl: FakeDownloads?, details: app.bolbol.tv.core.model.TitleDetails = movie(1, listOf(hd, sd))): DetailsViewModel {
        val vm = DetailsViewModel(1, FakeCatalog(details), FakeWatch(), FakePrefs(), downloads = dl)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    private fun DetailsViewModel.pick() = (overlay.value as DetailsOverlay.Pick).state

    @Test fun clickingDownloadOpensTheDialogWithSizesAndFreeSpace() = runTest {
        val dl = FakeDownloads(free = 4 * GB).apply { sizes[hd] = 3 * GB + 800 * MB; sizes[sd] = 600 * MB }
        val vm = vm(dl)
        vm.onDownloadClicked(WatchKey(1))
        val pick = vm.pick()
        assertEquals(4 * GB, pick.freeBytes)
        assertEquals(listOf(1080, 480), pick.options.map { it.stream.quality })
        assertFalse(pick.options[0].fits) // 3.8 GB + margin > 4 GB
        assertTrue(pick.options[1].fits)
        assertTrue(pick.options.none { it.sizeLoading })
    }

    @Test fun anUnknownSizeStaysSelectableAndFallsBackToTheSiteText() = runTest {
        val dl = FakeDownloads() // sizeOf returns null for both
        val vm = vm(dl)
        vm.onDownloadClicked(WatchKey(1))
        assertTrue(vm.pick().options.all { it.fits && !it.sizeLoading && it.sizeBytes == null })
    }

    @Test fun choosingAnOptionStartsItAndClosesTheDialog() = runTest {
        val dl = FakeDownloads()
        val vm = vm(dl)
        vm.onDownloadClicked(WatchKey(1))
        vm.onDownloadOptionChosen(hd)
        assertEquals(1, dl.started.size)
        assertEquals(hd, dl.started.single().stream)
        assertEquals(WatchKey(1), dl.started.single().key)
        assertNull(dl.started.single().subtitleLine)
        assertNull(vm.overlay.value)
    }

    @Test fun anOptionThatDoesNotFitExplainsWithNumbersAndDoesNotStart() = runTest {
        val dl = FakeDownloads(free = 1 * GB).apply { sizes[hd] = 2 * GB }
        val vm = vm(dl)
        vm.onDownloadClicked(WatchKey(1))
        vm.onDownloadOptionChosen(hd)
        assertTrue(dl.started.isEmpty())
        assertEquals(
            DownloadMessage.NotEnoughSpace(2 * GB + DOWNLOAD_SAFETY_MARGIN_BYTES, 1 * GB),
            vm.pick().message,
        )
    }

    @Test fun theEnginesNotEnoughSpaceAnswerIsShownInTheDialog() = runTest {
        val dl = FakeDownloads().apply { startResult = StartDownloadResult.NotEnoughSpace(5 * GB, 4 * GB) }
        val vm = vm(dl)
        vm.onDownloadClicked(WatchKey(1))
        vm.onDownloadOptionChosen(sd)
        assertEquals(DownloadMessage.NotEnoughSpace(5 * GB, 4 * GB), vm.pick().message)
        assertFalse(vm.pick().starting)
    }

    @Test fun anEpisodeDownloadCarriesItsSeasonEpisodeLine() = runTest {
        val dl = FakeDownloads()
        val series = series(1, season(2, 3))
        val vm = vm(dl, series)
        vm.onDownloadClicked(WatchKey(1, 2, 3))
        vm.onDownloadOptionChosen(vm.pick().options.first().stream)
        assertEquals("فصل ۲ · قسمت ۳", dl.started.single().subtitleLine)
        assertEquals(WatchKey(1, 2, 3), dl.started.single().key)
    }

    @Test fun removalAsksFirstThenDeletes() = runTest {
        val dl = FakeDownloads().apply { items.value = listOf(dlItem(WatchKey(1), DownloadStatus.COMPLETED)) }
        val vm = vm(dl)
        assertEquals(DownloadButtonState.Completed, (vm.uiState.value as DetailsUiState.Content).movieDownload)
        vm.onRemoveDownloadClicked(WatchKey(1))
        assertTrue(vm.overlay.value is DetailsOverlay.ConfirmRemove)
        assertTrue(dl.removed.isEmpty())

        vm.onConfirmRemoveDownload()
        assertEquals(listOf(WatchKey(1)), dl.removed)
        assertNull(vm.overlay.value)
        assertEquals(DownloadButtonState.Idle, (vm.uiState.value as DetailsUiState.Content).movieDownload)
    }

    @Test fun cancellingTheConfirmKeepsTheDownload() = runTest {
        val dl = FakeDownloads().apply { items.value = listOf(dlItem(WatchKey(1), DownloadStatus.COMPLETED)) }
        val vm = vm(dl)
        vm.onRemoveDownloadClicked(WatchKey(1))
        vm.closeOverlay()
        assertTrue(dl.removed.isEmpty())
        assertNull(vm.overlay.value)
    }

    @Test fun retryGoesToTheRepository() = runTest {
        val dl = FakeDownloads()
        val vm = vm(dl)
        vm.onRetryDownload(WatchKey(1))
        assertEquals(listOf(WatchKey(1)), dl.retried)
    }

    @Test fun withoutARepositoryTheDownloadActionsDoNothing() = runTest {
        val vm = vm(null)
        vm.onDownloadClicked(WatchKey(1))
        vm.onRemoveDownloadClicked(WatchKey(1))
        assertNull(vm.overlay.value)
    }
}
