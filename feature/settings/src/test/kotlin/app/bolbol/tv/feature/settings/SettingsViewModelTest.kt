package app.bolbol.tv.feature.settings

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.SubtitleBackground
import app.bolbol.tv.core.model.SubtitleStyle
import app.bolbol.tv.core.model.UpdateRepository
import app.bolbol.tv.core.model.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakePrefs : PrefsRepository {
        val style = MutableStateFlow(SubtitleStyle())
        val global = MutableStateFlow(PlaybackPrefs())
        val savedStyles = mutableListOf<SubtitleStyle>()

        override fun globalPrefs(): Flow<PlaybackPrefs> = global
        override suspend fun titlePrefs(postId: Long): PlaybackPrefs? = null
        override suspend fun saveGlobalPrefs(prefs: PlaybackPrefs) { global.value = prefs }
        override suspend fun saveTitlePrefs(postId: Long, prefs: PlaybackPrefs) {}
        override fun subtitleStyle(): Flow<SubtitleStyle> = style
        override suspend fun saveSubtitleStyle(style: SubtitleStyle) {
            savedStyles += style
            this.style.value = style
        }
    }

    private class FakeSettingsStore : KeyValueStore {
        val map = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private val prefs = FakePrefs()
    private val appSettings = AppSettings(FakeSettingsStore())
    private var mirror = "a.example.com"
    private var resets = 0

    private fun viewModel() = SettingsViewModel(
        prefs = prefs,
        appSettings = appSettings,
        currentMirror = { mirror },
        resetMirrors = { resets++; mirror = "first.example.com" },
        versionName = "1.2.3",
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsTheSavedStyleAndPrefs() {
        prefs.style.value = SubtitleStyle(background = SubtitleBackground.BOX)
        prefs.global.value = PlaybackPrefs(audio = AudioType.DUBBED, quality = 720)
        val vm = viewModel()
        dispatcher.scheduler.runCurrent()
        assertEquals(SubtitleBackground.BOX, vm.subtitleStyle.value.background)
        assertEquals(PlaybackPrefs(audio = AudioType.DUBBED, quality = 720), vm.playbackPrefs.value)
        assertEquals("1.2.3", vm.versionName)
    }

    @Test
    fun subtitleChangeUpdatesThePreviewAtOnceAndSaves() {
        val vm = viewModel()
        dispatcher.scheduler.runCurrent()

        vm.onSubtitleChange(SubtitleChange.SizeStep(3))
        assertEquals(1.5f, vm.subtitleStyle.value.sizeScale)
        assertTrue(prefs.savedStyles.isEmpty()) // not yet: the write runs in the background

        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(SubtitleStyle(sizeScale = 1.5f)), prefs.savedStyles)
    }

    @Test
    fun playbackChangeKeepsTheOtherFieldAndSaves() {
        prefs.global.value = PlaybackPrefs(quality = 480)
        val vm = viewModel()
        dispatcher.scheduler.runCurrent()

        vm.onPlaybackChange(PlaybackChange.Audio(AudioType.SUBTITLED))
        dispatcher.scheduler.runCurrent()
        assertEquals(PlaybackPrefs(audio = AudioType.SUBTITLED, quality = 480), prefs.global.value)
    }

    @Test
    fun performanceSwitchGoesToAppSettings() {
        val vm = viewModel()
        assertFalse(vm.showPerformance.value)
        vm.onShowPerformance(true)
        assertTrue(appSettings.showPerformance.value)
        assertTrue(vm.showPerformance.value)
    }

    @Test
    fun resetMirrorsCallsThroughRefreshesTheHostAndShowsTheNoticeBriefly() {
        val vm = viewModel()
        assertEquals("a.example.com", vm.mirror.value)
        assertFalse(vm.mirrorResetDone.value)

        vm.onResetMirrors()
        assertEquals(1, resets)
        assertEquals("first.example.com", vm.mirror.value)
        assertTrue(vm.mirrorResetDone.value)

        dispatcher.scheduler.advanceTimeBy(SettingsViewModel.RESET_NOTICE_MS + 1)
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.mirrorResetDone.value)
    }

    private class FakeUpdates : UpdateRepository {
        override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        val checks = mutableListOf<Boolean>()
        var downloads = 0
        var permissionOpened = 0
        override suspend fun check(force: Boolean) { checks += force }
        override suspend fun downloadAndInstall() { downloads++ }
        override fun openInstallPermissionSettings() { permissionOpened++ }
    }

    @Test
    fun updateActionsGoToTheRepositoryAndStateIsPassedThrough() {
        val updates = FakeUpdates()
        var saved = "https://old"
        val vm = SettingsViewModel(
            prefs = prefs,
            appSettings = appSettings,
            currentMirror = { mirror },
            resetMirrors = {},
            versionName = "1.4.0",
            updates = updates,
            updateScope = CoroutineScope(Dispatchers.Unconfined),
            manifestUrlOverride = { saved },
            setManifestUrlOverride = { saved = it },
        )
        vm.checkForUpdate(force = false)
        vm.checkForUpdate(force = true)
        vm.downloadAndInstall()
        vm.openInstallPermissionSettings()
        assertEquals(listOf(false, true), updates.checks)
        assertEquals(1, updates.downloads)
        assertEquals(1, updates.permissionOpened)

        updates.state.value = UpdateState.UpToDate("1.4.0")
        assertEquals(UpdateState.UpToDate("1.4.0"), vm.updateState.value)

        assertEquals("https://old", vm.manifestUrl.value)
        vm.onManifestUrl("  http://10.0.2.2:8000/update.json ")
        assertEquals("http://10.0.2.2:8000/update.json", vm.manifestUrl.value)
        assertEquals("http://10.0.2.2:8000/update.json", saved)
        vm.onManifestUrl("")
        assertEquals("", saved)
    }

    @Test
    fun refreshMirrorReadsTheCurrentHost() {
        val vm = viewModel()
        mirror = "b.example.com"
        vm.refreshMirror()
        assertEquals("b.example.com", vm.mirror.value)
    }
}
