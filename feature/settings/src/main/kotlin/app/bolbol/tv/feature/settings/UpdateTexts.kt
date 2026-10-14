package app.bolbol.tv.feature.settings

import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AppUpdate
import app.bolbol.tv.core.model.UpdateFailure
import app.bolbol.tv.core.model.UpdateState

/** What a button on the update page does. */
internal enum class UpdateAction { CHECK, DOWNLOAD, OPEN_PERMISSION_SETTINGS, NONE }

internal data class UpdateButton(val label: String, val action: UpdateAction)

/**
 * Everything the update page shows for one [UpdateState]: a status line, optional notes/size, and one or two
 * buttons (the first takes the page's entry focus). Pure, so the Persian copy is unit-tested.
 *
 * Every Persian sentence here starts with a Persian word (BolbolText takes the paragraph direction from the first
 * strong character, see [PERFORMANCE_DESCRIPTION]).
 */
internal data class UpdateUi(
    val status: String,
    /** Highlight the status in the accent colour (a new version is waiting). */
    val highlight: Boolean = false,
    val notes: String = "",
    val size: String = "",
    val buttons: List<UpdateButton>,
)

internal const val CHECK_LABEL = "بررسی به‌روزرسانی"
internal const val DOWNLOAD_LABEL = "دانلود و نصب"
internal const val RETRY_LABEL = "تلاش دوباره"

internal const val PERMISSION_EXPLANATION =
    "برای نصب نسخهٔ تازه، اندروید باید اجازه بدهد «مهرناز» برنامه نصب کند. این کار فقط یک بار لازم است. " +
        "دکمهٔ زیر را بزنید، گزینهٔ «اجازه دادن از این منبع» را روشن کنید، با دکمهٔ بازگشت برگردید و دوباره «دانلود و نصب» را بزنید."

internal fun updateUi(state: UpdateState): UpdateUi = when (state) {
    UpdateState.Idle -> UpdateUi(
        status = "برای دیدن نسخهٔ تازه، دکمهٔ زیر را بزنید.",
        buttons = listOf(UpdateButton(CHECK_LABEL, UpdateAction.CHECK)),
    )
    UpdateState.Checking -> UpdateUi(
        status = "در حال بررسی…",
        buttons = listOf(UpdateButton("در حال بررسی…", UpdateAction.NONE)),
    )
    is UpdateState.UpToDate -> UpdateUi(
        status = "آخرین نسخه را دارید.",
        buttons = listOf(UpdateButton(CHECK_LABEL, UpdateAction.CHECK)),
    )
    is UpdateState.Available -> UpdateUi(
        status = "نسخهٔ ${state.update.versionName} آماده است.",
        highlight = true,
        notes = state.update.notesFa,
        size = sizeLabel(state.update),
        buttons = listOf(UpdateButton(DOWNLOAD_LABEL, UpdateAction.DOWNLOAD)),
    )
    is UpdateState.Downloading -> UpdateUi(
        status = "در حال دانلود نسخهٔ ${state.update.versionName}…",
        highlight = true,
        buttons = listOf(UpdateButton("دانلود ${percentLabel(state.percent)}", UpdateAction.NONE)),
    )
    is UpdateState.Installing -> UpdateUi(
        status = "در حال نصب… صفحهٔ تأیید اندروید را تأیید کنید.",
        highlight = true,
        buttons = listOf(UpdateButton("نصب…", UpdateAction.NONE)),
    )
    is UpdateState.NeedsInstallPermission -> UpdateUi(
        status = PERMISSION_EXPLANATION,
        highlight = true,
        buttons = listOf(
            UpdateButton("باز کردن تنظیمات اندروید", UpdateAction.OPEN_PERMISSION_SETTINGS),
            UpdateButton(DOWNLOAD_LABEL, UpdateAction.DOWNLOAD),
        ),
    )
    is UpdateState.Failed -> UpdateUi(
        status = failureText(state.failure),
        notes = "",
        // A failure after the update was known retries the download; before that, it retries the check.
        buttons = listOf(
            if (state.update != null) UpdateButton(RETRY_LABEL, UpdateAction.DOWNLOAD)
            else UpdateButton(RETRY_LABEL, UpdateAction.CHECK),
        ),
    )
}

internal fun failureText(failure: UpdateFailure): String = when (failure) {
    UpdateFailure.OFFLINE -> "اتصال برقرار نشد. اینترنت را بررسی کنید و دوباره تلاش کنید."
    UpdateFailure.BAD_MANIFEST -> "اطلاعات نسخهٔ تازه خوانده نشد. کمی بعد دوباره تلاش کنید."
    UpdateFailure.DOWNLOAD_FAILED -> "دانلود ناموفق بود. اتصال را بررسی کنید و دوباره تلاش کنید."
    UpdateFailure.CHECKSUM_MISMATCH -> "فایل دانلودشده سالم نبود و کنار گذاشته شد. دوباره تلاش کنید."
    UpdateFailure.NO_SPACE -> "فضای خالی دستگاه کافی نیست. چند دانلود یا برنامه را حذف کنید."
    UpdateFailure.INSTALL_FAILED -> "نصب انجام نشد. دوباره تلاش کنید."
}

/** «۴۲٪» */
internal fun percentLabel(percent: Int): String = "${percent.coerceIn(0, 100).toPersianDigits()}٪"

/** «حجم: ۳۱ مگابایت», or empty when the manifest has no size. */
internal fun sizeLabel(update: AppUpdate): String {
    if (update.sizeBytes <= 0L) return ""
    val mb = ((update.sizeBytes + 524_288L) / 1_048_576L).coerceAtLeast(1L).toInt()
    return "حجم: ${mb.toPersianDigits()} مگابایت"
}

/** Presets for the developer URL field. The empty string means «the real GitHub address». */
internal const val EMULATOR_MANIFEST_URL = "http://10.0.2.2:8000/update.json"
