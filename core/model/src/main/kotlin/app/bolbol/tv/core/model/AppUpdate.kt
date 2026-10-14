package app.bolbol.tv.core.model

import kotlinx.coroutines.flow.StateFlow

// v1.4 in-app update (docs/plan.md §v1.4). Implemented in core:update; shown in feature:settings
// («به‌روزرسانی») and as a banner on Home. Source: the public releases repo's update.json.

/** A newer signed APK published by tools/release.sh. */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the APK; the download is rejected if it doesn't match. */
    val sha256: String,
    /** Short Persian notes for the user, may be blank. */
    val notesFa: String,
)

enum class UpdateFailure { OFFLINE, BAD_MANIFEST, DOWNLOAD_FAILED, CHECKSUM_MISMATCH, NO_SPACE, INSTALL_FAILED }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersionName: String) : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val percent: Int) : UpdateState
    /** Downloaded and verified; Android's install screen is (being) shown. */
    data class Installing(val update: AppUpdate) : UpdateState
    /**
     * Android refused to open the installer because «مهرناز» may not install apps yet; the UI explains the
     * one-time switch and offers [UpdateRepository.openInstallPermissionSettings].
     */
    data class NeedsInstallPermission(val update: AppUpdate) : UpdateState
    data class Failed(val failure: UpdateFailure, val update: AppUpdate?) : UpdateState
}

interface UpdateRepository {
    val state: StateFlow<UpdateState>

    /** Checks the manifest. Without [force], does nothing if the last check was under 24 h ago. */
    suspend fun check(force: Boolean = false)

    /** Downloads, verifies and opens Android's install screen for the [UpdateState.Available] update. */
    suspend fun downloadAndInstall()

    fun openInstallPermissionSettings()
}
