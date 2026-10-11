package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.SubtitleStyleEntity
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.SubtitleBackground
import app.bolbol.tv.core.model.SubtitleColor
import app.bolbol.tv.core.model.SubtitlePosition
import app.bolbol.tv.core.model.SubtitleStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitlePrefsTest {

    private val prefsDao = FakePrefsDao()

    private fun TestScope.prefs() = PrefsRepositoryImpl(prefsDao, UnconfinedTestDispatcher(testScheduler))

    private val custom = SubtitleStyle(
        enabled = false,
        sizeScale = 1.5f,
        color = SubtitleColor.YELLOW,
        background = SubtitleBackground.BOX,
        bold = false,
        position = SubtitlePosition.HIGH,
    )

    // --- style --------------------------------------------------------------------------------

    @Test
    fun styleEmitsDefaultsWhenNothingIsStored() = runTest {
        assertEquals(SubtitleStyle(), prefs().subtitleStyle().first())
    }

    @Test
    fun styleRoundTripsEveryField() = runTest {
        val repo = prefs()
        repo.saveSubtitleStyle(custom)
        assertEquals(custom, repo.subtitleStyle().first())
    }

    @Test
    fun styleFlowEmitsDefaultsThenTheSavedValueThenLaterChanges() = runTest {
        val repo = prefs()
        val emitted = mutableListOf<SubtitleStyle>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.subtitleStyle().collect { emitted += it }
        }
        repo.saveSubtitleStyle(custom)
        repo.saveSubtitleStyle(custom) // identical: no duplicate emission
        repo.saveSubtitleStyle(custom.copy(sizeScale = 0.8f))
        job.cancel()
        assertEquals(listOf(SubtitleStyle(), custom, custom.copy(sizeScale = 0.8f)), emitted)
    }

    @Test
    fun savingTheDefaultStyleOverwritesAnEarlierCustomOne() = runTest {
        val repo = prefs()
        repo.saveSubtitleStyle(custom)
        repo.saveSubtitleStyle(SubtitleStyle())
        assertEquals(SubtitleStyle(), repo.subtitleStyle().first())
    }

    @Test
    fun unknownStoredEnumNamesFallBackToDefaults() = runTest {
        prefsDao.plantStyle(
            SubtitleStyleEntity(enabled = true, sizeScale = 1.25f, color = "NEON", background = "GLOW", bold = false, position = "TOP"),
        )
        val style = prefs().subtitleStyle().first()
        assertEquals(SubtitleStyle(enabled = true, sizeScale = 1.25f, bold = false), style)
    }

    @Test
    fun styleAndPlaybackPrefsDoNotOverwriteEachOther() = runTest {
        val repo = prefs()
        val playback = PlaybackPrefs(audio = AudioType.DUBBED, quality = 720)
        repo.saveGlobalPrefs(playback)
        repo.saveSubtitleStyle(custom)
        repo.saveGlobalPrefs(playback.copy(quality = 480))
        assertEquals(custom, repo.subtitleStyle().first())
        assertEquals(playback.copy(quality = 480), repo.globalPrefs().first())
    }

    // --- offset -------------------------------------------------------------------------------

    @Test
    fun offsetIsZeroWhenNeverSet() = runTest {
        assertEquals(0, prefs().subtitleOffsetMs(55))
    }

    @Test
    fun offsetRoundTripsOnTheStepGrid() = runTest {
        val repo = prefs()
        repo.saveSubtitleOffsetMs(55, 1_500)
        repo.saveSubtitleOffsetMs(56, -2_000)
        assertEquals(1_500, repo.subtitleOffsetMs(55))
        assertEquals(-2_000, repo.subtitleOffsetMs(56))
    }

    @Test
    fun offsetIsRoundedToHalfSeconds() = runTest {
        val repo = prefs()
        for ((input, expected) in listOf(
            0 to 0, 1 to 0, 249 to 0, 250 to 500, 499 to 500, 500 to 500, 749 to 500, 750 to 1_000, 1_234 to 1_000,
            -1 to 0, -249 to 0, -250 to -500, -749 to -500, -750 to -1_000, -1_234 to -1_000,
        )) {
            repo.saveSubtitleOffsetMs(7, input)
            assertEquals("input $input", expected, repo.subtitleOffsetMs(7))
        }
    }

    @Test
    fun offsetIsClampedToPlusMinus120Seconds() = runTest {
        val repo = prefs()
        assertEquals(120_000, SubtitleStyle.OFFSET_MAX_MS)
        repo.saveSubtitleOffsetMs(7, 50_000) // well inside the range now (user request: up to ±50 s and more)
        assertEquals(50_000, repo.subtitleOffsetMs(7))
        repo.saveSubtitleOffsetMs(7, -50_000)
        assertEquals(-50_000, repo.subtitleOffsetMs(7))
        repo.saveSubtitleOffsetMs(7, 120_000)
        assertEquals(SubtitleStyle.OFFSET_MAX_MS, repo.subtitleOffsetMs(7))
        repo.saveSubtitleOffsetMs(7, 120_250)
        assertEquals(120_000, repo.subtitleOffsetMs(7))
        repo.saveSubtitleOffsetMs(7, 999_999)
        assertEquals(120_000, repo.subtitleOffsetMs(7))
        repo.saveSubtitleOffsetMs(7, -999_999)
        assertEquals(-SubtitleStyle.OFFSET_MAX_MS, repo.subtitleOffsetMs(7))
        repo.saveSubtitleOffsetMs(7, Int.MAX_VALUE)
        assertEquals(120_000, repo.subtitleOffsetMs(7))
        repo.saveSubtitleOffsetMs(7, Int.MIN_VALUE)
        assertEquals(-120_000, repo.subtitleOffsetMs(7))
    }

    @Test
    fun offsetsAreIsolatedPerTitle() = runTest {
        val repo = prefs()
        repo.saveSubtitleOffsetMs(1, 2_000)
        repo.saveSubtitleOffsetMs(2, -500)
        assertEquals(2_000, repo.subtitleOffsetMs(1))
        assertEquals(-500, repo.subtitleOffsetMs(2))
        assertEquals(0, repo.subtitleOffsetMs(3))
        repo.saveSubtitleOffsetMs(1, 0)
        assertEquals(0, repo.subtitleOffsetMs(1))
        assertEquals(-500, repo.subtitleOffsetMs(2))
    }

    @Test
    fun resettingTheOffsetRemovesTheRow() = runTest {
        val repo = prefs()
        repo.saveSubtitleOffsetMs(1, 1_000)
        repo.saveSubtitleOffsetMs(1, 100) // rounds to 0
        assertNull(prefsDao.offsets.value[1L])
    }

    @Test
    fun offsetAndPlaybackPrefsOfATitleAreIndependent() = runTest {
        val repo = prefs()
        repo.saveSubtitleOffsetMs(9, 1_000)
        assertNull(repo.titlePrefs(9)) // an offset alone does not create "title prefs"
        repo.saveTitlePrefs(9, PlaybackPrefs(quality = 1080))
        assertEquals(1_000, repo.subtitleOffsetMs(9))
        repo.saveSubtitleOffsetMs(9, -500)
        assertEquals(PlaybackPrefs(quality = 1080), repo.titlePrefs(9))
    }

    @Test
    fun normalizerHandlesTheExtremesWithoutOverflow() {
        assertEquals(120_000, normalizeSubtitleOffsetMs(Int.MAX_VALUE))
        assertEquals(-120_000, normalizeSubtitleOffsetMs(Int.MIN_VALUE))
        assertEquals(0, normalizeSubtitleOffsetMs(0))
    }
}
