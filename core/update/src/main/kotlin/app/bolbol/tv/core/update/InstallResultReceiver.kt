package app.bolbol.tv.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the PackageInstaller session status. STATUS_PENDING_USER_ACTION carries the system's
 * confirmation screen, which is started here; every other status is forwarded to the repository.
 */
internal class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    InstallEvents.emit(InstallResult.Failed("no confirmation intent"))
                } else {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { InstallEvents.emit(InstallResult.Failed(it.message)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.emit(InstallResult.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.emit(InstallResult.Aborted)
            else -> InstallEvents.emit(
                InstallResult.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            )
        }
    }
}
