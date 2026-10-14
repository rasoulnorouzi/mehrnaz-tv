package app.bolbol.tv.feature.settings

import app.bolbol.tv.core.model.AppUpdate
import app.bolbol.tv.core.model.UpdateFailure
import app.bolbol.tv.core.model.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTextsTest {
    private val update = AppUpdate(5, "1.4.1", "https://e.com/a.apk", 33_000_000L, "a".repeat(64), "رفع چند ایراد")

    private val allStates: List<UpdateState> = listOf(
        UpdateState.Idle,
        UpdateState.Checking,
        UpdateState.UpToDate("1.4.0"),
        UpdateState.Available(update),
        UpdateState.Downloading(update, 42),
        UpdateState.Installing(update),
        UpdateState.NeedsInstallPermission(update),
    ) + UpdateFailure.entries.flatMap { listOf(UpdateState.Failed(it, null), UpdateState.Failed(it, update)) }

    @Test fun everyTextStartsWithAPersianWordSoItLaysOutRightToLeft() {
        allStates.forEach { state ->
            val ui = updateUi(state)
            assertTrue("status of $state", startsWithRtl(ui.status))
            ui.buttons.forEach { assertTrue("button of $state", startsWithRtl(it.label)) }
            if (ui.size.isNotEmpty()) assertTrue(startsWithRtl(ui.size))
        }
    }

    @Test fun everyStateHasAtLeastOneButtonToKeepTheFocus() {
        allStates.forEach { assertTrue("$it", updateUi(it).buttons.isNotEmpty()) }
    }

    @Test fun availableOffersDownloadWithNotesAndSize() {
        val ui = updateUi(UpdateState.Available(update))
        assertEquals("نسخهٔ 1.4.1 آماده است.", ui.status)
        assertEquals("رفع چند ایراد", ui.notes)
        assertEquals("حجم: ۳۱ مگابایت", ui.size)
        assertEquals(listOf(UpdateButton("دانلود و نصب", UpdateAction.DOWNLOAD)), ui.buttons)
    }

    @Test fun downloadingShowsPersianPercentAndDoesNothingOnPress() {
        val ui = updateUi(UpdateState.Downloading(update, 42))
        assertEquals("دانلود ۴۲٪", ui.buttons.single().label)
        assertEquals(UpdateAction.NONE, ui.buttons.single().action)
        assertEquals("۱۰۰٪", percentLabel(150))
        assertEquals("۰٪", percentLabel(-5))
    }

    @Test fun checkingAndInstallingIgnorePresses() {
        assertEquals(UpdateAction.NONE, updateUi(UpdateState.Checking).buttons.single().action)
        assertEquals(UpdateAction.NONE, updateUi(UpdateState.Installing(update)).buttons.single().action)
    }

    @Test fun permissionStateExplainsAndOffersTheSettingsThenRetry() {
        val ui = updateUi(UpdateState.NeedsInstallPermission(update))
        assertEquals(PERMISSION_EXPLANATION, ui.status)
        assertEquals(
            listOf(UpdateAction.OPEN_PERMISSION_SETTINGS, UpdateAction.DOWNLOAD),
            ui.buttons.map { it.action },
        )
    }

    @Test fun retryAfterFailureDependsOnWhetherTheUpdateWasKnown() {
        assertEquals(
            UpdateAction.CHECK,
            updateUi(UpdateState.Failed(UpdateFailure.OFFLINE, null)).buttons.single().action,
        )
        assertEquals(
            UpdateAction.DOWNLOAD,
            updateUi(UpdateState.Failed(UpdateFailure.CHECKSUM_MISMATCH, update)).buttons.single().action,
        )
    }

    @Test fun everyFailureHasItsOwnText() {
        val texts = UpdateFailure.entries.map { failureText(it) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test fun sizeIsHiddenWhenUnknownAndRoundedUp() {
        assertEquals("", sizeLabel(update.copy(sizeBytes = 0)))
        assertEquals("حجم: ۱ مگابایت", sizeLabel(update.copy(sizeBytes = 10)))
    }
}
